package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Formation;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.mtxgdn.i18n.I18n;

public class FormationService {

    private static final GameLogger LOG = GameLogger.getLogger(FormationService.class);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(FormationService::tickFormations, 60, 60, TimeUnit.SECONDS);
        LOG.info("阵法定时检查任务已启动");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("阵法定时检查任务已停止");
    }

    public Formation getActiveFormation(long playerId) {
        String sql = "SELECT * FROM formations WHERE player_id = ? AND active = 1 ORDER BY expires_at DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Formation f = mapFormation(rs);
                    if (f.isExpired()) {
                        deactivateFormation(f.getId());
                        return null;
                    }
                    return f;
                }
            }
        } catch (SQLException e) { throw new RuntimeException("查询阵法失败", e); }
        return null;
    }

    public Map<String, Object> placeFormation(long playerId, String formationKey, int level) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.character_not_found"));
            return result;
        }

        if (com.mtxgdn.common.service.ServiceRegistry.getCaveService().getCaveByPlayerId(playerId) == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_do"));
            return result;
        }

        if (getActiveFormation(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_have_already_set_up"));
            return result;
        }

        long cost = Formation.getPlaceCost(formationKey, level);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", I18n.t("formation.not_enough_spirit_stones_2", cost, spiritStones));
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            long now = System.currentTimeMillis();
            long durationMs = (long) Formation.getBaseDuration(formationKey) * 60 * 1000;
            String sql = "INSERT INTO formations (player_id, formation_key, name, level, " +
                    "spirit_energy_boost, cultivation_bonus, defense_bonus, heart_demon_resist, " +
                    "duration_minutes, placed_at, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, playerId);
                ps.setString(2, formationKey);
                ps.setString(3, Formation.getNameForKey(formationKey));
                ps.setInt(4, level);
                ps.setInt(5, Formation.getSpiritEnergyBoostForLevel(formationKey, level));
                ps.setInt(6, Formation.getCultivationBonusForLevel(formationKey, level));
                ps.setInt(7, Formation.getDefenseBonusForLevel(formationKey, level));
                ps.setInt(8, Formation.getHeartDemonResistForLevel(formationKey, level));
                ps.setInt(9, Formation.getBaseDuration(formationKey));
                ps.setLong(10, now);
                ps.setLong(11, now + durationMs);
                ps.executeUpdate();
            }
            return null;
        });

        Formation formation = getActiveFormation(playerId);
        result.put("success", true);
        result.put("message", I18n.t("formation.successfully_set_up", formation.getName(), cost, formation.getDurationMinutes(), formation.getSpiritEnergyBoost(), formation.getCultivationBonus()));
        result.put("formation", formation);
        return result;
    }

    public Map<String, Object> upgradeFormation(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_have_not_set_up"));
            return result;
        }

        if (formation.getLevel() >= Formation.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", I18n.t("formation.the_formation_is_already", Formation.MAX_LEVEL));
            return result;
        }

        int newLevel = formation.getLevel() + 1;
        long cost = Formation.getPlaceCost(formation.getFormationKey(), newLevel);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", I18n.t("formation.not_enough_spirit_stones", cost, spiritStones));
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            String sql = "UPDATE formations SET level = ?, spirit_energy_boost = ?, " +
                    "cultivation_bonus = ?, defense_bonus = ?, heart_demon_resist = ?, " +
                    "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, newLevel);
                ps.setInt(2, Formation.getSpiritEnergyBoostForLevel(formation.getFormationKey(), newLevel));
                ps.setInt(3, Formation.getCultivationBonusForLevel(formation.getFormationKey(), newLevel));
                ps.setInt(4, Formation.getDefenseBonusForLevel(formation.getFormationKey(), newLevel));
                ps.setInt(5, Formation.getHeartDemonResistForLevel(formation.getFormationKey(), newLevel));
                ps.setLong(6, formation.getId());
                ps.executeUpdate();
            }
            return null;
        });

        result.put("success", true);
        result.put("message", I18n.t("formation.upgraded",
                formation.getName(), newLevel, cost));
        result.put("newLevel", newLevel);
        return result;
    }

    public Map<String, Object> removeFormation(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_have_not_set_up"));
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            String sql = "UPDATE formations SET active = 0 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, formation.getId());
                ps.executeUpdate();
            }
            return null;
        });

        result.put("success", true);
        result.put("message", I18n.t("formation.dismantled", formation.getName()));
        return result;
    }

    private void deactivateFormation(long formationId) {
        String sql = "UPDATE formations SET active = 0 WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, formationId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("阵法失效失败", e); }
    }

    public static void tickFormations() {
        // 内置模块门控：阵法模块停用后暂停阵法到期结算
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("formation")) return;
        String sql = "UPDATE formations SET active = 0 WHERE active = 1 AND expires_at < ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("阵法定时检查失败", e); }
    }

    public int getTotalSpiritEnergyBoost(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getSpiritEnergyBoost() : 0;
    }

    public int getTotalCultivationBonus(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getCultivationBonus() : 0;
    }

    public int getTotalHeartDemonResist(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getHeartDemonResist() : 0;
    }

    /**
     * 已激活阵法的防御加成（% 减伤）：护山大阵每级 +10%、迷踪阵每级 +5%。
     * 供 PVE / PVP / 团队 Boss 结算按百分比削减受到的伤害，激活期间全程生效。
     */
    public int getTotalDefenseBonus(long playerId) {
        Formation f = getActiveFormation(playerId);
        return f != null ? f.getDefenseBonus() : 0;
    }

    /** 阵图物品 fullKey → 阵法 key；非阵图返回 null。 */
    public static String treasureKeyToFormationKey(String fullKey) {
        return switch (fullKey) {
            case "mtxgdn:spirit_gathering_formation" -> "spirit_gathering";
            case "mtxgdn:mountain_protection_formation" -> "mountain_protection";
            case "mtxgdn:purification_formation" -> "purification";
            default -> null;
        };
    }

    /**
     * 使用阵图物品布置对应 1 级阵法：消耗 1 张阵图（不另扣灵石）。
     * 需要洞府且当前未布置阵法；成功后按该阵法 1 级效果激活。
     */
    public Map<String, Object> placeFormationFromTreasure(long playerId, String treasureFullKey) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();
        String formationKey = treasureKeyToFormationKey(treasureFullKey);
        if (formationKey == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.this_is_not_a_formation"));
            return result;
        }
        if (com.mtxgdn.common.service.ServiceRegistry.getCaveService().getCaveByPlayerId(playerId) == null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_do"));
            return result;
        }
        if (getActiveFormation(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("formation.you_have_already_set_up"));
            return result;
        }
        final int level = 1;
        try {
            DatabaseManager.runTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?")) {
                    ps.setLong(1, playerId);
                    ps.setString(2, treasureFullKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getLong(1) < 1) {
                            throw new SQLException("背包中没有该阵图");
                        }
                    }
                }
                long now = System.currentTimeMillis();
                long durationMs = (long) Formation.getBaseDuration(formationKey) * 60 * 1000;
                String sql = "INSERT INTO formations (player_id, formation_key, name, level, " +
                        "spirit_energy_boost, cultivation_bonus, defense_bonus, heart_demon_resist, " +
                        "duration_minutes, placed_at, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, formationKey);
                    ps.setString(3, Formation.getNameForKey(formationKey));
                    ps.setInt(4, level);
                    ps.setInt(5, Formation.getSpiritEnergyBoostForLevel(formationKey, level));
                    ps.setInt(6, Formation.getCultivationBonusForLevel(formationKey, level));
                    ps.setInt(7, Formation.getDefenseBonusForLevel(formationKey, level));
                    ps.setInt(8, Formation.getHeartDemonResistForLevel(formationKey, level));
                    ps.setInt(9, Formation.getBaseDuration(formationKey));
                    ps.setLong(10, now);
                    ps.setLong(11, now + durationMs);
                    ps.executeUpdate();
                }
                if (!itemService.removeItem(conn, playerId, treasureFullKey, 1)) {
                    throw new SQLException("阵图扣除失败: " + treasureFullKey);
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("formation.failed_to_use_the_formation", e.getMessage()));
            return result;
        }
        Formation formation = getActiveFormation(playerId);
        result.put("success", true);
        // 防御/心魔两段都是"有才显示"的尾巴，整体各占一个占位符（{4} / {5}），
        // 没有该属性时传空串。拆成「，防御加成 +{5}%」那种半句模板，
        // 会让英文翻译很难摆对逗号和换行位置。
        result.put("message", I18n.t("formation.use.success",
                formation.getName(),
                formation.getDurationMinutes(),
                formation.getSpiritEnergyBoost(),
                formation.getCultivationBonus(),
                formation.getDefenseBonus() > 0
                        ? "，防御加成 +" + formation.getDefenseBonus() + "%"
                        : "",
                formation.getHeartDemonResist() > 0
                        ? "，心魔抵抗 +" + formation.getHeartDemonResist()
                        : ""));
        result.put("formation", formation);
        return result;
    }

    private Formation mapFormation(ResultSet rs) throws SQLException {
        Formation f = new Formation();
        f.setId(rs.getLong("id"));
        f.setPlayerId(rs.getLong("player_id"));
        f.setFormationKey(rs.getString("formation_key"));
        f.setName(rs.getString("name"));
        f.setLevel(rs.getInt("level"));
        f.setSpiritEnergyBoost(rs.getInt("spirit_energy_boost"));
        f.setCultivationBonus(rs.getInt("cultivation_bonus"));
        f.setDefenseBonus(rs.getInt("defense_bonus"));
        f.setHeartDemonResist(rs.getInt("heart_demon_resist"));
        f.setDurationMinutes(rs.getInt("duration_minutes"));
        f.setPlacedAt(rs.getLong("placed_at"));
        f.setExpiresAt(rs.getLong("expires_at"));
        f.setActive(rs.getBoolean("active"));
        return f;
    }
}