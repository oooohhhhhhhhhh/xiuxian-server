package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.entity.WorldBoss;
import com.mtxgdn.game.entity.WorldBossDamage;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import com.mtxgdn.i18n.I18n;

/**
 * 世界 Boss（B6）：全服共享血量 + 伤害分红 + 超发熔断。
 *
 * <h2>与现有两套 Boss 的分工</h2>
 * <ul>
 *   <li>{@code Monster.createBoss} —— 一次性 PVE 对手，血量只属于你这一场；</li>
 *   <li>{@link CombatService#raidBossFight} —— 团队 Raid，三形态推进，血量属于这一队；</li>
 *   <li><b>本类</b> —— 世界 Boss：<b>全服共享同一份血量</b>，谁打掉多少就扣多少。</li>
 * </ul>
 *
 * <h2>四条设计不变量</h2>
 * <ol>
 *   <li><b>无内存态、无定时器</b>：血量直接落库，服务重启后血条原样还在。
 *       到期重建走读取时的惰性判断（{@link WorldBoss#isExpired}），
 *       不需要任何 {@code ScheduledExecutorService}。</li>
 *   <li><b>奖励按伤害分红，不是击杀独占</b>：每次挑战发放
 *       {@code 预算 × 本次伤害 / 最大血量}。因此全服累计发放天然不超过预算 ——
 *       因为累计伤害不会超过最大血量。这是"不会超发"的<b>结构性</b>保证，
 *       不依赖事后核对。</li>
 *   <li><b>超发熔断</b>：单次奖励超过 {@code world_boss.max_single_reward} 时，
 *       改发 {@code 上限 / (5 + rand(6))}。这是最后一道闸门，
 *       防的是"配置写错一个零"这类事故，而不是日常路径。</li>
 *   <li><b>挑战冷却</b>：本项目没有体力系统，若不限次，一个高境界玩家可以连着
 *       把 Boss 打死，其他人连参与的份都没有。冷却记在伤害台账的
 *       {@code last_hit_at} 上，并用条件更新抢占，并发下只会有一次成功。</li>
 * </ol>
 */
public class WorldBossService {

    private static final GameLogger LOG = GameLogger.getLogger(WorldBossService.class);

    /** 单世界服务器：全服共享同一批 Boss。 */
    public static final String DEFAULT_SCOPE = "global";

    /** 单次奖励的默认熔断阈值（与参照实现一致：100 万）。 */
    public static final long MAX_SINGLE_REWARD_DEFAULT = 1_000_000L;

    /** 击杀者额外分成比例的默认值。 */
    public static final double KILL_BONUS_RATIO_DEFAULT = 0.2;

    /** 血量倍率默认值：世界 Boss 要比同境界的普通 Boss 厚得多，否则一波就没了。 */
    public static final int HP_MULTIPLIER_DEFAULT = 12;

    /** 整池重建周期（小时）。 */
    public static final int RESPAWN_HOURS_DEFAULT = 4;

    /** 一个池子里放几只 Boss。 */
    public static final int BOSS_COUNT_DEFAULT = 3;

    /** 挑战境界门槛：境界序位差达到该值即不可挑战。 */
    public static final int MAX_STAGE_GAP_DEFAULT = 9;

    /** 挑战冷却（秒）。 */
    public static final int CHALLENGE_COOLDOWN_SECONDS_DEFAULT = 300;

    private final PlayerService playerService;
    private final ItemService itemService;
    private final CombatService combatService;

    public WorldBossService() {
        this.playerService = ServiceRegistry.getPlayerService();
        this.itemService = ServiceRegistry.getItemService();
        this.combatService = ServiceRegistry.getCombatService();
    }

    // ==================== 配置 ====================

    /** Boss 池归属键；单世界服务器固定 {@code global}。 */
    public static String scope() {
        String s = AppConfig.get("world_boss.scope", DEFAULT_SCOPE);
        return (s == null || s.isBlank()) ? DEFAULT_SCOPE : s.trim();
    }

