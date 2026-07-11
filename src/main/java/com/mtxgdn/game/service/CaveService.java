package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.Cave;
import com.mtxgdn.game.service.HeartDemonService;
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

public class CaveService {

    private static final GameLogger LOG = GameLogger.getLogger(CaveService.class);

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(CaveService::tickSpiritEnergy, 10, 10, TimeUnit.SECONDS);
        LOG.info("洞府灵气自动增长任务已启动");
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
        Map<String, Object> result = new LinkedHashMap<>();

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        if (player.getRealm() < Cave.MIN_REALM_CREATE) {
            String realmName = GameConfigLoader.getRealmConfig(player.getRealm(), 0) != null
                    ? GameConfigLoader.getRealmConfig(player.getRealm(), 0).getFullName() : "凡人";
            result.put("success", false);
            result.put("message", "境界不足，需要达到筑基期以上才能开辟洞府（当前：" + realmName + "）");
            return result;
        }

        if (getCaveByPlayerId(playerId) != null) {
            result.put("success", false);
            result.put("message", "你已经拥有洞府了");
            return result;
        }

        long spiritStones = itemService.getSpiritStoneCount(playerId);
        if (spiritStones < Cave.CREATE_COST_SPIRIT_STONES) {
            result.put("success", false);
            result.put("message", "灵石不足，开辟洞府需要 " + Cave.CREATE_COST_SPIRIT_STONES + " 灵石（你目前有 " + spiritStones + " 灵石）");
            return result;
        }

        String caveNameFinal = caveName != null && !caveName.trim().isEmpty() ? caveName.trim() : "山间洞府";
        if (caveNameFinal.length() > 10) {
            caveNameFinal = caveNameFinal.substring(0, 10);
        }
        final String name = caveNameFinal;

        Cave cave = DatabaseManager.runTransaction(conn -> {
            if (!itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, Cave.CREATE_COST_SPIRIT_STONES)) {
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
                    long caveId = keys.getLong(1);
                    return getCaveById(caveId);
                }
            }
            return null;
        });

        if (cave != null) {
            result.put("success", true);
            result.put("message", "恭喜【" + player.getName() + "】开辟洞府【" + name + "】！消耗灵石 " + Cave.CREATE_COST_SPIRIT_STONES + "\n" +
                    "洞府可提供修炼加成和额外存储空间");
            result.put("cave", cave);
        } else {
            result.put("success", false);
            result.put("message", "开辟洞府失败");
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
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", "你还没有洞府");
            return result;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - cave.getLastCollectTime();
        long collectInterval = 5 * 60 * 1000L;
        if (elapsed < collectInterval) {
            long remaining = (collectInterval - elapsed) / 1000;
            result.put("success", false);
            result.put("message", "灵气尚未汇聚，还需等待 " + remaining + " 秒");
            return result;
        }

        long spiritEnergy = cave.getSpiritEnergy();
        if (spiritEnergy <= 0) {
            result.put("success", false);
            result.put("message", "洞府灵气不足，需要等待灵气汇聚");
            return result;
        }

        DatabaseManager.runTransaction(conn -> {
            String sql = "UPDATE caves SET spirit_energy = 0, last_collect_time = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, now);
                ps.setLong(2, cave.getId());
                ps.executeUpdate();
            }
            return null;
        });

        int formationBoost = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalSpiritEnergyBoost(playerId);
        long boostedEnergy = spiritEnergy;
        if (formationBoost > 0) {
            boostedEnergy = spiritEnergy * (100 + formationBoost) / 100;
        }

        itemService.addSpiritStones(playerId, boostedEnergy);

        result.put("success", true);
        if (formationBoost > 0) {
            result.put("message", "收集洞府灵气获得 " + boostedEnergy + " 灵石！（阵法加成 +" + formationBoost + "%）");
        } else {
            result.put("message", "收集洞府灵气获得 " + boostedEnergy + " 灵石！");
        }
        result.put("collected", boostedEnergy);
        return result;
    }

    public Map<String, Object> meditate(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", "你还没有洞府，无法在此修炼");
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        int caveBonus = cave.getCultivationBonus();
        int formationBonus = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalCultivationBonus(playerId);
        int totalBonus = caveBonus + formationBonus;
        long expGain = 100 + (long) totalBonus * 10;

        int formationResist = com.mtxgdn.common.service.ServiceRegistry.getFormationService().getTotalHeartDemonResist(playerId);
        int totalCaveLevelForHeartDemon = cave.getLevel() + (formationResist / 2);

        HeartDemonService heartDemonService = new HeartDemonService();
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
        Map<String, Object> result = new LinkedHashMap<>();

        Cave cave = getCaveByPlayerId(playerId);
        if (cave == null) {
            result.put("success", false);
            result.put("message", "你还没有洞府");
            return result;
        }

        if (cave.getLevel() >= Cave.MAX_LEVEL) {
            result.put("success", false);
            result.put("message", "洞府已达到最高等级 " + Cave.MAX_LEVEL + " 级");
            return result;
        }

        long cost = Cave.getLevelUpCost(cave.getLevel());
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
        result.put("message", "洞府【" + cave.getName() + "】晋升为 " + newLevel + " 级！\n" +
                "消耗 " + cost + " 灵石\n" +
                "修炼加成: +" + Cave.getCultivationBonusForLevel(newLevel) + "%\n" +
                "存储加成: +" + Cave.getStorageBonusForLevel(newLevel) + " 格");
        result.put("newLevel", newLevel);
        return result;
    }

    public static void tickSpiritEnergy() {
        String sql = "UPDATE caves SET spirit_energy = LEAST(max_spirit_energy, spirit_energy + 10)";
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