package com.mtxgdn.game.service;

import com.mtxgdn.db.PlayerDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.config.NewbieRewardConfig;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

/**
 * 角色（{@code players}）相关的业务逻辑。
 *
 * <p><b>本类不写 JDBC</b>：所有 {@code players} 表的读写（含方言差异、行→实体映射、
 * 级联删除的事务）都在 {@link PlayerDAO} 里。这里只剩三类东西 ——</p>
 * <ul>
 *   <li><b>计算</b>：修炼/经验/称号/凝实/强化的加成，以及战力属性汇总；</li>
 *   <li><b>校验与组装</b>：闭关前置条件、疗伤扣灵石、新手礼包、返回值 Map；</li>
 *   <li><b>转调其它服务</b>：{@code ItemService}、{@code BuffService}、
 *       {@code TechniqueService}、{@code FormationService}、{@code MapService}…</li>
 * </ul>
 *
 * <p>需要"在事务里读写角色"的调用方（{@code RealmService}、{@code BuffService}、
 * {@code TradeService} 等）继续用本类那些收 {@link Connection} 的重载 ——
 * 它们把连接原样交给 {@link PlayerDAO}，绝不会在事务里另开连接
 * （SQLite 下那会抛 {@code SQLITE_BUSY_SNAPSHOT} 或读到旧快照）。</p>
 */