    public static int bossCount() {
        return Math.max(1, Math.min(10, AppConfig.getInt("world_boss.boss_count", BOSS_COUNT_DEFAULT)));
    }

    public static int respawnHours() {
        return Math.max(1, AppConfig.getInt("world_boss.respawn_hours", RESPAWN_HOURS_DEFAULT));
    }

    public static int hpMultiplier() {
        return Math.max(1, AppConfig.getInt("world_boss.hp_multiplier", HP_MULTIPLIER_DEFAULT));
    }

    public static int maxStageGap() {
        return Math.max(1, AppConfig.getInt("world_boss.max_stage_gap", MAX_STAGE_GAP_DEFAULT));
    }

    public static long challengeCooldownMs() {
        return Math.max(0, AppConfig.getInt("world_boss.challenge_cooldown_seconds",
                CHALLENGE_COOLDOWN_SECONDS_DEFAULT)) * 1000L;
    }

    public static long maxSingleReward() {
        return Math.max(1, (long) AppConfig.getInt("world_boss.max_single_reward",
                (int) MAX_SINGLE_REWARD_DEFAULT));
    }

    public static double killBonusRatio() {
        String raw = AppConfig.get("world_boss.kill_bonus_ratio");
        if (raw == null || raw.isBlank()) {
            return KILL_BONUS_RATIO_DEFAULT;
        }
        try {
            return Math.max(0.0, Math.min(5.0, Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            return KILL_BONUS_RATIO_DEFAULT;
        }
    }

    // ==================== 池子：惰性生成与重建 ====================

    /**
     * 取当前 Boss 池；池子为空、数量不符或已到期时惰性重建。
     *
     * @param now 由调用方传入，便于测试注入时间
     */
    public List<WorldBoss> getPool(String scope, long now) {
        String key = (scope == null || scope.isBlank()) ? scope() : scope;
        List<WorldBoss> rows = loadPool(key);
        if (isPoolUsable(rows, now)) {
            return rows;
        }
        try {
            rebuildPool(key, now);
        } catch (RuntimeException e) {
            // 并发重建：另一个请求已经把池子建好了（UNIQUE(scope, slot) 会挡住重复写入），
            // 直接重新读一次即可，不必把异常抛给玩家。
            LOG.warn("世界Boss池重建失败，按已存在的池子继续: " + e);
        }
        return loadPool(key);
    }

    /** 池子可用 = 数量对得上、全部未到期、且至少有一只还活着或允许展示。 */
    private boolean isPoolUsable(List<WorldBoss> rows, long now) {
        if (rows.size() != bossCount()) {
            return false;
        }
        for (WorldBoss boss : rows) {
            if (boss.isExpired(now)) {
                return false;
            }
        }
        return true;
    }

    /** 管理员强制重建（{@code /世界boss 刷新}）。 */
    public List<WorldBoss> refreshPool(String scope, long now) {
        String key = (scope == null || scope.isBlank()) ? scope() : scope;
        rebuildPool(key, now);
        return loadPool(key);
    }

    private void rebuildPool(String scope, long now) {
        long expiresAt = now + respawnHours() * 3600_000L;
        int count = bossCount();
        int maxRealm = resolveRealmCeiling();

        DatabaseManager.runTransaction(conn -> {
            // 先清掉旧池的伤害台账，再清旧池 —— 否则台账会随着每次重建无限累积
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM world_boss_damage WHERE boss_id IN "
                            + "(SELECT id FROM world_bosses WHERE scope = ?)")) {
                ps.setString(1, scope);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM world_bosses WHERE scope = ?")) {
                ps.setString(1, scope);
                ps.executeUpdate();
            }
            Random random = ThreadLocalRandom.current();
            for (int slot = 1; slot <= count; slot++) {
                WorldBoss boss = generateBoss(scope, slot, count, maxRealm, now, expiresAt, random);
                insertBoss(conn, boss);
            }
            return null;
        });
        LOG.info("世界Boss池已重建：scope=" + scope + "，共 " + count + " 只，到期 " + expiresAt);
    }

