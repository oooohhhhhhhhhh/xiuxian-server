package com.mtxgdn.game.service;

import com.mtxgdn.db.BuffDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BuffService {

    private static final BuffDAO BUFF_DAO = new BuffDAO();

    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(BuffService.class);

    /** 经验倍率 Buff：playerId -> {percentX100, expireAtMs}（内存态，重启即失效，可接受） */
    private static final Map<Long, long[]> EXP_BOOSTS = new java.util.concurrent.ConcurrentHashMap<>();
    /** 伤害反弹 Buff：playerId -> {percentX100, expireAtMs} */
    private static final Map<Long, long[]> REFLECTION_BUFFS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 过期增益「兜底」回收间隔（秒）。
     *
     * <p>为什么还需要兜底：增益加成是物化在 players 表上的（加成直接写进了玩家属性），
     * 玩家离线后不会再访问 Buff，惰性过期就没有触发点，属性会一直虚高，
     * 所以必须有一条与玩家行为无关的低频通道。</p>
     *
     * <p>但代价不应与表总量相关：配合 {@code idx_player_buffs_expire} 索引，
     * 单次代价只与"已过期的行数"相关。原实现是<b>每秒</b>执行一次
     * {@code WHERE expire_time <= ?} 的全表扫描，本改造把它降到默认 60 秒一次，
     * 活跃玩家则完全由访问时的惰性过期即时处理（见 {@link #expirePlayerBuffs}）。</p>
     */
    private static final int EXPIRE_SWEEP_SECONDS =
            Math.max(10, com.mtxgdn.util.AppConfig.getInt("buff.expire_sweep_seconds", 60));

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "buff-expire-sweeper");
                t.setDaemon(true); // 守护线程：不阻止 JVM 退出
                return t;
            });

    static {
        // 首个 tick 延迟 30 秒，避开数据库初始化与启动高峰
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkExpiredBuffs();
            } catch (Throwable t) {
                // 绝不让异常冒泡：一次失败不应终止后续所有 tick
                LOG.warn("增益过期兜底回收任务异常: " + t);
            }
        }, 30, EXPIRE_SWEEP_SECONDS, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(BuffService::cleanupExpiredMemoryBuffs, 30, 30, TimeUnit.SECONDS);
    }

    public static void shutdownScheduler() {
        scheduler.shutdown();
    }

    private static void cleanupExpiredMemoryBuffs() {

        // 服务器正在关闭、或测试里刚换了连接池：这时候没有库可查，
        // 继续跑只会刷一屏 "Connection is closed" 把真问题淹掉。
        if (!com.mtxgdn.db.DatabaseManager.isReady()) {
            return;
        }
        // 内置模块门控：Buff 模块停用后暂停内存增益清理
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("buff")) return;
        long now = System.currentTimeMillis();
        EXP_BOOSTS.entrySet().removeIf(e -> e.getValue()[1] <= now);
        REFLECTION_BUFFS.entrySet().removeIf(e -> e.getValue()[1] <= now);
    }

    // ==================== 经验倍率 Buff（经验翻倍丹） ====================

    /** 添加经验倍率 Buff（同类型刷新时长），percent 如 50 表示 +50% */
    public void addExpBoost(long playerId, int percent, int durationSeconds) {
        if (percent <= 0) return;
        long expireAt = System.currentTimeMillis() + (long) durationSeconds * 1000;
        EXP_BOOSTS.put(playerId, new long[]{percent, expireAt});
    }

    /** 当前经验获取倍率（1.0 = 无加成） */
    public double getExpMultiplier(long playerId) {
        long[] boost = EXP_BOOSTS.get(playerId);
        if (boost == null || boost[1] <= System.currentTimeMillis()) {
            if (boost != null) EXP_BOOSTS.remove(playerId);
            return 1.0;
        }
        return 1.0 + boost[0] / 100.0;
    }

    // ==================== 伤害反弹 Buff（伤害反弹丹） ====================

    /** 添加伤害反弹 Buff（同类型刷新时长），percent 如 30 表示反弹 30% */
    public void addReflectionBuff(long playerId, int percent, int durationSeconds) {
        if (percent <= 0) return;
        long expireAt = System.currentTimeMillis() + (long) durationSeconds * 1000;
        REFLECTION_BUFFS.put(playerId, new long[]{percent, expireAt});
    }

    /** 当前伤害反弹百分比（0 = 无反弹） */
    public int getReflectionPercent(long playerId) {
        long[] buff = REFLECTION_BUFFS.get(playerId);
        if (buff == null || buff[1] <= System.currentTimeMillis()) {
            if (buff != null) REFLECTION_BUFFS.remove(playerId);
            return 0;
        }
        return (int) buff[0];
    }

    // ==================== 清空全部 Buff（洗髓丹） ====================

    /** 移除玩家全部活跃 Buff 并回退属性加成。 */
    public void removeAllBuffs(long playerId) {
        for (String id : BUFF_DAO.findActiveBuffIds(playerId, System.currentTimeMillis())) {
            try {
                removeBuff(playerId, id);
            } catch (Exception ignored) {
            }
        }
    }

    public static class ActiveBuff {
        private String buffId;
        private int attackBonus;
        private int defenseBonus;
        private int speedBonus;
        private int spiritBonus;
        private long expireTime;

        public ActiveBuff(String buffId, int attackBonus, int defenseBonus, int speedBonus, int spiritBonus, long expireTime) {
            this.buffId = buffId;
            this.attackBonus = attackBonus;
            this.defenseBonus = defenseBonus;
            this.speedBonus = speedBonus;
            this.spiritBonus = spiritBonus;
            this.expireTime = expireTime;
        }

        public String getBuffId() { return buffId; }
        public int getAttackBonus() { return attackBonus; }
        public int getDefenseBonus() { return defenseBonus; }
        public int getSpeedBonus() { return speedBonus; }
        public int getSpiritBonus() { return spiritBonus; }
        public long getExpireTime() { return expireTime; }
        public boolean isExpired() { return System.currentTimeMillis() >= expireTime; }
    }

    public void addBuff(long playerId, String buffId, int attackBonus, int defenseBonus, int speedBonus, int spiritBonus, int durationSeconds) {
        long expireTime = System.currentTimeMillis() + (long) durationSeconds * 1000;
        DatabaseManager.runTransaction(conn -> {
            applyBuff(conn, playerId, buffId, attackBonus, defenseBonus, speedBonus, spiritBonus, expireTime);
            return true;
        });
    }

    /**
     * 在<b>调用方已开启的事务</b>里加增益。
     *
     * <p><b>为什么需要这个重载</b>：{@link #addBuff(long, String, int, int, int, int, int)}
     * 自己开事务。而丹药/装备的效果是在别人的事务里执行的
     * （见 {@code ItemService.useItem}、{@code ItemService.equipItem}）——
     * 如果那里调用自开事务的版本，增益行会<b>提前提交</b>：
     * 外层事务随后回滚（例如扣物品失败、后续效果抛异常），玩家已经"白得"了一个增益，
     * 而且这个增益在回滚后没有任何东西会去撤销它。</p>
     *
     * <p>把连接传进来，增益行与外层事务同生共死。</p>
     */
    public void addBuff(Connection conn, long playerId, String buffId,
                        int attackBonus, int defenseBonus, int speedBonus, int spiritBonus,
                        int durationSeconds) throws SQLException {
        long expireTime = System.currentTimeMillis() + (long) durationSeconds * 1000;
        applyBuff(conn, playerId, buffId, attackBonus, defenseBonus, speedBonus, spiritBonus, expireTime);
    }

    /**
     * 加增益的实际逻辑（不加事务、不取连接，完全由调用方的事务托管）。
     *
     * @param expireTime 过期时间戳（毫秒）
     */
    private void applyBuff(Connection conn, long playerId, String buffId,
                           int attackBonus, int defenseBonus, int speedBonus, int spiritBonus,
                           long expireTime) throws SQLException {
        // 先清掉该玩家已过期的增益：否则"同 buffId 已存在"会把一条早已过期的行当成有效增益
        expirePlayerBuffs(conn, playerId, System.currentTimeMillis());
        // 同 buffId 已存在（如重复使用同种丹药）：只刷新时长，不重复叠加属性
        if (BUFF_DAO.buffExists(conn, playerId, buffId)) {
            BUFF_DAO.refreshBuffExpire(conn, playerId, buffId, expireTime);
            return;
        }

        BUFF_DAO.insertBuff(conn, playerId, buffId,
                attackBonus, defenseBonus, speedBonus, spiritBonus, expireTime);

        PlayerService playerService = ServiceRegistry.getPlayerService();
        if (attackBonus != 0) playerService.addAttack(conn, playerId, attackBonus);
        if (defenseBonus != 0) playerService.addDefense(conn, playerId, defenseBonus);
        if (speedBonus != 0) playerService.addSpeed(conn, playerId, speedBonus);
        if (spiritBonus != 0) playerService.addSpirit(conn, playerId, spiritBonus);
    }

    public void removeBuff(long playerId, String buffId) {
        DatabaseManager.runTransaction(conn -> {
            // 先结算过期增益，避免"已过期但尚未回退"的行与本次移除相互干扰
            expirePlayerBuffs(conn, playerId, System.currentTimeMillis());
            BuffDAO.BonusSum bonuses = BUFF_DAO.findBuffBonuses(conn, playerId, buffId);

            BUFF_DAO.deleteBuff(conn, playerId, buffId);

            PlayerService playerService = ServiceRegistry.getPlayerService();
            if (bonuses.attack != 0) playerService.addAttack(conn, playerId, -bonuses.attack);
            if (bonuses.defense != 0) playerService.addDefense(conn, playerId, -bonuses.defense);
            if (bonuses.speed != 0) playerService.addSpeed(conn, playerId, -bonuses.speed);
            if (bonuses.spirit != 0) playerService.addSpirit(conn, playerId, -bonuses.spirit);

            return true;
        });
    }

    public Map<String, Object> getActiveBuffs(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> buffList = new ArrayList<>();

        long now = System.currentTimeMillis();
        // 用事务而不是裸连接：expirePlayerBuffs 会 DELETE + 回退属性，
        // 那不是只读操作，必须和随后的查询落在同一个事务里
        List<BuffDAO.ActiveBuffRow> rows = DatabaseManager.runTransaction(conn -> {
            // 读取即结算：过期增益在这里当场回退，玩家看到的属性永远是最新的
            expirePlayerBuffs(conn, playerId, now);
            return BUFF_DAO.findActiveBuffs(conn, playerId, now);
        });
        for (BuffDAO.ActiveBuffRow row : rows) {
            Map<String, Object> buffInfo = new LinkedHashMap<>();
            buffInfo.put("id", row.buffId);
            buffInfo.put("attackBonus", row.attackBonus);
            buffInfo.put("defenseBonus", row.defenseBonus);
            buffInfo.put("speedBonus", row.speedBonus);
            buffInfo.put("spiritBonus", row.spiritBonus);
            buffInfo.put("remainingSeconds", Math.max(0, (row.expireTime - now) / 1000));
            buffList.add(buffInfo);
        }

        result.put("buffs", buffList);
        result.put("count", buffList.size());
        return result;
    }

    /**
     * 惰性过期：只处理该玩家自己的过期增益。
     *
     * <p>玩家查 / 加 / 清 Buff 或加载角色时调用，保证「玩家看到的就是最新的」，
     * 不必等后台任务——这也是能把全表扫描从每秒一次降下来的前提。</p>
     *
     * <p>属性回退与行删除绑定在同一条条件 DELETE 上（见 {@link #rollbackIfClaimed}）：
     * 只有真正删掉这一行的调用方才回退属性，因此两个请求线程并发触发、
     * 或与兜底任务撞车时都不会重复扣减。</p>
     */
    private static void expirePlayerBuffs(Connection conn, long playerId, long now) throws SQLException {
        List<BuffDAO.ExpiredBuffRow> expired = BUFF_DAO.findExpiredBuffsForPlayer(conn, playerId, now);
        if (expired.isEmpty()) return;

        PlayerService playerService = ServiceRegistry.getPlayerService();
        for (BuffDAO.ExpiredBuffRow row : expired) {
            rollbackIfClaimed(conn, playerId, row.buffId,
                    row.attackBonus, row.defenseBonus, row.speedBonus, row.spiritBonus,
                    now, playerService);
        }
    }

    /**
     * 认领并回退一条过期增益。
     *
     * <p>幂等认领：条件 DELETE 命中 1 行才是「这一行归我处理」的证据，
     * 只有拿到才算认领成功才回退属性。条件里带上 {@code expire_time <= ?}，
     * 因此玩家在期间重新嗑药刷新了时长（expire_time 被推到未来）时不会被误删、误扣。</p>
     */
    private static void rollbackIfClaimed(Connection conn, long playerId, String buffId,
                                          int atk, int def, int spd, int spi,
                                          long now, PlayerService playerService) throws SQLException {
        if (!BUFF_DAO.deleteExpiredBuffIfClaimed(conn, playerId, buffId, now)) {
            return; // 已被其他线程或兜底任务抢先处理，不再重复回退
        }

        if (atk != 0) playerService.addAttack(conn, playerId, -atk);
        if (def != 0) playerService.addDefense(conn, playerId, -def);
        if (spd != 0) playerService.addSpeed(conn, playerId, -spd);
        if (spi != 0) playerService.addSpirit(conn, playerId, -spi);
    }

    /**
     * 过期增益的兜底回收（低频，默认 60 秒一次；原实现为每秒一次全表扫描）。
     *
     * <p>它只负责「离线玩家」这一类惰性路径覆盖不到的情况；在线玩家在访问 Buff 时
     * 就已经被 {@link #expirePlayerBuffs} 即时处理过了。</p>
     */
    private static void checkExpiredBuffs() {
        // 内置模块门控：Buff 模块停用后暂停过期增益清理
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("buff")) return;
        long now = System.currentTimeMillis();
        List<BuffDAO.ExpiredBuffRow> expiredList = BUFF_DAO.findAllExpiredBuffs(now);
        if (expiredList.isEmpty()) return;

        DatabaseManager.runTransaction(conn -> {
            PlayerService playerService = ServiceRegistry.getPlayerService();
            for (BuffDAO.ExpiredBuffRow row : expiredList) {
                rollbackIfClaimed(conn, row.playerId, row.buffId,
                        row.attackBonus, row.defenseBonus, row.speedBonus, row.spiritBonus,
                        now, playerService);
            }
            return true;
        });
    }

    public void clearAllBuffs(long playerId) {
        DatabaseManager.runTransaction(conn -> {
            // 先结算过期增益：过期行由认领逻辑回退，剩下的才是本次要清掉的有效增益
            expirePlayerBuffs(conn, playerId, System.currentTimeMillis());
            BuffDAO.BonusSum total = BUFF_DAO.sumAllBonuses(conn, playerId);

            BUFF_DAO.deleteAllBuffs(conn, playerId);

            PlayerService playerService = ServiceRegistry.getPlayerService();
            if (total.attack != 0) playerService.addAttack(conn, playerId, -total.attack);
            if (total.defense != 0) playerService.addDefense(conn, playerId, -total.defense);
            if (total.speed != 0) playerService.addSpeed(conn, playerId, -total.speed);
            if (total.spirit != 0) playerService.addSpirit(conn, playerId, -total.spirit);

            return true;
        });
    }

    public int getTotalAttackBonus(long playerId) {
        return getTotalBonus(playerId, "attack_bonus");
    }

    public int getTotalDefenseBonus(long playerId) {
        return getTotalBonus(playerId, "defense_bonus");
    }

    public int getTotalSpeedBonus(long playerId) {
        return getTotalBonus(playerId, "speed_bonus");
    }

    public int getTotalSpiritBonus(long playerId) {
        return getTotalBonus(playerId, "spirit_bonus");
    }

    private int getTotalBonus(long playerId, String column) {
        long now = System.currentTimeMillis();
        try {
            // 用事务而不是裸连接：结算会 DELETE + 回退属性，不是只读操作
            return DatabaseManager.runTransaction(conn -> {
                // 读取即结算：统计前先把过期行回退掉，避免把已失效的加成算进来
                expirePlayerBuffs(conn, playerId, now);
                return BUFF_DAO.sumActiveBonus(conn, playerId, now, column);
            });
        } catch (Exception e) {
            return 0;
        }
    }

    public void reloadBuffsOnStartup(long playerId) {
        DatabaseManager.runTransaction(conn -> {
            // 登录 / 重启加载：先把停机期间过期的增益回退掉，再汇总仍然有效的加成。
            // 这一步很关键——停机期间没有后台任务，只有这里能把虚高的属性修正回来。
            long now = System.currentTimeMillis();
            expirePlayerBuffs(conn, playerId, now);
            BuffDAO.BonusSum total = BUFF_DAO.sumStillValidBonuses(conn, playerId, now);

            PlayerService playerService = ServiceRegistry.getPlayerService();
            if (total.attack != 0) playerService.addAttack(conn, playerId, total.attack);
            if (total.defense != 0) playerService.addDefense(conn, playerId, total.defense);
            if (total.speed != 0) playerService.addSpeed(conn, playerId, total.speed);
            if (total.spirit != 0) playerService.addSpirit(conn, playerId, total.spirit);

            return true;
        });
    }
}