public class PlayerService {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerService.class);

    /** {@code players} 表的读写入口（无状态，可安全共享）。 */
    private static final PlayerDAO PLAYER_DAO = new PlayerDAO();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(PlayerService::tickAutoHeal, 10, 10, TimeUnit.SECONDS);
        LOG.info("玩家自动回血任务已启动（每10秒回复1%最大生命值）");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("玩家自动回血任务已停止");
    }

    public PlayerInfo getPlayerByUserId(long userId) {
        PlayerInfo info = PLAYER_DAO.findInfoByUserId(userId);
        if (info != null) {
            touchActive(info.getId(), info.getLastActiveTime());
        }
        return info;
    }

    /**
     * 按 <b>角色 ID（{@code players.id}）</b> 取玩家。
     *
     * <p>与 {@link #getPlayerByUserIdRaw(long)} 的区别只在 id 域：本方法收 {@code players.id}，
     * 那个方法收 {@code users.id}。玩法层（背包、技能、秘境、战斗、宗门…）拿到的几乎都是
     * playerId，应使用本方法。</p>
     */
    public Player getPlayerById(long playerId) {
        Player player = PLAYER_DAO.findById(playerId);
        if (player != null) {
            touchActive(player.getId(), player.getLastActiveTime());
        }
        return player;
    }

    /**
     * 按角色 ID 取 {@link PlayerInfo}（而非 {@link Player}）。
     *
     * <p>{@link #getPlayerById(long)} 返回的是另一个映射（Player），
     * 而语言解析只需要 PlayerInfo 上那一个字段，为它单独查一遍库不划算。
     * 与 {@link #getPlayerByUserId(long)} 同样会在读取时顺手刷新活跃时间。</p>
     */
    public PlayerInfo getPlayerInfoById(long playerId) {
        PlayerInfo info = PLAYER_DAO.findInfoById(playerId);
        if (info != null) {
            touchActive(info.getId(), info.getLastActiveTime());
        }
        return info;
    }

    /**
     * 设置界面语言。
     *
     * <p>不做合法性校验——调用方（REST 层）负责把字符串交给 {@code GameLanguage.parse}
     * 判定。这里刻意接受任意非空字符串落库，理由：将来新增语言时老客户端可能还不知道，
     * 而"客户端不认识的语言"应表现为回落到默认语言，而不是拒绝写入。</p>
     *
     * @return 是否更新成功（角色不存在时返回 false）
     */
    public boolean setLanguage(long playerId, String language) {
        if (language == null) {
            language = "";
        }
        return PLAYER_DAO.updateLanguage(playerId, language);
    }

    /**
     * 「最近活跃时间」的节流写入间隔（毫秒）。
     * <p>同一玩家每小时最多写一次 —— 它只服务于"是否弃坑"这种天级的判定，
     * 没必要每条指令都写一次库。</p>
     */
    private static final long ACTIVE_TOUCH_INTERVAL_MS = 60L * 60 * 1000;

    /**
     * 惰性刷新玩家的 {@code last_active_time}（所有通道通用的"最后出现时间"）。
     *
     * <p>设计取舍：</p>
     * <ul>
     *   <li><b>为什么不用 {@code last_offline_time}</b>：那个字段只有 WebSocket 断线路径写入
     *       （见 GameWebSocketApp），QQ / REST / Minecraft 玩家永远是 0，无法作为弃坑依据。</li>
     *   <li><b>为什么在读取路径上写库</b>：玩家活跃的唯一可靠信号就是"他被读到了"。
     *       为控制成本，按小时节流；写入失败<b>绝不影响任何玩法</b>，直接吞掉异常。</li>
     *   <li><b>为什么 0 视为需要刷新</b>：老库升级后该列全为 0（见 DatabaseMigration），
     *       意味着"从未观测到活跃"，从玩家下一次出现开始计时。</li>
     * </ul>
     */
    private void touchActive(long playerId, long lastActiveTime) {
        long now = System.currentTimeMillis();
        if (lastActiveTime > 0 && now - lastActiveTime < ACTIVE_TOUCH_INTERVAL_MS) {
            return;
        }
        PLAYER_DAO.touchActiveTime(playerId, now);
    }

    /**
     * 按 <b>账号 ID（{@code players.user_id}）</b> 取玩家，且不刷新活跃时间。
     *
     * <p><b>注意 id 域</b>：本方法收的是 {@code users.id}，不是 {@code players.id}。
     * 这两列在库里通常并不相等，传错会读到<b>另一个角色</b>或直接返回 {@code null}，
     * 表现为"角色不存在"这类毫无线索的失败。</p>
     *
     * <p>历史教训：本方法原名 {@code getPlayerRaw(long userId)}，因为形参名不显眼，
     * 曾有 5 处调用方把 playerId 传了进来（学技能、技能书、团队副本入口、心魔结算），
     * 导致这些链路在 {@code players.id != players.user_id} 的库上整体失效。
     * 改名就是为了让代码审查能一眼看出 id 域。</p>
     *
     * @param userId 账号 ID（{@code users.id} / {@code players.user_id}）
     * @return 玩家；账号下没有角色时返回 {@code null}
     * @see #getPlayerById(long) 按角色 ID 取玩家
     */
    public Player getPlayerByUserIdRaw(long userId) {
        return PLAYER_DAO.findByUserId(userId);
    }

    /** V10：角色名长度上限（与 TextSanitizer.sanitizePlayerName 及各通道校验保持一致）。 */
    public static final int MAX_PLAYER_NAME_LENGTH = 16;

    /**
     * V10 修复：创建角色前统一净化并校验角色名。
     *
     * <p>原实现在 service 层没有任何校验，REST {@code /game/player/create} 直接把
     * 请求体里的 name 原样落库 —— 超过 {@code players.name VARCHAR(64)} 会抛 SQLException
     * 变成 500，且允许写入 {@code <>"'&} 等字符（前端渲染不当即 XSS）。</p>
     *
     * <p>这里作为四个通道（REST / OneBot / QQ官方 / Minecraft）共用的唯一收口点，
     * 统一做"净化 + 长度校验"，避免各通道校验强度不一致。</p>
     *
     * @return 净化后的角色名
     * @throws IllegalArgumentException 角色名为空或净化后为空
     */
    static String normalizePlayerName(String name) {
        String sanitized = com.mtxgdn.util.TextSanitizer.sanitizePlayerName(name);
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("角色名不能为空，且不能只包含特殊字符");
        }
        return sanitized;
    }

    public PlayerInfo createPlayer(long userId, String name) {
        // V10：统一净化 + 长度限制，防止超长名导致 DB 异常或写入危险字符
        String safeName = normalizePlayerName(name);
        SpiritualRoot root = SpiritualRoot.drawRandom(new java.util.Random());
        // 新手礼包与插入共用同一个连接：回调里拿到的就是插入所用的那条连接
        long playerId = PLAYER_DAO.insertPlayer(userId, safeName, root, (conn, newPlayerId) -> {
            NewbieRewardConfig rewardConfig = GameConfigLoader.getNewbieRewardConfig();
            if (rewardConfig.isEnabled()) {
                ItemService itemService = ServiceRegistry.getItemService();

                if (rewardConfig.getGoldReward() > 0) {
                    PLAYER_DAO.addGold(conn, newPlayerId, rewardConfig.getGoldReward());
                    // 与原实现一致：新手金币走的是服务层的 addGold(conn, ...)，它会顺手 markDirty
                    markDataDirty(newPlayerId);
                }

                if (rewardConfig.getSpiritStoneReward() > 0) {
                    itemService.addSpiritStones(conn, newPlayerId, rewardConfig.getSpiritStoneReward(), rewardConfig.getSpiritStoneGrade());
                }

                if (rewardConfig.getItems() != null && !rewardConfig.getItems().isEmpty()) {
                    for (NewbieRewardConfig.RewardItem item : rewardConfig.getItems()) {
                        if (item.getItemKey() != null && !item.getItemKey().isBlank() && item.getQuantity() > 0) {
                            itemService.addItem(conn, newPlayerId, item.getItemKey(), item.getQuantity());
                        }
                    }
                }
            }
        });
        PlayerInfo created = getPlayerByUserId(userId);
        // 新玩家自动获得「初入仙途」称号
        if (created != null) {
            try {
                new com.mtxgdn.game.service.TitleService().grantTitle(playerId, "beginner");
            } catch (Exception ignored) {
                // 称号授予失败不影响创建
            }
        }
        // 新玩家立即生成个人数据文件（一用户一个文件）
        if (created != null && com.mtxgdn.playerdata.PlayerDataService.isEnabled()) {
            try {
                new com.mtxgdn.playerdata.PlayerDataService().exportPlayer(created.getId());
            } catch (Exception ignored) {
                // 文件生成失败不影响主流程
            }
        }
        return created;
    }

    public void updatePlayer(long playerId, Player player) {
        PLAYER_DAO.savePlayer(playerId, player);
        markDataDirty(playerId);
    }

    /** 玩家数据已变更，通知文件化存储（一用户一个文件） */
    private void markDataDirty(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
    }

    public boolean updatePlayerName(long playerId, String newName) {
        // V10：与 createPlayer 同一套净化/长度规则，避免改名校验弱于创角校验
        String safeName;
        try {
            safeName = normalizePlayerName(newName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        boolean updated = PLAYER_DAO.updateName(playerId, safeName);
        if (updated) {
            markDataDirty(playerId);
        }
        return updated;
    }

    public void addExperience(long playerId, long exp) {
        PLAYER_DAO.addExperience(playerId, experienceGain(playerId, exp));
        markDataDirty(playerId);
    }

    public void addExperience(Connection conn, long playerId, long exp) throws SQLException {
        PLAYER_DAO.addExperience(conn, playerId, experienceGain(playerId, exp));
        markDataDirty(playerId);
    }

    /** 经验加成结算（称号 + 功法 + 经验翻倍丹等 Buff），只算数、不落库。 */
    private long experienceGain(long playerId, long exp) {
        Player player = getPlayerById(playerId);
        if (player != null) {
            exp = (long) (exp * getFinalExpBonus(player));
            // 经验翻倍丹等 Buff 加成
            double buffMult = ServiceRegistry.getBuffService().getExpMultiplier(playerId);
            if (buffMult > 1.0) {
                exp = (long) (exp * buffMult);
            }
        }
        return exp;
    }

    public void updateCultivationProgress(long playerId, int progress) {
        PLAYER_DAO.updateCultivationProgress(playerId, progress);
    }

    public void setCultivating(long playerId, boolean cultivating) {
        if (cultivating) {
            startCultivation(playerId, System.currentTimeMillis());
        } else {
            stopCultivation(playerId);
        }
    }

    /**
     * 计算玩家当前闭关修炼每秒经验（基础值 × 境界倍率 × 季节 × 地点 × 灵根 × 功法 × 称号 × 天象 × 阵法）。
     * 供 REST / WebSocket / QQ 指令统一使用（仅用于展示，实际结算用 {@link #calculateCultivationExp}）。
     */
    public int calculateCultivationPerSecond(Player player) {
        if (player == null) {
            return 0;
        }
        return (int) Math.max(1, Math.round(cultivationMultiplier(player)));
    }

    /**
     * 计算玩家闭关 elapsedSeconds 秒获得的原始经验。
     * 全程使用 double 累乘（境界 × 季节 × 地点 × 灵根 × 功法 × 称号 × 天象 × 阵法），最后一次性取整，
     * 避免"每秒取整"导致 1×1.05→1 这类小数倍率被吞掉的问题。
     */
    public long calculateCultivationExp(Player player, long elapsedSeconds) {
        if (player == null || elapsedSeconds <= 0) {
            return 0;
        }
        double total = cultivationMultiplier(player) * elapsedSeconds;
        return Math.max(0, Math.round(total));
    }

    /** 汇总全部修炼倍率（在线与离线共用同一套加成） */
    private double cultivationMultiplier(Player player) {
        double base = GameConfigLoader.getCultivationMultiplier(player.getRealm())
                * GameConfigLoader.getCultivationBaseValue();
        double seasonMultiplier = SeasonService.getCultivationMultiplier();
        double locationMultiplier = ServiceRegistry.getMapService().getCultivationBonus(player.getCurrentLocationId());

        double mult = base * seasonMultiplier * locationMultiplier;

        // 灵根修炼效率（木水灵根 +15%）
        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.CULTIVATION_EFFICIENCY)) {
            mult *= (1 + root.getEffectValue());
        }

        // 天象修炼加成（紫气东来 +30% 等）
        mult *= com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic().getCultivationMultiplier();

        // 功法修炼速度（已装备功法的 cultivation_speed_bonus 之和）
        double techniqueBonus = ServiceRegistry.getTechniqueService().getEquippedCultivationSpeedBonus(player.getId());
        if (techniqueBonus > 0) {
            mult *= (1 + techniqueBonus);
        }

        // 称号修炼速度
        mult *= getFinalCultivationSpeed(player);

        // 洞府/阵法修炼加成（聚灵阵等）
        int formationBonus = ServiceRegistry.getFormationService().getTotalCultivationBonus(player.getId());
        if (formationBonus > 0) {
            mult *= (1 + formationBonus / 100.0);
        }

        return mult;
    }

    /**
     * 开始闭关（带完整校验）：角色存在 / 未在闭关中 / 生命值充足。
     */
    public Map<String, Object> beginCultivation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("player.h59a76b2c"));
            return result;
        }
        if (player.isCultivating()) {
            // "闭关中" 但没有起始时间，是无意义状态：算不出闭关时长，结算只能给 0 经验。
            // 它来自历史遗留脏数据（旧版本只写 is_cultivating 未写 cultivation_start_time，
            // 线上表现为 /停止 回"状态异常，已强制结束"，玩家必须重新闭关）。
            // 这种情况绝不能把玩家挡在外面 —— 否则他必须先 /停止（白丢一段闭关）才能重新闭关。
            // 直接以当前时间重新开始，顺带把脏数据自愈掉。
            if (player.getCultivationStartTime() > 0) {
                result.put("success", false);
                result.put("message", I18n.t("player.h7a0f95e1"));
                return result;
            }
            LOG.info("检测到无起始时间的闭关脏数据，已按重新闭关自愈: playerId=" + playerId);
        }
        if (player.getHp() <= 0) {
            result.put("success", false);
            result.put("message", I18n.t("player.h05d87a90"));
            return result;
        }

        long startTime = System.currentTimeMillis();
        startCultivation(playerId, startTime);

        result.put("success", true);
        result.put("cultivating", true);
        result.put("startTime", startTime);
        result.put("cultivationPerSecond", calculateCultivationPerSecond(player));
        result.put("season", SeasonService.getCurrentSeason().name());
        result.put("seasonMultiplier", SeasonService.getCultivationMultiplier());
        result.put("locationId", player.getCurrentLocationId());
        result.put("locationName", ServiceRegistry.getMapService().getLocationName(player.getCurrentLocationId()));
        result.put("locationMultiplier", ServiceRegistry.getMapService().getCultivationBonus(player.getCurrentLocationId()));
        result.put("message", I18n.t("player.h2a0575e8"));
        return result;
    }

    /**
     * 结束闭关（带完整校验）：角色存在 / 确实在闭关中；返回闭关时长与原始经验。
     */
    public Map<String, Object> finishCultivation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("player.h59a76b2c"));
            return result;
        }
        if (!player.isCultivating()) {
            result.put("success", false);
            result.put("message", I18n.t("player.hafb562c8"));
            return result;
        }

        long startTime = player.getCultivationStartTime();
        int elapsedSeconds = startTime > 0 ? (int) ((System.currentTimeMillis() - startTime) / 1000) : 0;
        // 原子认领停止操作：并发多次停止时只有第一次成功，防止经验重复发放
        if (!stopCultivation(playerId)) {
            result.put("success", false);
            result.put("message", I18n.t("player.hf5581950"));
            return result;
        }

        int perSecond = calculateCultivationPerSecond(player);
        long rawExpGained = calculateCultivationExp(player, elapsedSeconds);
        double seasonMultiplier = SeasonService.getCultivationMultiplier();
        double locationMultiplier = ServiceRegistry.getMapService().getCultivationBonus(player.getCurrentLocationId());

        result.put("success", true);
        result.put("elapsedSeconds", elapsedSeconds);
        result.put("cultivationPerSecond", perSecond);
        result.put("rawExpGained", rawExpGained);
        result.put("season", SeasonService.getCurrentSeason().name());
        result.put("seasonMultiplier", seasonMultiplier);
        result.put("locationId", player.getCurrentLocationId());
        result.put("locationName", ServiceRegistry.getMapService().getLocationName(player.getCurrentLocationId()));
        result.put("locationMultiplier", locationMultiplier);
        result.put("message", I18n.t("player.h00836942"));
        return result;
    }

    public void startCultivation(long playerId, long startTime) {
        PLAYER_DAO.startCultivation(playerId, startTime);
        markDataDirty(playerId);
    }

    /**
     * 条件停止闭关：仅当玩家确实处于闭关状态时清零并返回 true。
     * 并发调用下只有第一个请求成功，防止闭关结算被重复发放经验。
     */
    public boolean stopCultivation(long playerId) {
        boolean claimed = PLAYER_DAO.stopCultivation(playerId);
        if (claimed) {
            markDataDirty(playerId);
        }
        return claimed;
    }

    public void setTutorialStep(long playerId, int step) {
        PLAYER_DAO.updateTutorialStep(playerId, step);
    }

    public void setTutorialTips(long playerId, int tips) {
        PLAYER_DAO.updateTutorialTips(playerId, tips);
    }

    public boolean existsByUserId(long userId) {
        return PLAYER_DAO.existsByUserId(userId);
    }

    public void addHp(long playerId, int amount) {
        PLAYER_DAO.addHp(playerId, amount);
        markDataDirty(playerId);
    }

    /**
     * 扣血并保留一个最低值（用于陷阱、跌落等"不应致死"的场景）。
     *
     * <p><b>为什么需要这个方法</b>：这类场景原来在内容层直接
     * {@code UPDATE players SET hp = ?}，写入的是<b>读内存快照算出来的绝对值</b>。
     * 两条通道同时动这个角色时，后写的一方会把对方刚做的治疗/伤害整个覆盖掉
     * （典型的 lost update）；而且绕过了 {@link #markDataDirty(long)}，
     * 玩家快照可能与库里的血量不一致。</p>
     *
     * <p>改为"在 SQL 里做减法"：并发下的加法与减法会正确累加，
     * 只把最终结果夹在 {@code [floor, max_hp]} 区间内。</p>
     *
     * @param playerId 角色 id
     * @param amount   扣血量（&le;0 时不做任何事）
     * @param floor    血量下限，调用方决定是否允许致死（传 0 表示可以死）
     */
    public void damageHp(long playerId, int amount, int floor) {
        if (amount <= 0) {
            return;
        }
        int minHp = Math.max(0, floor);
        PLAYER_DAO.damageHp(playerId, amount, minHp);
        markDataDirty(playerId);
    }

    public Map<String, Object> healPlayer(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("player.h59a76b2c"));
            return result;
        }
        if (player.getHp() >= player.getMaxHp()) {
            result.put("success", true);
            result.put("message", I18n.t("player.hc5d64260"));
            return result;
        }

        long cost = (player.getRealm() + 1) * 50L;
        long currentStones = ServiceRegistry.getItemService().getSpiritStoneCount(playerId);

        if (currentStones < cost) {
            result.put("success", false);
            result.put("message", I18n.t("player.he7f02870", cost, currentStones));
            result.put("cost", cost);
            result.put("currentStones", currentStones);
            return result;
        }

        if (!ServiceRegistry.getItemService().removeSpiritStones(playerId, cost)) {
            result.put("success", false);
            result.put("message", I18n.t("player.hdb66b7ae"));
            return result;
        }

        int hpBefore = player.getHp();
        PLAYER_DAO.restoreFullVitals(playerId);

        result.put("success", true);
        result.put("hpBefore", hpBefore);
        result.put("hpAfter", player.getMaxHp());
        result.put("cost", cost);
        result.put("message", I18n.t("player.h07c721cc", cost, hpBefore, player.getMaxHp()));
        return result;
    }

    public void addMp(long playerId, int amount) {
        PLAYER_DAO.addMp(playerId, amount);
        markDataDirty(playerId);
    }

    public void addGold(long playerId, long amount) {
        PLAYER_DAO.addGold(playerId, amount);
        markDataDirty(playerId);
    }

    public void addGold(Connection conn, long playerId, long amount) throws SQLException {
        PLAYER_DAO.addGold(conn, playerId, amount);
        markDataDirty(playerId);
    }

    public void addAttack(long playerId, int amount) {
        addStat(playerId, "attack", amount, "增加攻击力");
    }

    public void addAttack(Connection conn, long playerId, int amount) throws SQLException {
        addStat(conn, playerId, "attack", amount);
    }

    public void addDefense(long playerId, int amount) {
        addStat(playerId, "defense", amount, "增加防御力");
    }

    public void addDefense(Connection conn, long playerId, int amount) throws SQLException {
        addStat(conn, playerId, "defense", amount);
    }

    public void addSpeed(long playerId, int amount) {
        addStat(playerId, "speed", amount, "增加速度");
    }

    public void addSpeed(Connection conn, long playerId, int amount) throws SQLException {
        addStat(conn, playerId, "speed", amount);
    }

    public void addSpirit(long playerId, int amount) {
        addStat(playerId, "spirit", amount, "增加灵力");
    }

    public void addSpirit(Connection conn, long playerId, int amount) throws SQLException {
        addStat(conn, playerId, "spirit", amount);
    }

    private void addStat(long playerId, String column, int amount, String operationName) {
        PLAYER_DAO.addStat(playerId, column, amount, operationName);
        markDataDirty(playerId);
    }

    private void addStat(Connection conn, long playerId, String column, int amount) throws SQLException {
        PLAYER_DAO.addStat(conn, playerId, column, amount);
        markDataDirty(playerId);
    }

    public void updateSpiritualRoot(long playerId, SpiritualRoot newRoot) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            throw new RuntimeException("玩家不存在: " + playerId);
        }
        PLAYER_DAO.updateSpiritualRoot(playerId, newRoot);
        markDataDirty(playerId);
    }

    public void updateLastSecretRealmTime(long playerId, long lastSecretRealmTime) {
        PLAYER_DAO.updateLastSecretRealmTime(playerId, lastSecretRealmTime);
    }

    public void updateLastExplorationTime(long playerId, long lastExplorationTime) {
        PLAYER_DAO.updateLastExplorationTime(playerId, lastExplorationTime);
    }

    public void updateBattleStrategy(long playerId, String strategy) {
        PLAYER_DAO.updateBattleStrategy(playerId, strategy);
    }

    public void updateLastOfflineTime(long playerId, long lastOfflineTime) {
        PLAYER_DAO.updateLastOfflineTime(playerId, lastOfflineTime);
    }

    /**
     * 原子"认领"离线收益：仅当 last_offline_time 仍等于期望值时清零并返回 true。
     * 并发下只有第一个请求能认领成功，防止离线收益被重复发放。
     */
    public boolean claimOfflineTime(long playerId, long expectedLastOfflineTime) {
        return PLAYER_DAO.claimOfflineTime(playerId, expectedLastOfflineTime);
    }

    // ==================== 玩法计数（称号获取） ====================

    public void incrementSecretRealmCount(long playerId) {
        updateCounter(playerId, "secret_realm_count");
    }

    public void incrementPvpWins(long playerId) {
        updateCounter(playerId, "pvp_wins");
    }

    public void incrementCraftCount(long playerId) {
        updateCounter(playerId, "craft_count");
    }

    private void updateCounter(long playerId, String column) {
        PLAYER_DAO.incrementCounter(playerId, column);
    }

    public List<PlayerInfo> getAllPlayers(int limit, int offset) {
        return PLAYER_DAO.findAll(limit, offset);
    }

    public List<PlayerInfo> searchPlayersByName(String name, int limit, int offset) {
        return PLAYER_DAO.searchByName(name, limit, offset);
    }

    public int getPlayerCount() {
        return PLAYER_DAO.countPlayers();
    }

    public int getUserCount() {
        return PLAYER_DAO.countUserAccounts();
    }

    public List<PlayerInfo> getTopByRealm(int limit) {
        return PLAYER_DAO.findTopByRealm(limit);
    }

    public List<PlayerInfo> getTopByPower(int limit) {
        return PLAYER_DAO.findTopByPower(limit);
    }

    public List<PlayerInfo> getTopByWealth(int limit) {
        return PLAYER_DAO.findTopByWealth(limit);
    }

    private Title getEquippedTitle(long playerId) {
        String titleKey = PLAYER_DAO.findEquippedTitleKey(playerId);
        if (titleKey == null) {
            return null;
        }
        return TitleRegistry.get(titleKey);
    }

    public int getFinalAttack(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getAttack()), (java.util.function.Function<Title, Integer>) t -> t.getAttackBonus())
                + getEnhanceBonus(player.getId())[0] - getBrokenPenalty(player.getId())[0];
    }

    public int getFinalDefense(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getDefense()), (java.util.function.Function<Title, Integer>) t -> t.getDefenseBonus())
                + getEnhanceBonus(player.getId())[1] - getBrokenPenalty(player.getId())[1];
    }

    public int getFinalMaxHp(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getMaxHp()), (java.util.function.Function<Title, Integer>) t -> t.getHpBonus());
    }

    public int getFinalMaxMp(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getMaxMp()), (java.util.function.Function<Title, Integer>) t -> t.getMpBonus());
    }

    public int getFinalSpeed(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getSpeed()), (java.util.function.Function<Title, Integer>) t -> t.getSpeedBonus())
                + getEnhanceBonus(player.getId())[2] - getBrokenPenalty(player.getId())[2];
    }

    public int getFinalSpirit(Player player) {
        return applyTitleBonus(player, applyConsolidationBonus(player, player.getSpirit()), (java.util.function.Function<Title, Integer>) t -> t.getSpiritBonus())
                + getEnhanceBonus(player.getId())[3] - getBrokenPenalty(player.getId())[3];
    }

    /**
     * 已损坏装备的属性惩罚（攻击/防御/速度/灵力，恒为正数，调用方负责相减）。
     *
     * <p>装备属性在 {@code equipItem} 时就物化进 {@code players} 表了，耐久归零后
     * 无法"就地回退"（那需要写库、还要和并发战斗抢事务）。所以改成读取时扣减：
     * 修理完耐久回到满值，惩罚自然归零，不存在记账不一致。</p>
     */
    private int[] getBrokenPenalty(long playerId) {
        return com.mtxgdn.common.service.ServiceRegistry.getDurabilityService().getBrokenPenalty(playerId);
    }

    /**
     * 凝实（极境）属性加成：每层按 {@code breakthrough.consolidate_stat_bonus} 提升基础属性。
     *
     * <p>刻意做成<b>读取时乘算</b>而不是突破时写进数据库：这样渡劫成功把层数清零后，
     * 极境之力自然消散、属性回到境界本身的水准，不需要记账"当初加了多少"，
     * 也不可能因为重复结算而越叠越高。</p>
     */
    private int applyConsolidationBonus(Player player, int baseValue) {
        if (player == null) {
            return baseValue;
        }
        int layers = player.getConsolidation();
        if (layers <= 0) {
            return baseValue;
        }
        int perLayer = Math.max(0, com.mtxgdn.util.AppConfig.getInt("breakthrough.consolidate_stat_bonus", 8));
        return (int) Math.round(baseValue * (1.0 + layers * perLayer / 100.0));
    }

    /**
     * 读取三件装备的强化加成（攻击/防御/速度/灵力），强化等级真实生效于战斗属性。
     *
     * <p>耐久归零（损坏）的装备不提供强化加成 —— 否则"装备坏了但强化还在"，
     * 修理就没有必要了。耐久表没有记录 = 从未磨损 = 满耐久，按生效处理。</p>
     *
     * <p>查询强化等级失败不阻断战斗（DAO 记日志并返回空列表），与原实现一致。</p>
     */
    private int[] getEnhanceBonus(long playerId) {
        int[] total = {0, 0, 0, 0};
        for (int enhanceLevel : PLAYER_DAO.findActiveEnhanceLevels(playerId)) {
            int[] bonus = com.mtxgdn.game.service.EnhanceService.getEnhanceStatBonus(enhanceLevel);
            for (int i = 0; i < 4; i++) total[i] += bonus[i];
        }
        return total;
    }

    public double getFinalCultivationSpeed(Player player) {
        return applyTitleBonus(player, 1.0, t -> t.getCultivationSpeedBonus());
    }

    public double getFinalExpBonus(Player player) {
        // 称号经验加成 + 已装备功法经验加成（exp_bonus），两者均为经验获取倍率的增量
        double bonus = applyTitleBonus(player, 1.0, t -> t.getExpBonus());
        bonus += ServiceRegistry.getTechniqueService().getEquippedExpBonus(player.getId());
        return bonus;
    }

    public double getFinalDropRateBonus(Player player) {
        return applyTitleBonus(player, 0.0, t -> t.getDropRateBonus());
    }

    private int applyTitleBonus(Player player, int base, java.util.function.Function<Title, Integer> bonusExtractor) {
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += bonusExtractor.apply(title);
        }
        return base;
    }

    private double applyTitleBonus(Player player, double base, java.util.function.Function<Title, Double> bonusExtractor) {
        Title title = getEquippedTitle(player.getId());
        if (title != null) {
            base += bonusExtractor.apply(title);
        }
        return base;
    }

    /**
     * 灵根字符串 → 枚举。保留本方法的签名（同包内与测试都在用），实现只有一份：
     * 委托给 {@link PlayerDAO#parseSpiritualRoot(String)}，与"读 {@code players} 行"共用同一套解析。
     */
    SpiritualRoot safeParseSpiritualRoot(String s) {
        return PlayerDAO.parseSpiritualRoot(s);
    }

    public boolean deletePlayer(long playerId) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            return false;
        }
        // 级联删除（含"最后一名管理者被删 → 解散孤儿宗门"）在同一个事务里，由 DAO 负责
        return PLAYER_DAO.deletePlayerCascade(playerId);
    }

    private static void tickAutoHeal() {
        // 内置模块门控：角色系统停用后暂停自动疗伤
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("player")) return;
        int affected = PLAYER_DAO.healAllPlayersByOnePercent();
        if (affected > 0) {
            LOG.debug("自动回血：" + affected + " 名玩家恢复了生命值");
        }
    }
}