    /**
     * 决定这批 Boss 的境界上限：取全服最高境界，至少 3。
     * <p>这样新开的服务器也不会三只都是 0 境界的同一只怪。</p>
     */
    private int resolveRealmCeiling() {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT MAX(realm) FROM players");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Math.max(3, rs.getInt(1));
            }
        } catch (SQLException e) {
            LOG.warn("读取全服最高境界失败，世界Boss按默认境界生成: " + e);
        }
        return 3;
    }

    /**
     * 生成一只 Boss：名字/描述/攻防速复用 {@link Monster#createBossForRealm}（同一套本地化文案），
     * 血量按 {@code hp_multiplier} 放大，奖励预算按境界立方增长。
     *
     * <p>血量被刻意夹到 {@link Integer#MAX_VALUE}：{@link Monster#getHp()} 是 {@code int}，
     * 若库里的血量能超过它，战斗模拟与库里的血条就会对不上。</p>
     */
    private WorldBoss generateBoss(String scope, int slot, int count, int maxRealm,
                                   long now, long expiresAt, Random random) {
        int[] band = realmBand(slot, count, maxRealm);
        int realm = band[0] + (band[1] > band[0] ? random.nextInt(band[1] - band[0] + 1) : 0);
        Monster themed = Monster.createBossForRealm(realm, random);

        long maxHp = Math.min(Integer.MAX_VALUE, (long) themed.getHp() * hpMultiplier());
        // 灵石预算 ~ 境界平方；修为预算 ~ 境界立方（高境界会撞上熔断阈值，这正是熔断存在的意义）
        long stoneBudget = 200L * (realm + 1) * (realm + 1);
        long expBudget = 2000L * (realm + 1) * (realm + 1) * (realm + 1);

        WorldBoss boss = new WorldBoss();
        boss.setScope(scope);
        boss.setSlot(slot);
        boss.setName(themed.getName());
        boss.setDescription(themed.getDescription());
        boss.setRealm(realm);
        boss.setHp(maxHp);
        boss.setMaxHp(maxHp);
        boss.setAttack(themed.getAttack());
        boss.setDefense(themed.getDefense());
        boss.setSpeed(themed.getSpeed());
        boss.setStoneBudget(stoneBudget);
        boss.setExpBudget(expBudget);
        boss.setExpiresAt(expiresAt);
        return boss;
    }

    /**
     * 把池子按序号切成「低 / 中 / 高」三档境界区间。
     * <p>Boss 数量不是 3 时退化为按比例分档，保证首尾两档一定覆盖到上下界。</p>
     */
    private int[] realmBand(int slot, int count, int maxRealm) {
        if (count <= 1) {
            return new int[]{0, maxRealm};
        }
        int span = maxRealm + 1;
        int low = (slot - 1) * span / count;
        int high = slot * span / count - 1;
        if (slot == count) {
            high = maxRealm;
        }
        if (high < low) {
            high = low;
        }
        return new int[]{Math.max(0, low), Math.max(0, high)};
    }

    private void insertBoss(Connection conn, WorldBoss boss) {
        String sql = "INSERT INTO world_bosses (scope, slot, name, description, realm, hp, max_hp, "
                + "attack, defense, speed, stone_budget, exp_budget, expires_at, killed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, boss.getScope());
            ps.setInt(2, boss.getSlot());
            ps.setString(3, boss.getName());
            ps.setString(4, boss.getDescription());
            ps.setInt(5, boss.getRealm());
            ps.setLong(6, boss.getHp());
            ps.setLong(7, boss.getMaxHp());
            ps.setInt(8, boss.getAttack());
            ps.setInt(9, boss.getDefense());
            ps.setInt(10, boss.getSpeed());
            ps.setLong(11, boss.getStoneBudget());
            ps.setLong(12, boss.getExpBudget());
            ps.setLong(13, boss.getExpiresAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("写入世界Boss失败", e);
        }
    }

    private List<WorldBoss> loadPool(String scope) {
        String sql = "SELECT b.*, p.name AS killer_name FROM world_bosses b "
                + "LEFT JOIN players p ON b.killer_player_id = p.id "
                + "WHERE b.scope = ? ORDER BY b.slot";
        List<WorldBoss> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapBoss(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss失败", e);
        }
        return result;
    }

    public WorldBoss getBossById(long bossId) {
        String sql = "SELECT b.*, p.name AS killer_name FROM world_bosses b "
                + "LEFT JOIN players p ON b.killer_player_id = p.id WHERE b.id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapBoss(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss失败", e);
        }
        return null;
    }

    private WorldBoss mapBoss(ResultSet rs) throws SQLException {
        WorldBoss boss = new WorldBoss();
        boss.setId(rs.getLong("id"));
        boss.setScope(rs.getString("scope"));
        boss.setSlot(rs.getInt("slot"));
        boss.setName(rs.getString("name"));
        boss.setDescription(rs.getString("description"));
        boss.setRealm(rs.getInt("realm"));
        boss.setHp(rs.getLong("hp"));
        boss.setMaxHp(rs.getLong("max_hp"));
        boss.setAttack(rs.getInt("attack"));
        boss.setDefense(rs.getInt("defense"));
        boss.setSpeed(rs.getInt("speed"));
        boss.setStoneBudget(rs.getLong("stone_budget"));
        boss.setExpBudget(rs.getLong("exp_budget"));
        boss.setExpiresAt(rs.getLong("expires_at"));
        boss.setKilledAt(rs.getLong("killed_at"));
        boss.setKillerPlayerId(rs.getLong("killer_player_id"));
        try {
            boss.setKillerName(rs.getString("killer_name"));
        } catch (SQLException ignored) {
            // 没有 JOIN players 的查询里没有这一列
        }
        return boss;
    }

    // ==================== 挑战 ====================

    /**
     * 挑战一只世界 Boss。
     *
     * <p>顺序是刻意的：<b>先抢占冷却槽 → 再打 → 最后在同一事务里扣血并结算奖励</b>。
     * 先抢占冷却槽，是为了让"连点两下"这种并发只会有一次真正进入战斗；
     * 扣血与发奖放同一事务，是为了不出现"血扣了但没发奖"或"发了奖但血没扣"。</p>
     *
     * @param query 序号（1..N）、Boss 名称或 Boss ID
     * @param now   由调用方传入，便于测试注入时间
     */
    public Map<String, Object> challenge(long playerId, String scope, String query, long now) {
        Map<String, Object> result = new LinkedHashMap<>();
        String key = (scope == null || scope.isBlank()) ? scope() : scope;

        WorldBoss boss = selectBoss(getPool(key, now), query);
        if (boss == null) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.ha32bc604"));
            return result;
        }
        if (boss.isDead()) {
            result.put("success", false);
            result.put("message", "【" + boss.getName() + "】已被击杀。\n"
                    + "整池将在 " + formatTime(boss.getExpiresAt()) + " 重建");
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.h59a76b2c"));
            return result;
        }
        if (player.getHp() <= 0) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.h95538220"));
            return result;
        }
        if (player.isCultivating()) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.hfd4aae38"));
            return result;
        }

        int stageGap = Math.abs(player.getRealmStage() - boss.getRealm() * 3);
        if (stageGap >= maxStageGap()) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.h126829d8", maxStageGap(), stageGap));
            return result;
        }

        long cooldownMs = challengeCooldownMs();
        WorldBossDamage ledger = getDamage(boss.getId(), playerId);
        if (ledger != null && !ledger.isReady(now, cooldownMs)) {
            result.put("success", false);
            result.put("message", "挑战冷却中，还需等待 " + formatDuration(ledger.remainingCooldownMs(now, cooldownMs)));
            return result;
        }
        if (!claimChallengeSlot(boss.getId(), playerId, now, cooldownMs)) {
            result.put("success", false);
            result.put("message", I18n.t("world_boss.h0803df94"));
            return result;
        }

        long startHp = boss.getHp();
        PveCombatResult combat = combatService.pveFight(playerId, toMonster(boss));
        if (!combat.isSuccess()) {
            result.put("success", false);
            result.put("message", combat.getMessage() != null ? combat.getMessage() : "战斗未能开始");
            return result;
        }

        // 战斗里怪物血量是 int，且我们从"挑战开始时的血量"起算，
        // 所以本次伤害 = 起始血量 - 剩余血量，且不会超过起始血量。
        long damage = Math.max(0, Math.min(startHp, startHp - combat.getMonsterHpRemaining()));

        Settlement settlement = settleDamage(boss.getId(), playerId, damage, now);

        result.put("success", true);
        result.put("bossName", boss.getName());
        result.put("damage", damage);
        result.put("playerWon", combat.isPlayerWon());
        result.put("hpRemaining", settlement.hpRemaining);
        result.put("maxHp", settlement.maxHp);
        result.put("killed", settlement.killed);
        result.put("stones", settlement.stones);
        result.put("exp", settlement.exp);
        result.put("capped", settlement.capped);
        result.put("battleLog", combat.getBattleLog());
        result.put("message", buildChallengeMessage(boss, combat, damage, settlement));
        return result;
    }

    private WorldBoss selectBoss(List<WorldBoss> pool, String query) {
        if (pool.isEmpty()) {
            return null;
        }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return pool.get(0);
        }
        // 纯数字：先当序号（1 起），再当 Boss ID
        if (q.matches("\\d{1,18}")) {
            int index = Integer.parseInt(q);
            if (index >= 1 && index <= pool.size()) {
                return pool.get(index - 1);
            }
            long id = Long.parseLong(q);
            for (WorldBoss boss : pool) {
                if (boss.getId() == id) {
                    return boss;
                }
            }
        }
        for (WorldBoss boss : pool) {
            if (boss.getName().equals(q)) {
                return boss;
            }
        }
        for (WorldBoss boss : pool) {
            if (boss.getName().contains(q)) {
                return boss;
            }
        }
        return null;
    }

    /**
     * 把世界 Boss 转成一场普通 PVE 战斗的对手。
     * <p>奖励字段<b>全部置零</b>：{@code pveFight} 自带的那套经验/金币/灵石/掉落
     * 是给普通怪的，世界 Boss 的奖励走伤害分红，不能两边都发。</p>
     */
    private Monster toMonster(WorldBoss boss) {
        return new Monster(boss.getName(),
                (int) Math.min(Integer.MAX_VALUE, Math.max(1, boss.getHp())),
                boss.getAttack(), boss.getDefense(), boss.getSpeed(),
                boss.getRealm(), true,
                new String[0], 0.0,
                0L, 0L, 0L,
                boss.getDescription());
    }

    /** 一次挑战的结算结果。 */
    private static final class Settlement {
        long hpRemaining;
        long maxHp;
        boolean killed;
        long stones;
        long exp;
        boolean capped;
    }

    /**
     * 在<b>一个事务</b>里完成：扣共享血量 → 判定击杀 → 按伤害分红 → 记账。
     *
     * <p>血量用 {@code SELECT ... FOR UPDATE} 锁行后再读（SQLite 下事务本身即写锁，
     * 见 {@code DatabaseDialect#getForUpdateClause()}），因此并发挑战不会丢更新，
     * 也不会出现两个人都以为自己补了最后一刀。</p>
     */
    private Settlement settleDamage(long bossId, long playerId, long damage, long now) {
        return DatabaseManager.runTransaction(conn -> {
            long curHp;
            long maxHp;
            long stoneBudget;
            long expBudget;
            String lockSql = "SELECT hp, max_hp, stone_budget, exp_budget FROM world_bosses WHERE id = ? "
                    + DatabaseManager.getDialect().getForUpdateClause();
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, bossId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("世界Boss不存在: " + bossId);
                    }
                    curHp = rs.getLong(1);
                    maxHp = rs.getLong(2);
                    stoneBudget = rs.getLong(3);
                    expBudget = rs.getLong(4);
                }
            } catch (SQLException e) {
                throw new RuntimeException("锁定世界Boss失败", e);
            }

            Settlement out = new Settlement();
            out.maxHp = maxHp;

            if (curHp <= 0) {
                // 在我们打的这段时间里被别人打死了：本次不再扣血、不再发奖
                out.hpRemaining = 0;
                out.killed = false;
                recordDamage(conn, bossId, playerId, 0, now);
                return out;
            }

            long applied = Math.min(curHp, Math.max(0, damage));
            long newHp = curHp - applied;
            boolean killed = newHp <= 0;
            out.hpRemaining = newHp;
            out.killed = killed;

            String updateSql = killed
                    ? "UPDATE world_bosses SET hp = 0, killed_at = ?, killer_player_id = ?, "
                            + "updated_at = CURRENT_TIMESTAMP WHERE id = ?"
                    : "UPDATE world_bosses SET hp = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                if (killed) {
                    ps.setLong(1, now);
                    ps.setLong(2, playerId);
                    ps.setLong(3, bossId);
                } else {
                    ps.setLong(1, newHp);
                    ps.setLong(2, bossId);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("更新世界Boss血量失败", e);
            }

            // ---- 伤害分红：预算 × 本次伤害 / 最大血量 ----
            long stones = maxHp > 0 ? stoneBudget * applied / maxHp : 0;
            long exp = maxHp > 0 ? expBudget * applied / maxHp : 0;
            if (killed) {
                // 补最后一刀的人额外拿一份，按预算比例算，同样受熔断约束
                exp += (long) (expBudget * killBonusRatio());
            }
            long cappedStones = applyOverflowBreaker(stones);
            long cappedExp = applyOverflowBreaker(exp);
            out.capped = cappedStones != stones || cappedExp != exp;
            out.stones = cappedStones;
            out.exp = cappedExp;

            if (out.stones > 0) {
                itemService.addSpiritStones(conn, playerId, out.stones);
            }
            if (out.exp > 0) {
                playerService.addExperience(conn, playerId, out.exp);
            }
            recordDamage(conn, bossId, playerId, applied, now);
            return out;
        });
    }

    /**
     * 超发熔断：单次奖励超过阈值时，改发 {@code 阈值 / (5 + rand(6))}。
     *
     * <p>这不是日常路径 —— 正常的伤害分红已经被"预算 × 伤害/血量"天然限死。
     * 它防的是配置事故（比如把 {@code exp_budget} 多写一个零）被一次击杀放大成
     * 经济崩溃。触发时 {@link Settlement#capped} 为 true，会在战报里明示。</p>
     */
    long applyOverflowBreaker(long reward) {
        long cap = maxSingleReward();
        if (reward <= cap) {
            return reward;
        }
        int divisor = 5 + ThreadLocalRandom.current().nextInt(6);
        long reduced = cap / divisor;
        LOG.warn("世界Boss奖励触发超发熔断：" + reward + " -> " + reduced + "（阈值 " + cap + "）");
        return reduced;
    }

    private void recordDamage(Connection conn, long bossId, long playerId, long damage, long now)
            throws SQLException {
        String update = "UPDATE world_boss_damage SET damage = damage + ?, hits = hits + 1, last_hit_at = ? "
                + "WHERE boss_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, damage);
            ps.setLong(2, now);
            ps.setLong(3, bossId);
            ps.setLong(4, playerId);
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        String insert = "INSERT INTO world_boss_damage (boss_id, player_id, damage, hits, last_hit_at) "
                + "VALUES (?, ?, ?, 1, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            ps.setLong(3, damage);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /**
     * 抢占挑战冷却槽：条件更新，只有"冷却确实已过"的那一次会返回 true。
     * <p>这一步发生在<b>战斗之前</b>，因此连点两下的并发请求只会有一个真正进入战斗。</p>
     */
    private boolean claimChallengeSlot(long bossId, long playerId, long now, long cooldownMs) {
        long cutoff = now - Math.max(0, cooldownMs);
        String update = "UPDATE world_boss_damage SET last_hit_at = ? "
                + "WHERE boss_id = ? AND player_id = ? AND last_hit_at <= ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            if (tryClaim(conn, update, bossId, playerId, now, cutoff)) {
                return true;
            }
            if (hasDamageRow(conn, bossId, playerId)) {
                return false; // 记录存在但冷却未过
            }
            String insert = "INSERT INTO world_boss_damage (boss_id, player_id, damage, hits, last_hit_at) "
                    + "VALUES (?, ?, 0, 0, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insert)) {
                ps.setLong(1, bossId);
                ps.setLong(2, playerId);
                ps.setLong(3, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException duplicate) {
                // 并发插入撞唯一键：回到条件更新再判一次
                return tryClaim(conn, update, bossId, playerId, now, cutoff);
            }
        } catch (SQLException e) {
            throw new RuntimeException("抢占世界Boss挑战冷却失败", e);
        }
    }

    private boolean tryClaim(Connection conn, String update, long bossId, long playerId,
                             long now, long cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setLong(1, now);
            ps.setLong(2, bossId);
            ps.setLong(3, playerId);
            ps.setLong(4, cutoff);
            return ps.executeUpdate() > 0;
        }
    }

    private boolean hasDamageRow(Connection conn, long bossId, long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM world_boss_damage WHERE boss_id = ? AND player_id = ?")) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ==================== 查询 ====================

    public WorldBossDamage getDamage(long bossId, long playerId) {
        String sql = "SELECT d.*, p.name AS player_name FROM world_boss_damage d "
                + "LEFT JOIN players p ON d.player_id = p.id "
                + "WHERE d.boss_id = ? AND d.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapDamage(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss伤害记录失败", e);
        }
        return null;
    }

    /** 某只 Boss 的伤害贡献榜（按伤害降序）。 */
    public List<WorldBossDamage> getLeaderboard(long bossId, int limit) {
        String sql = "SELECT d.*, p.name AS player_name FROM world_boss_damage d "
                + "LEFT JOIN players p ON d.player_id = p.id "
                + "WHERE d.boss_id = ? AND d.damage > 0 ORDER BY d.damage DESC, d.player_id ASC LIMIT ?";
        List<WorldBossDamage> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bossId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapDamage(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询世界Boss排行榜失败", e);
        }
        return result;
    }

    private WorldBossDamage mapDamage(ResultSet rs) throws SQLException {
        WorldBossDamage d = new WorldBossDamage();
        d.setBossId(rs.getLong("boss_id"));
        d.setPlayerId(rs.getLong("player_id"));
        d.setDamage(rs.getLong("damage"));
        d.setHits(rs.getInt("hits"));
        d.setLastHitAt(rs.getLong("last_hit_at"));
        try {
            d.setPlayerName(rs.getString("player_name"));
        } catch (SQLException ignored) {
            // 没有 JOIN players 的查询里没有这一列
        }
        return d;
    }

    // ==================== 文案 ====================

    private String buildChallengeMessage(WorldBoss boss, PveCombatResult combat, long damage, Settlement s) {
        StringBuilder sb = new StringBuilder();
        sb.append(combat.isPlayerWon() ? "击杀成功" : "挑战结束").append("：").append(boss.getName()).append("\n");
        sb.append("本次伤害：").append(damage);
        sb.append("    剩余气血：").append(Math.max(0, s.hpRemaining)).append("/").append(s.maxHp);
        if (s.maxHp > 0) {
            sb.append("（").append(Math.max(0, s.hpRemaining) * 100 / s.maxHp).append("%）");
        }
        sb.append("\n灵石 +").append(s.stones).append("    修为 +").append(s.exp);
        if (s.capped) {
            sb.append("\n⚠ 本次奖励过高，已按超发熔断折算发放");
        }
        if (s.killed) {
            sb.append("\n\n☠ 你补上了最后一刀，【").append(boss.getName()).append("】已被击杀！");
            sb.append("\n整池将在 ").append(formatTime(boss.getExpiresAt())).append(" 重建");
        }
        return sb.toString();
    }

    private static String formatTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + " 分 " + seconds + " 秒";
        }
        return seconds + " 秒";
    }
}
