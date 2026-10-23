package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import com.mtxgdn.i18n.I18n;

public class EnhanceService {

    private static final GameLogger LOG = GameLogger.getLogger(EnhanceService.class);
    private static final int MAX_ENHANCE_LEVEL = 15;
    /** 连续失败保底：每失败一次 +5% 成功率，最多累计 6 次（+30%），成功后清零 */
    private static final int MAX_PITY_STREAK = 6;
    private static final int PITY_BONUS_PER_FAIL = 5;
    private static final ConcurrentHashMap<String, Integer> FAIL_STREAK = new ConcurrentHashMap<>();
    private final PlayerService playerService;
    private final ItemService itemService;
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

    public EnhanceService() {
        this.playerService = ServiceRegistry.getPlayerService();
        this.itemService = ServiceRegistry.getItemService();
    }

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
            return createResult(false, I18n.t("enhance.there_is_no_equipment"));
        }

        Item item = ItemRegistry.get(itemKey);
        if (item == null) {
            return createResult(false, I18n.t("enhance.equipment_data_error"));
        }

        int currentLevel = getEnhanceLevel(playerId, slot);
        if (currentLevel >= MAX_ENHANCE_LEVEL) {
            return createResult(false, I18n.t("enhance.this_equipment_is_already", MAX_ENHANCE_LEVEL));
        }

        int goldCost = ENHANCE_GOLD_COST[currentLevel];
        int stoneCost = ENHANCE_STONE_COST[currentLevel];

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return createResult(false, I18n.t("enhance.character_not_found"));
        }

        if (!checkGold(player, goldCost)) {
            return createResult(false, I18n.t("enhance.not_enough_gold", goldCost, player.getGold()));
        }

        if (!itemService.hasItem(playerId, "mtxgdn:enhance_stone", stoneCost)) {
            return createResult(false, I18n.t("enhance.not_enough_enhance_stones", stoneCost));
        }

        int successRate = ENHANCE_SUCCESS_RATE[currentLevel];
        int breakRate = ENHANCE_BREAK_RATE[currentLevel];
        int degradeRate = ENHANCE_DEGRADE_RATE[currentLevel];

        // 连续失败保底：每失败一次 +5%，上限 +30%
        String pityKey = playerId + ":" + slot;
        int pityStreak = Math.min(MAX_PITY_STREAK, FAIL_STREAK.getOrDefault(pityKey, 0));
        int effectiveSuccessRate = Math.min(95, successRate + pityStreak * PITY_BONUS_PER_FAIL);

        result.put("slot", slot);
        result.put("itemName", item.getName());
        result.put("currentLevel", currentLevel);
        result.put("costGold", goldCost);
        result.put("costStones", stoneCost);
        result.put("successRate", effectiveSuccessRate);
        result.put("breakRate", breakRate);
        result.put("pityStreak", pityStreak);

        int roll = random.nextInt(100);

        try {
            String outcome = DatabaseManager.runTransaction(conn -> {
                playerService.addGold(conn, playerId, -goldCost);
                itemService.removeItem(conn, playerId, "mtxgdn:enhance_stone", stoneCost);

                if (roll < effectiveSuccessRate) {
                    setEnhanceLevel(conn, playerId, slot, currentLevel + 1);
                    return "success";
                }

                int failRoll = roll - effectiveSuccessRate;
                if (failRoll < breakRate && currentLevel >= 5) {
                    // 有保护符时消耗一张，装备免于碎裂
                    if (itemService.getItemCount(conn, playerId, "mtxgdn:protect_charm") >= 1) {
                        itemService.removeItem(conn, playerId, "mtxgdn:protect_charm", 1);
                        return "protected";
                    }
                    unequipItem(conn, playerId, slot);
                    return "break";
                } else if (failRoll < breakRate + degradeRate && currentLevel > 0) {
                    setEnhanceLevel(conn, playerId, slot, currentLevel - 1);
                    return "degrade";
                }
                return "fail";
            });

            if ("success".equals(outcome)) {
                FAIL_STREAK.remove(pityKey);
            } else {
                FAIL_STREAK.compute(pityKey, (k, v) -> v == null ? 1 : Math.min(MAX_PITY_STREAK, v + 1));
            }
            return buildEnhanceResult(result, outcome, currentLevel, item.getName());
        } catch (Exception e) {
            result.put("success", false);
            result.put("enhanceSuccess", false);
            result.put("message", I18n.t("enhance.enhancement_failed_due", e.getMessage()));
            return result;
        }
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

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    private boolean checkGold(Player player, int goldCost) {
        return player.getGold() >= goldCost;
    }

    private Map<String, Object> buildEnhanceResult(Map<String, Object> result, String outcome,
                                                    int currentLevel, String itemName) {
        switch (outcome) {
            case "success" -> {
                result.put("success", true);
                result.put("enhanceSuccess", true);
                result.put("newLevel", currentLevel + 1);
                result.put("message", I18n.t("enhance.success", itemName, currentLevel + 1));
            }
            case "break" -> {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("broken", true);
                result.put("brokenItem", itemName);
                result.put("message", I18n.t("enhance.enhancement_failed_2", itemName));
            }
            case "protected" -> {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("protected", true);
                result.put("message", I18n.t("enhance.enhancement_failed_3"));
            }
            case "degrade" -> {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("degraded", true);
                result.put("newLevel", currentLevel - 1);
                result.put("message", I18n.t("enhance.break", itemName, currentLevel - 1));
            }
            default -> {
                result.put("success", false);
                result.put("enhanceSuccess", false);
                result.put("message", I18n.t("enhance.enhancement_failed"));
            }
        }
        return result;
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

    /**
     * 强化碎裂：装备被销毁。
     *
     * <p><b>这里曾经只发一条 {@code DELETE FROM players_equipment}</b>，后果是
     * 装备虽然从身上消失了，但它通过 {@link BuffEffect} <b>实打实加在
     * {@code players.attack/defense/speed/spirit} 上的属性从未被扣回</b> ——
     * 玩家可以靠"强化到高等级再碎掉"永久白嫖一份属性，而且这份属性不在任何界面上
     * 显示为来源，排查时完全看不出来。装备被销毁是玩法设定（碎裂），但属性必须一起消失。</p>
     *
     * <p>属性回退逻辑与 {@code ItemService.equipItem} 里"回退旧装备效果"完全对齐：
     * 同样只看 {@link BuffEffect} 的四项加成，同样在同一个事务连接上扣减，
     * 保证"删装备行"与"扣属性"要么一起成功要么一起回滚。</p>
     *
     * <p>另外记一条 warn：装备碎裂是不可逆的玩家资产损失，之前没有任何日志，
     * 玩家申诉时无从查证。</p>
     */
    private void unequipItem(Connection conn, long playerId, String slot) throws SQLException {
        String itemKey = getEquippedItemKey(conn, playerId, slot);
        int enhanceLevel = getEnhanceLevel(conn, playerId, slot);
        if (itemKey != null) {
            Item item = ItemRegistry.get(itemKey);
            if (item != null) {
                for (ItemEffect effect : item.getEffects()) {
                    if (effect instanceof BuffEffect be) {
                        if (be.getAttackBonus() > 0) playerService.addAttack(conn, playerId, -be.getAttackBonus());
                        if (be.getDefenseBonus() > 0) playerService.addDefense(conn, playerId, -be.getDefenseBonus());
                        if (be.getSpeedBonus() > 0) playerService.addSpeed(conn, playerId, -be.getSpeedBonus());
                        if (be.getSpiritBonus() > 0) playerService.addSpirit(conn, playerId, -be.getSpiritBonus());
                    }
                }
            } else {
                LOG.warn("强化碎裂时找不到装备定义，属性无法回退: playerId=" + playerId
                        + " slot=" + slot + " itemKey=" + itemKey);
            }
        }

        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }

        LOG.warn("装备强化碎裂销毁: playerId=" + playerId + " slot=" + slot
                + " itemKey=" + itemKey + " enhanceLevel=" + enhanceLevel);
    }

    /** 事务内版本：复用调用方已开启的连接，避免在持有连接时再向池里要一条。 */
    private String getEquippedItemKey(Connection conn, long playerId, String slot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("item_key") : null;
            }
        }
    }

    /** 事务内版本：同 {@link #getEquippedItemKey(Connection, long, String)}。 */
    private int getEnhanceLevel(Connection conn, long playerId, String slot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT enhance_level FROM players_equipment WHERE player_id = ? AND slot = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("enhance_level") : 0;
            }
        }
    }
}