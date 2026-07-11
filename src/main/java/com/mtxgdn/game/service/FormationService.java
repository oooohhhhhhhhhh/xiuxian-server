package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
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

public class FormationService {

    private static final GameLogger LOG = GameLogger.getLogger(FormationService.class);

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final CaveService caveService = new CaveService();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(FormationService::tickFormations, 60, 60, TimeUnit.SECONDS);
        LOG.info("阵法定时检查任务已启动");
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
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        if (caveService.getCaveByPlayerId(playerId) == null) {
            result.put("success", false);
            result.put("message", "你还没有洞府，无法布置阵法");
            return result;
        }

        if (getActiveFormation(playerId) != null) {
            result.put("success", false);
            result.put("message", "你已经布置了一个阵法，需先拆除");
            return result;
        }

        long cost = Formation.getPlaceCost(formationKey, level);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", "灵石不足，布置阵法需要 " + cost + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, cost)) {
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
        result.put("message", "成功布置【" + formation.getName() + "】！消耗 " + cost + " 灵石\n" +
                "持续时间: " + formation.getDurationMinutes() + " 分钟\n" +
                "效果: 灵气汇聚 +" + formation.getSpiritEnergyBoost() + "%，修炼加成 +" + formation.getCultivationBonus() + "%");
        result.put("formation", formation);
        return result;
    }

    public Map<String, Object> upgradeFormation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            result.put("success", false);
            result.put("message", "你还没有布置阵法");
            return result;
        }

        if (formation.getLevel() >= Formation.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", "阵法已达到最高等级 " + Formation.MAX_LEVEL + " 级");
            return result;
        }

        int newLevel = formation.getLevel() + 1;
        long cost = Formation.getPlaceCost(formation.getFormationKey(), newLevel);
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", "灵石不足，升级需要 " + cost + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, cost)) {
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
        result.put("message", formation.getName() + "晋升为 " + newLevel + " 级！消耗 " + cost + " 灵石");
        result.put("newLevel", newLevel);
        return result;
    }

    public Map<String, Object> removeFormation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Formation formation = getActiveFormation(playerId);
        if (formation == null) {
            result.put("success", false);
            result.put("message", "你还没有布置阵法");
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
        result.put("message", "已拆除【" + formation.getName() + "】");
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