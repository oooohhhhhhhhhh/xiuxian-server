package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.config.NewbieRewardConfig;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

public class PlayerService {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerService.class);

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
        String sql = "SELECT * FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerInfo info = mapPlayerInfo(rs);
                    touchActive(info.getId(), info.getLastActiveTime());
                    return info;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    public Player getPlayerById(long playerId) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Player player = mapPlayer(rs);
                    touchActive(player.getId(), player.getLastActiveTime());
                    return player;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
    }

    /**
     * 按角色 ID 取 {@link PlayerInfo}（而非 {@link Player}）。
     *
     * <p>{@link #getPlayerById(long)} 返回的是另一个映射（Player），
     * 而语言解析只需要 PlayerInfo 上那一个字段，为它单独查一遍库不划算。
     * 与 {@link #getPlayerByUserId(long)} 同样会在读取时顺手刷新活跃时间。</p>
     */
    public PlayerInfo getPlayerInfoById(long playerId) {
        String sql = "SELECT * FROM players WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerInfo info = mapPlayerInfo(rs);
                    touchActive(info.getId(), info.getLastActiveTime());
                    return info;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
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
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET lang = ? WHERE id = ?")) {
            ps.setString(1, language);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("更新界面语言失败", e);
        }
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
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE players SET last_active_time = ? WHERE id = ?")) {
            ps.setLong(1, now);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // 活跃时间只用于弃坑判定，写失败不应阻断任何玩法
        }
    }

    public Player getPlayerRaw(long userId) {
        String sql = "SELECT * FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlayer(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家信息失败", e);
        }
        return null;
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
        String sql = """
            INSERT INTO players (user_id, name, spiritual_root, level, experience, realm, sub_realm,
                hp, max_hp, mp, max_mp, attack, defense, speed, spirit,
                gold, cultivation_progress, is_cultivating, cultivation_start_time, last_secret_realm_time, last_exploration_time, tutorial_step, tutorial_tips, current_location_id)
            VALUES (?, ?, ?, 1, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 1, 0, 1)
            """;
        long playerId = -1;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, userId);
                ps.setString(2, safeName);
                ps.setString(3, root.name());
                ps.setInt(4, root.applyHpBonus(100));
                ps.setInt(5, root.applyHpBonus(100));
                ps.setInt(6, root.applyMpBonus(50));
                ps.setInt(7, root.applyMpBonus(50));
                ps.setInt(8, root.applyAttackBonus(10));
                ps.setInt(9, root.applyDefenseBonus(5));
                ps.setInt(10, root.applySpeedBonus(5));
                ps.setInt(11, root.applySpiritBonus(5));
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        playerId = rs.getLong(1);
                    }
                }
            }

            NewbieRewardConfig rewardConfig = GameConfigLoader.getNewbieRewardConfig();
            if (rewardConfig.isEnabled()) {
                ItemService itemService = new ItemService();

                if (rewardConfig.getGoldReward() > 0) {
                    addGold(conn, playerId, rewardConfig.getGoldReward());
                }

                if (rewardConfig.getSpiritStoneReward() > 0) {
                    itemService.addSpiritStones(conn, playerId, rewardConfig.getSpiritStoneReward(), rewardConfig.getSpiritStoneGrade());
                }

                if (rewardConfig.getItems() != null && !rewardConfig.getItems().isEmpty()) {
                    for (NewbieRewardConfig.RewardItem item : rewardConfig.getItems()) {
                        if (item.getItemKey() != null && !item.getItemKey().isBlank() && item.getQuantity() > 0) {
                            itemService.addItem(conn, playerId, item.getItemKey(), item.getQuantity());
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建玩家失败: " + e.getMessage(), e);
        }
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
        String sql = """
            UPDATE players SET level = ?, experience = ?, realm = ?, sub_realm = ?,
                hp = ?, max_hp = ?, mp = ?, max_mp = ?, attack = ?, defense = ?,
                speed = ?, spirit = ?, gold = ?,
                cultivation_progress = ?, is_cultivating = ?, cultivation_start_time = ?,
                last_secret_realm_time = ?, last_exploration_time = ?, last_offline_time = ?,
                last_breakthrough_time = ?, last_active_time = ?,
                breakthrough_pity = ?, consolidation = ?
            WHERE id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getLevel());
            ps.setLong(2, player.getExperience());
            ps.setInt(3, player.getRealm());
            ps.setInt(4, player.getSubRealm());
            ps.setInt(5, player.getHp());
            ps.setInt(6, player.getMaxHp());
            ps.setInt(7, player.getMp());
            ps.setInt(8, player.getMaxMp());
            ps.setInt(9, player.getAttack());
            ps.setInt(10, player.getDefense());
            ps.setInt(11, player.getSpeed());
            ps.setInt(12, player.getSpirit());
            ps.setLong(13, player.getGold());
            ps.setInt(14, player.getCultivationProgress());
            ps.setBoolean(15, player.isCultivating());
            ps.setLong(16, player.getCultivationStartTime());
            ps.setLong(17, player.getLastSecretRealmTime());
            ps.setLong(18, player.getLastExplorationTime());
            ps.setLong(19, player.getLastOfflineTime());
            ps.setLong(20, player.getLastBreakthroughTime());
            ps.setLong(21, System.currentTimeMillis()); // last_active_time：落库本身就是一次活跃信号
            ps.setInt(22, player.getBreakthroughPity());
            ps.setInt(23, player.getConsolidation());
            ps.setLong(24, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新玩家失败", e);
        }
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
        String sql = "UPDATE players SET name = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, safeName);
            ps.setLong(2, playerId);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                markDataDirty(playerId);
            }
            return affected > 0;
        } catch (SQLException e) {
            throw new RuntimeException("修改玩家名称失败", e);
        }
    }

    public void addExperience(long playerId, long exp) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addExperience(conn, playerId, exp);
        } catch (SQLException e) {
            throw new RuntimeException("增加经验失败", e);
        }
    }

    public void addExperience(Connection conn, long playerId, long exp) throws SQLException {
        Player player = getPlayerById(playerId);
        if (player != null) {
            exp = (long) (exp * getFinalExpBonus(player));
            // 经验翻倍丹等 Buff 加成
            double buffMult = ServiceRegistry.getBuffService().getExpMultiplier(playerId);
            if (buffMult > 1.0) {
                exp = (long) (exp * buffMult);
            }
        }
        String sql = "UPDATE players SET experience = experience + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, exp);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
        markDataDirty(playerId);
    }

    public void updateCultivationProgress(long playerId, int progress) {
        String sql = "UPDATE players SET cultivation_progress = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, progress);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼进度失败", e);
        }
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
        String sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = ?, last_offline_time = 0 WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true);
            ps.setLong(2, startTime);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼状态失败", e);
        }
        markDataDirty(playerId);
    }

    /**
     * 条件停止闭关：仅当玩家确实处于闭关状态时清零并返回 true。
     * 并发调用下只有第一个请求成功，防止闭关结算被重复发放经验。
     */
    public boolean stopCultivation(long playerId) {
        String sql = "UPDATE players SET is_cultivating = ?, cultivation_start_time = 0 WHERE id = ? AND is_cultivating = 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            boolean claimed = ps.executeUpdate() > 0;
            if (claimed) {
                markDataDirty(playerId);
            }
            return claimed;
        } catch (SQLException e) {
            throw new RuntimeException("更新修炼状态失败", e);
        }
    }

    public void setTutorialStep(long playerId, int step) {
        String sql = "UPDATE players SET tutorial_step = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, step);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新引导步骤失败", e);
        }
    }

    public void setTutorialTips(long playerId, int tips) {
        String sql = "UPDATE players SET tutorial_tips = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tips);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新引导提示标记失败", e);
        }
    }

    public boolean existsByUserId(long userId) {
        String sql = "SELECT COUNT(*) FROM players WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查玩家存在失败", e);
        }
        return false;
    }

    public void addHp(long playerId, int amount) {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET hp = MIN(hp + ?, max_hp) WHERE id = ?"
                : "UPDATE players SET hp = LEAST(hp + ?, max_hp) WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("恢复生命值失败", e);
        }
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
        String sql = "UPDATE players SET hp = max_hp, mp = max_mp WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("疗伤失败", e);
        }

        result.put("success", true);
        result.put("hpBefore", hpBefore);
        result.put("hpAfter", player.getMaxHp());
        result.put("cost", cost);
        result.put("message", I18n.t("player.h07c721cc", cost, hpBefore, player.getMaxHp()));
        return result;
    }

    public void addMp(long playerId, int amount) {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET mp = MIN(mp + ?, max_mp) WHERE id = ?"
                : "UPDATE players SET mp = LEAST(mp + ?, max_mp) WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("恢复法力失败", e);
        }
        markDataDirty(playerId);
    }

    public void addGold(long playerId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addGold(conn, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("增加金币失败", e);
        }
    }

    public void addGold(Connection conn, long playerId, long amount) throws SQLException {
        String sql = "UPDATE players SET gold = gold + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
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
        try (Connection conn = DatabaseManager.getConnection()) {
            addStat(conn, playerId, column, amount);
        } catch (SQLException e) {
            throw new RuntimeException(operationName + "失败", e);
        }
    }

    private void addStat(Connection conn, long playerId, String column, int amount) throws SQLException {
        String sql = "UPDATE players SET " + column + " = " + column + " + ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
        markDataDirty(playerId);
    }

    public void updateSpiritualRoot(long playerId, SpiritualRoot newRoot) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            throw new RuntimeException("玩家不存在: " + playerId);
        }

        int baseHp = 100, baseMp = 50, baseAtk = 10, baseDef = 5, baseSpd = 5, baseSpi = 5;
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = MIN(hp, ?), max_mp = ?, mp = MIN(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?"
                : "UPDATE players SET spiritual_root = ?, max_hp = ?, hp = LEAST(hp, ?), max_mp = ?, mp = LEAST(mp, ?), attack = ?, defense = ?, speed = ?, spirit = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newRoot.name());
            ps.setInt(2, newRoot.applyHpBonus(baseHp));
            ps.setInt(3, newRoot.applyHpBonus(baseHp));
            ps.setInt(4, newRoot.applyMpBonus(baseMp));
            ps.setInt(5, newRoot.applyMpBonus(baseMp));
            ps.setInt(6, newRoot.applyAttackBonus(baseAtk));
            ps.setInt(7, newRoot.applyDefenseBonus(baseDef));
            ps.setInt(8, newRoot.applySpeedBonus(baseSpd));
            ps.setInt(9, newRoot.applySpiritBonus(baseSpi));
            ps.setLong(10, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新灵根失败", e);
        }
        markDataDirty(playerId);
    }

    public void updateLastSecretRealmTime(long playerId, long lastSecretRealmTime) {
        String sql = "UPDATE players SET last_secret_realm_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastSecretRealmTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新秘境时间失败", e);
        }
    }

    public void updateLastExplorationTime(long playerId, long lastExplorationTime) {
        String sql = "UPDATE players SET last_exploration_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastExplorationTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新游历时间失败", e);
        }
    }

    public void updateBattleStrategy(long playerId, String strategy) {
        String sql = "UPDATE players SET battle_strategy = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新战斗策略失败", e);
        }
    }

    public void updateLastOfflineTime(long playerId, long lastOfflineTime) {
        String sql = "UPDATE players SET last_offline_time = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastOfflineTime);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新离线时间失败", e);
        }
    }

    /**
     * 原子"认领"离线收益：仅当 last_offline_time 仍等于期望值时清零并返回 true。
     * 并发下只有第一个请求能认领成功，防止离线收益被重复发放。
     */
    public boolean claimOfflineTime(long playerId, long expectedLastOfflineTime) {
        String sql = "UPDATE players SET last_offline_time = 0 WHERE id = ? AND last_offline_time = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, expectedLastOfflineTime);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("认领离线时间失败", e);
        }
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
        String sql = "UPDATE players SET " + column + " = " + column + " + 1 WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新玩法计数失败: " + column, e);
        }
    }

    public List<PlayerInfo> getAllPlayers(int limit, int offset) {
        String sql = "SELECT * FROM players ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家列表失败", e);
        }
        return players;
    }

    public List<PlayerInfo> searchPlayersByName(String name, int limit, int offset) {
        String sql = "SELECT * FROM players WHERE name LIKE ? ORDER BY level DESC, experience DESC LIMIT ? OFFSET ?";
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + name + "%");
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("搜索玩家失败", e);
        }
        return players;
    }

    public int getPlayerCount() {
        String sql = "SELECT COUNT(*) FROM players";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家数量失败", e);
        }
        return 0;
    }

    public int getUserCount() {
        String sql = "SELECT COUNT(*) FROM users";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取用户数量失败", e);
        }
        return 0;
    }

    public List<PlayerInfo> getTopByRealm(int limit) {
        String sql = "SELECT * FROM players ORDER BY realm DESC, experience DESC LIMIT ?";
        return queryPlayerInfoList(sql, limit);
    }

    public List<PlayerInfo> getTopByPower(int limit) {
        String sql = "SELECT * FROM players ORDER BY (attack + defense + speed + max_hp) DESC LIMIT ?";
        return queryPlayerInfoList(sql, limit);
    }

    public List<PlayerInfo> getTopByWealth(int limit) {
        String sql = """
            SELECT p.*, COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0) AS spirit_stones
            FROM players p
            LEFT JOIN players_items pi ON p.id = pi.player_id
            GROUP BY p.id
            ORDER BY (p.gold + COALESCE(SUM(CASE
                WHEN pi.item_key = 'mtxgdn:spirit_stone_low' THEN pi.quantity
                WHEN pi.item_key = 'mtxgdn:spirit_stone_mid' THEN pi.quantity * 1000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_high' THEN pi.quantity * 1000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone_supreme' THEN pi.quantity * 1000000000
                WHEN pi.item_key = 'mtxgdn:spirit_stone' THEN pi.quantity
                ELSE 0 END), 0)) DESC
            LIMIT ?
            """;
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取财富榜失败", e);
        }
        return players;
    }

    private List<PlayerInfo> queryPlayerInfoList(String sql, int limit) {
        List<PlayerInfo> players = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayerInfo(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家列表失败", e);
        }
        return players;
    }

    private PlayerInfo mapPlayerInfo(ResultSet rs) throws SQLException {
        PlayerInfo pi = new PlayerInfo();
        pi.setId(rs.getLong("id"));
        pi.setUserId(rs.getLong("user_id"));
        pi.setName(rs.getString("name"));
        pi.setSpiritualRoot(safeParseSpiritualRoot(rs.getString("spiritual_root")));
        pi.setLevel(rs.getInt("level"));
        pi.setExperience(rs.getLong("experience"));
        pi.setRealm(rs.getInt("realm"));
        pi.setSubRealm(rs.getInt("sub_realm"));
        pi.setHp(rs.getInt("hp"));
        pi.setMaxHp(rs.getInt("max_hp"));
        pi.setMp(rs.getInt("mp"));
        pi.setMaxMp(rs.getInt("max_mp"));
        pi.setAttack(rs.getInt("attack"));
        pi.setDefense(rs.getInt("defense"));
        pi.setSpeed(rs.getInt("speed"));
        pi.setSpirit(rs.getInt("spirit"));
        pi.setGold(rs.getLong("gold"));
        pi.setCultivationProgress(rs.getInt("cultivation_progress"));
        pi.setCultivating(rs.getBoolean("is_cultivating"));
        pi.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        pi.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        pi.setLastExplorationTime(rs.getLong("last_exploration_time"));
        pi.setTutorialStep(rs.getInt("tutorial_step"));
        pi.setTutorialTips(rs.getInt("tutorial_tips"));
        pi.setLastOfflineTime(rs.getLong("last_offline_time"));
        try { pi.setLastActiveTime(rs.getLong("last_active_time")); } catch (Exception ignored) {}
        try { pi.setBreakthroughPity(rs.getInt("breakthrough_pity")); } catch (Exception ignored) {}
        try { pi.setConsolidation(rs.getInt("consolidation")); } catch (Exception ignored) {}
        // 老库迁移后该列是空串：getString 返回 "" 而不是 null，与"未设置"等价。
        try { pi.setLanguage(rs.getString("lang")); } catch (Exception ignored) {}
        pi.setCreatedAt(rs.getString("created_at"));
        pi.setUpdatedAt(rs.getString("updated_at"));

        RealmConfig realmConfig = GameConfigLoader.getRealmConfig(pi.getRealm(), pi.getSubRealm());
        if (realmConfig != null) {
            pi.setRealmName(realmConfig.getFullName());
        }

        return pi;
    }

    private Player mapPlayer(ResultSet rs) throws SQLException {
        Player p = new Player();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setName(rs.getString("name"));
        p.setSpiritualRoot(safeParseSpiritualRoot(rs.getString("spiritual_root")));
        p.setLevel(rs.getInt("level"));
        p.setExperience(rs.getLong("experience"));
        p.setRealm(rs.getInt("realm"));
        p.setSubRealm(rs.getInt("sub_realm"));
        p.setHp(rs.getInt("hp"));
        p.setMaxHp(rs.getInt("max_hp"));
        p.setMp(rs.getInt("mp"));
        p.setMaxMp(rs.getInt("max_mp"));
        p.setAttack(rs.getInt("attack"));
        p.setDefense(rs.getInt("defense"));
        p.setSpeed(rs.getInt("speed"));
        p.setSpirit(rs.getInt("spirit"));
        p.setGold(rs.getLong("gold"));
        p.setCultivationProgress(rs.getInt("cultivation_progress"));
        p.setCultivating(rs.getBoolean("is_cultivating"));
        p.setCultivationStartTime(rs.getLong("cultivation_start_time"));
        p.setLastSecretRealmTime(rs.getLong("last_secret_realm_time"));
        p.setLastExplorationTime(rs.getLong("last_exploration_time"));
        p.setTutorialStep(rs.getInt("tutorial_step"));
        p.setTutorialTips(rs.getInt("tutorial_tips"));
        p.setLastOfflineTime(rs.getLong("last_offline_time"));
        try { p.setLastActiveTime(rs.getLong("last_active_time")); } catch (Exception ignored) {}
        try { p.setBattleStrategy(rs.getString("battle_strategy")); } catch (Exception ignored) {}
        try { p.setCurrentLocationId(rs.getLong("current_location_id")); } catch (Exception ignored) {}
        try { p.setLastTravelTime(rs.getLong("last_travel_time")); } catch (Exception ignored) {}
        try { p.setLastBreakthroughTime(rs.getLong("last_breakthrough_time")); } catch (Exception ignored) {}
        try { p.setBreakthroughPity(rs.getInt("breakthrough_pity")); } catch (Exception ignored) {}
        try { p.setConsolidation(rs.getInt("consolidation")); } catch (Exception ignored) {}
        try { p.setSecretRealmCount(rs.getInt("secret_realm_count")); } catch (Exception ignored) {}
        try { p.setPvpWins(rs.getInt("pvp_wins")); } catch (Exception ignored) {}
        try { p.setCraftCount(rs.getInt("craft_count")); } catch (Exception ignored) {}
        p.setCreatedAt(rs.getString("created_at"));
        p.setUpdatedAt(rs.getString("updated_at"));
        return p;
    }

    private Title getEquippedTitle(long playerId) {
        String sql = "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setBoolean(2, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return TitleRegistry.get(rs.getString("title_key"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
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
     */
    private int[] getEnhanceBonus(long playerId) {
        int[] total = {0, 0, 0, 0};
        String sql = "SELECT e.enhance_level FROM players_equipment e "
                + "LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ? AND e.enhance_level > 0 AND (d.durability IS NULL OR d.durability > 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int[] bonus = com.mtxgdn.game.service.EnhanceService.getEnhanceStatBonus(rs.getInt("enhance_level"));
                    for (int i = 0; i < 4; i++) total[i] += bonus[i];
                }
            }
        } catch (SQLException e) {
            // 查询强化加成失败不阻断战斗，记录日志
            java.util.logging.Logger.getLogger(PlayerService.class.getName())
                    .warning("查询强化加成失败: " + e.getMessage());
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

    SpiritualRoot safeParseSpiritualRoot(String s) {
        if (s == null || s.isBlank()) return SpiritualRoot.CHAOS_MIXED;
        s = s.trim().toUpperCase();
        return switch (s) {
            case "METAL" -> SpiritualRoot.TAIYI_GOLDEN;
            case "WOOD" -> SpiritualRoot.QINGDI_WOOD;
            case "WATER" -> SpiritualRoot.XUANMING_WATER;
            case "FIRE" -> SpiritualRoot.LIHUO_FIRE;
            case "EARTH" -> SpiritualRoot.HOUTU_EARTH;
            case "WIND" -> SpiritualRoot.XUNFENG_WIND;
            case "THUNDER" -> SpiritualRoot.JINGLEI_THUNDER;
            case "ICE" -> SpiritualRoot.XUANBING_ICE;
            default -> {
                try { yield SpiritualRoot.valueOf(s); }
                catch (Exception e) { yield SpiritualRoot.CHAOS_MIXED; }
            }
        };
    }

    public boolean deletePlayer(long playerId) {
        Player player = getPlayerById(playerId);
        if (player == null) {
            return false;
        }

        String[] tables = {
            "players_items", "players_equipment", "players_skills", "players_techniques",
            "player_daily", "player_economy", "player_bank", "player_titles", "player_energy",
            "sect_members", "sect_applications", "auction_listings", "auction_bids",
            "trade_listings", "redeemed_codes", "chat_messages", "player_action_logs"
        };

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // M20 修复：若该玩家是某宗门的最后一名有管理权限成员，先解散宗门并清空仓库。
                // 必须在删除 sect_members 之前执行，否则无法查出其宗门身份。
                cleanupOrphanedSect(conn, playerId);

                for (String table : tables) {
                    String sql = "DELETE FROM " + table + " WHERE player_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setLong(1, playerId);
                        ps.executeUpdate();
                    }
                }

                // S11 修复：friends 表列名是 friend_player_id，不是 friend_id
                String deleteFriendSql = "DELETE FROM friends WHERE player_id = ? OR friend_player_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deleteFriendSql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, playerId);
                    ps.executeUpdate();
                }

                String deletePlayerSql = "DELETE FROM players WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(deletePlayerSql)) {
                    ps.setLong(1, playerId);
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        conn.rollback();
                        return false;
                    }
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw new RuntimeException("删除玩家失败", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("删除玩家失败", e);
        }
    }

    /**
     * M20 修复：玩家被删除时，若该玩家是某宗门的最后一名有管理权限成员（宗主/副宗主/长老），
     * 则解散该宗门并清空宗门仓库、申请、战争记录。必须在同一事务内、且删除 sect_members 之前调用。
     */
    private void cleanupOrphanedSect(Connection conn, long playerId) throws SQLException {
        long sectId = -1;
        String role = null;
        String findSql = "SELECT sect_id, role FROM sect_members WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sectId = rs.getLong("sect_id");
                    role = rs.getString("role");
                }
            }
        }
        if (sectId < 0) {
            return;
        }
        boolean isManager = com.mtxgdn.game.entity.SectMember.ROLE_LEADER.equals(role)
                || com.mtxgdn.game.entity.SectMember.ROLE_VICE_LEADER.equals(role)
                || com.mtxgdn.game.entity.SectMember.ROLE_ELDER.equals(role);
        if (!isManager) {
            return;
        }

        // 若宗门内还有其他管理权限成员，则无需解散
        String countSql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ? AND player_id != ? " +
                "AND role IN ('LEADER', 'VICE_LEADER', 'ELDER')";
        try (PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
        }

        // 解散宗门：清空仓库/申请/成员/战争记录，再删除宗门本体
        String[] sectTables = {"sect_warehouse", "sect_applications", "sect_members"};
        for (String table : sectTables) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE sect_id = ?")) {
                ps.setLong(1, sectId);
                ps.executeUpdate();
            }
        }
        // sect_wars 没有 sect_id 列，按攻/守两方宗门 id 清理
        String deleteWarsSql = "DELETE FROM sect_wars WHERE attacker_sect_id = ? OR defender_sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteWarsSql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sects WHERE id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
        LOG.info("删除玩家 " + playerId + " 时解散了无管理成员的宗门 sectId=" + sectId);
    }

    private static void tickAutoHeal() {
        // 内置模块门控：角色系统停用后暂停自动疗伤
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("player")) return;
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "UPDATE players SET hp = MIN(hp + CAST(CEILING(max_hp * 0.01) AS INTEGER), max_hp) WHERE hp < max_hp";
        } else {
            sql = "UPDATE players SET hp = LEAST(hp + CEIL(max_hp * 0.01), max_hp) WHERE hp < max_hp";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int affected = ps.executeUpdate();
            if (affected > 0) {
                LOG.debug("自动回血：" + affected + " 名玩家恢复了生命值");
            }
        } catch (SQLException e) {
            LOG.error("自动回血失败", e);
        }
    }
}
