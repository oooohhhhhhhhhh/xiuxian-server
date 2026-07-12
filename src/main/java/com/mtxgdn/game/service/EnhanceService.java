package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class EnhanceService {

    private static final GameLogger LOG = GameLogger.getLogger(EnhanceService.class);
    private static final int MAX_ENHANCE_LEVEL = 15;
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final Random random = new Random();

    private static final int[] ENHANCE_GOLD_COST = {
        500, 800, 1200, 1800, 2500, 3500, 5000, 7000, 10000, 15000,
        22000, 32000, 48000, 70000, 100000
    };

    private static final int[] ENHANCE_STONE_COST = {
        1, 1, 2, 2, 3, 4, 5, 7, 10, 14,
        20, 28, 40, 55, 75
    };

    private static final int[] ENHANCE_SUCCESS_RATE = {
        95, 90, 85, 80, 75, 65, 55, 45, 35, 25,
        18, 12, 8, 5, 3
    };

    private static final int[] ENHANCE_BREAK_RATE = {
        0, 0, 0, 0, 0, 2, 5, 8, 12, 18,
        25, 35, 45, 55, 65
    };

    private static final int[] ENHANCE_DEGRADE_RATE = {
        0, 0, 0, 0, 0, 8, 15, 20, 25, 25,
        25, 20, 20, 15, 10
    };

    private static final double ENHANCE_ATK_MULTIPLIER = 0.06;
    private static final double ENHANCE_DEF_MULTIPLIER = 0.06;
    private static final double ENHANCE_SPD_MULTIPLIER = 0.04;
    private static final double ENHANCE_SPIRIT_MULTIPLIER = 0.04;

    public int getEnhanceLevel(long playerId, String slot) {
        String sql = "SELECT enhance_level FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("enhance_level");
                }
            }
        } catch (SQLException e) {
            LOG.error("查询强化等级失败: " + e.getMessage(), e);
        }
        return 0;
    }

    public String getEquippedItemKey(long playerId, String slot) {
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("item_key");
                }
            }
        } catch (SQLException e) {
            LOG.error("查询装备失败: " + e.getMessage(), e);
        }
        return null;
    }

    public Map<String, Object> enhanceItem(long playerId, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();

        String itemKey = getEquippedItemKey(playerId, slot);
        if (itemKey == null) {
            result.put("success", false);
            result.put("message", "该装备栏没有装备");
            return result;
        }

        Item item = ItemRegistry.get(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "装备数据异常");
            return result;
        }

        int currentLevel = getEnhanceLevel(playerId, slot);
        if (currentLevel >= MAX_ENHANCE_LEVEL) {
            result.put("success", false);
            result.put("message", "装备已达最高强化等级 +" + MAX_ENHANCE_LEVEL);
            return result;
        }

        int goldCost = ENHANCE_GOLD_COST[currentLevel];
        int stoneCost = ENHANCE_STONE_COST[currentLevel];

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }
        long playerGold = player.getGold();
        if (playerGold < goldCost) {
            result.put("success", false);
            result.put("message", "金币不足，需要 " + goldCost + " 金币（当前: " + playerGold + "）");
            return result;
        }

        boolean hasStones = itemService.hasItem(playerId, "enhance_stone", stoneCost);
        if (!hasStones) {
            result.put("success", false);
            result.put("message", "强化石不足，需要 " + stoneCost + " 个强化石");
            return result;
        }

        int successRate = ENHANCE_SUCCESS_RATE[currentLevel];
        int breakRate = ENHANCE_BREAK_RATE[currentLevel];
        int degradeRate = ENHANCE_DEGRADE_RATE[currentLevel];

        result.put("slot", slot);
        result.put("itemName", item.getName());
        result.put("currentLevel", currentLevel);
        result.put("costGold", goldCost);
        result.put("costStones", stoneCost);
        result.put("successRate", successRate);
        result.put("breakRate", breakRate);

        int roll = random.nextInt(100);

        try {
            DatabaseManager.runTransaction(conn -> {
                // 扣材料
                playerService.addGold(conn, playerId, -goldCost);
                itemService.removeItem(conn, playerId, "mtxgdn:enhance_stone", stoneCost);

                if (roll < successRate) {
                    setEnhanceLevel(conn, playerId, slot, currentLevel + 1);
                } else {
                    int failRoll = roll - successRate;
                    if (failRoll < breakRate && currentLevel >= 5) {
                        unequipItem(conn, playerId, slot);
                    } else if (failRoll < breakRate + degradeRate && currentLevel > 0) {
                        setEnhanceLevel(conn, playerId, slot, currentLevel - 1);
                    }
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("enhanceSuccess", false);
            result.put("message", "强化失败，系统异常: " + e.getMessage());
            return result;
        }

        if (roll < successRate) {
            result.put("success", true);
            result.put("enhanceSuccess", true);
            result.put("newLevel", currentLevel + 1);
            result.put("message", "强化成功！" + item.getName() + " 提升至 +" + (currentLevel + 1));
        } else {
            int failRoll = roll - successRate;
            if (failRoll < breakRate && currentLevel >= 5) {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("broken", true);
                result.put("brokenItem", item.getName());
                result.put("message", "强化失败！" + item.getName() + " 承受不住力量，碎裂了...");
            } else if (failRoll < breakRate + degradeRate && currentLevel > 0) {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("degraded", true);
                result.put("newLevel", currentLevel - 1);
                result.put("message", "强化失败！" + item.getName() + " 降级至 +" + (currentLevel - 1));
            } else {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("message", "强化失败！材料消耗但装备未受影响");
            }
        }

        return result;
    }

    public static int[] getEnhanceStatBonus(int enhanceLevel) {
        if (enhanceLevel <= 0) return new int[]{0, 0, 0, 0};
        return new int[]{
            (int)(enhanceLevel * 12 * ENHANCE_ATK_MULTIPLIER * enhanceLevel),
            (int)(enhanceLevel * 10 * ENHANCE_DEF_MULTIPLIER * enhanceLevel),
            (int)(enhanceLevel * 8 * ENHANCE_SPD_MULTIPLIER * enhanceLevel),
            (int)(enhanceLevel * 6 * ENHANCE_SPIRIT_MULTIPLIER * enhanceLevel)
        };
    }

    private void setEnhanceLevel(Connection conn, long playerId, String slot, int level) throws SQLException {
        String sql = "UPDATE players_equipment SET enhance_level = ? WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setLong(2, playerId);
            ps.setString(3, slot);
            ps.executeUpdate();
        }
    }

    private void unequipItem(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }
    }
}
