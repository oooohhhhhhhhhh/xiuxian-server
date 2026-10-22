package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Cave;
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

public class CaveService {

    private static final GameLogger LOG = GameLogger.getLogger(CaveService.class);

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(CaveService::tickSpiritEnergy, 10, 10, TimeUnit.SECONDS);
        LOG.info("洞府灵气自动增长任务已启动");
    }

    public static void shutdown() {
        scheduler.shutdown();
        LOG.info("洞府灵气自动增长任务已停止");
    }

    public Cave getCaveByPlayerId(long playerId) {
        String sql = "SELECT * FROM caves WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapCave(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询洞府失败", e); }
        return null;
    }

    public Map<String, Object> createCave(long playerId, String caveName) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h59a76b2c"));
            return result;
        }

        if (player.getRealm() < Cave.MIN_REALM_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", I18n.t("cave.h90f650ef", realmName));
            return result;
        }

        if (getCaveByPlayerId(playerId) != null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.hcf358280"));
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Cave.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h76e9d310", Cave.CREATE_COST_SPIRIT_STONES, spiritStones));
            return result;
        }

        String caveNameFinal = caveName != null && !caveName.trim().isEmpty() ? caveName.trim() : "山间洞府";
        if (caveNameFinal.length() > 10) {
            caveNameFinal = caveNameFinal.substring(0, 10);
        }
        final String name = caveNameFinal;

        // 修复"开辟洞府失败实际成功"：不能在事务内用另一条连接查询刚插入的行
        //（事务未提交时新连接看不到该行，导致返回 null 报失败，但插入实际已提交）。
        // 改为：事务内只返回生成的 id，提交后再查询。
        Long caveId = DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, Cave.CREATE_COST_SPIRIT_STONES)) {
                throw new SQLException("灵石扣除失败");
            }
            String sql = "INSERT INTO caves (player_id, name, level, spirit_energy, max_spirit_energy, " +
                    "cultivation_bonus, storage_bonus, last_collect_time) VALUES (?, ?, 1, 0, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, playerId);
                ps.setString(2, name);
                ps.setInt(3, Cave.getMaxSpiritEnergyForLevel(1));
                ps.setInt(4, Cave.getCultivationBonusForLevel(1));
                ps.setInt(5, Cave.getStorageBonusForLevel(1));
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            return null;
        });

        Cave cave = caveId != null ? getCaveById(caveId) : null;

        if (cave != null) {
            result.put("success", true);
            result.put("message", I18n.t("cave.h57e5697c", player.getName(), name, Cave.CREATE_COST_SPIRIT_STONES));
            result.put("cave", cave);
        } else {
            result.put("success", false);
            result.put("message", I18n.t("cave.h6e2e686c"));
        }
        return result;
    }

    private Cave getCaveById(long caveId) {
        String sql = "SELECT * FROM caves WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, caveId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapCave(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询洞府失败", e); }
        return null;
    }

    public Map<String, Object> collectSpiritEnergy(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h7b28cff2"));
            return result;
        }

        long collectInterval = 5 * 60 * 1000L;
        Long collectedEnergy = DatabaseManager.runTransaction(conn -> {
            String checkSql = "SELECT spirit_energy, last_collect_time FROM caves WHERE id = ? " + com.mtxgdn.db.DatabaseManager.getDialect().getForUpdateClause();
            long spiritEnergy = 0;
            long lastCollectTime = 0;
            try (var ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, cave.getId());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        spiritEnergy = rs.getLong("spirit_energy");
                        lastCollectTime = rs.getLong("last_collect_time");
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (lastCollectTime > 0 && (now - lastCollectTime) < collectInterval) {
                return null;
            }

            if (spiritEnergy <= 0) {
                return null;
            }

            String updateSql = "UPDATE caves SET spirit_energy = 0, last_collect_time = ? WHERE id = ?";
            try (var ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, now);
                ps.setLong(2, cave.getId());
                ps.executeUpdate();
            }

            // 在事务内发放灵石，确保灵能重置和灵石发放原子性
            int formationBoost = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalSpiritEnergyBoost(playerId);
            long boostedEnergy = spiritEnergy;
            if (formationBoost > 0) {
                boostedEnergy = spiritEnergy * (100 + formationBoost) / 100;
            }
            itemService.addSpiritStones(conn, playerId, boostedEnergy);
            return boostedEnergy;
        });

        if (collectedEnergy == null) {
            long now = System.currentTimeMillis();
            long elapsed = now - cave.getLastCollectTime();
            if (elapsed < collectInterval) {
                long remaining = (collectInterval - elapsed) / 1000;
                result.put("success", false);
                result.put("message", I18n.t("cave.h5560681a", remaining));
            } else {
                result.put("success", false);
                result.put("message", I18n.t("cave.h13067628"));
            }
            return result;
        }

        result.put("success", true);
        result.put("message", I18n.t("cave.h4ad47298", collectedEnergy));
        result.put("collected", collectedEnergy);
        return result;
    }

    public Map<String, Object> meditate(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h4e9943c4"));
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h59a76b2c"));
            return result;
        }

        int caveBonus = cave.getCultivationBonus();
        int formationBonus = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalCultivationBonus(playerId);
        int totalBonus = caveBonus + formationBonus;
        long expGain = 100 + (long) totalBonus * 10;

        int formationResist = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalHeartDemonResist(playerId);
        int totalCaveLevelForHeartDemon = cave.getLevel() + (formationResist / 2);

        HeartDemonService heartDemonService = ServiceRegistry.getHeartDemonService();
        HeartDemonService.HeartDemonResult heartDemon = heartDemonService.processCultivationWithCave(playerId, expGain, 30, totalCaveLevelForHeartDemon);

        if (heartDemon.triggered) {
            expGain = heartDemon.netExpChange;
            if (expGain < 0) expGain = 0;
            playerService.addExperience(playerId, expGain);
            StringBuilder msg = new StringBuilder();
            msg.append("在洞府中打坐冥想\n");
            msg.append("⚠ 心魔劫: ").append(heartDemon.narrative).append("\n");
            msg.append("实际获得: ").append(expGain).append(" 经验值\n");
            msg.append("洞府修炼加成: +").append(caveBonus).append("%");
            if (formationBonus > 0) {
                msg.append("（阵法加成: +").append(formationBonus).append("%）");
            }
            result.put("success", true);
            result.put("message", msg.toString());
            result.put("expGain", expGain);
            result.put("bonus", totalBonus);
            result.put("heartDemon", heartDemon);
        } else {
            playerService.addExperience(playerId, expGain);
            StringBuilder msg = new StringBuilder();
            msg.append("在洞府中打坐冥想，获得 ").append(expGain).append(" 经验值\n");
            msg.append("洞府修炼加成: +").append(caveBonus).append("%");
            if (formationBonus > 0) {
                msg.append("（阵法加成: +").append(formationBonus).append("%）");
            }
            result.put("success", true);
            result.put("message", msg.toString());
            result.put("expGain", expGain);
            result.put("bonus", totalBonus);
        }
        return result;
    }

    public Map<String, Object> levelUp(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h7b28cff2"));
            return result;
        }

        if (cave.getLevel() >= Cave.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h58c56ef8", Cave.MAX_LEVEL));
            return result;
        }

        long cost = Cave.getLevelUpCost(cave.getLevel());
        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < cost) {
            result.put("success", false);
            result.put("message", I18n.t("cave.h1c7e44f3", cost, spiritStones));
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeSpiritStones(conn, playerId, cost)) {
                throw new SQLException("灵石扣除失败");
            }
            int newLevel = cave.getLevel() + 1;
            String sql = "UPDATE caves SET level = ?, max_spirit_energy = ?, " +
                    "cultivation_bonus = ?, storage_bonus = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, newLevel);
                ps.setInt(2, Cave.getMaxSpiritEnergyForLevel(newLevel));
                ps.setInt(3, Cave.getCultivationBonusForLevel(newLevel));
                ps.setInt(4, Cave.getStorageBonusForLevel(newLevel));
                ps.setLong(5, cave.getId());
                ps.executeUpdate();
            }
            return null;
        });

        int newLevel = cave.getLevel() + 1;
        result.put("success", true);
        result.put("message", I18n.t("cave.h862af23c", cave.getName(), newLevel, cost, Cave.getCultivationBonusForLevel(newLevel), Cave.getStorageBonusForLevel(newLevel)));
        result.put("newLevel", newLevel);
        return result;
    }

    public static void tickSpiritEnergy() {
        // 内置模块门控：洞府模块停用后暂停灵气自动收集
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("cave")) return;
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE caves SET spirit_energy = MIN(max_spirit_energy, spirit_energy + 10)"
                : "UPDATE caves SET spirit_energy = LEAST(max_spirit_energy, spirit_energy + 10)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("更新洞府灵气失败", e); }
    }

    public int getStorageBonus(long playerId) {
        Cave cave = getCaveByPlayerId(playerId);
        return cave != null ? cave.getStorageBonus() : 0;
    }

    private Cave mapCave(ResultSet rs) throws SQLException {
        Cave cave = new Cave();
        cave.setId(rs.getLong("id"));
        cave.setPlayerId(rs.getLong("player_id"));
        cave.setName(rs.getString("name"));
        cave.setLevel(rs.getInt("level"));
        cave.setSpiritEnergy(rs.getLong("spirit_energy"));
        cave.setMaxSpiritEnergy(rs.getInt("max_spirit_energy"));
        cave.setCultivationBonus(rs.getInt("cultivation_bonus"));
        cave.setStorageBonus(rs.getInt("storage_bonus"));
        cave.setLastCollectTime(rs.getLong("last_collect_time"));
        cave.setCreatedAt(rs.getString("created_at"));
        return cave;
    }
}