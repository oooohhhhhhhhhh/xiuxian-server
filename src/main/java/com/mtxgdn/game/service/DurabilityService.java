package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装备耐久与修理。
 *
 * <p>设计要点（这三条决定了它不能被"卸下再装上"绕过）：</p>
 * <ol>
 *   <li><b>耐久独立存表</b>（{@code player_item_durability}，键为 玩家+物品key），
 *       不挂在 {@code players_equipment} 上。装备脱下来耐久还在，重新装上接着用，
 *       只有修理能恢复 —— 否则"卸下→装上"就是一次免费修理，整个系统形同虚设。</li>
 *   <li><b>损坏不靠改玩家属性</b>：装备的属性加成在 {@code equipItem} 时已经物化进
 *       {@code players} 表（见 {@code BuffEffect}）。耐久归零时如果在战斗中回退这些属性，
 *       需要额外的写事务且容易与并发战斗打架；因此改为<b>读取时扣减</b>
 *       （{@link PlayerService#getFinalAttack} 等），修理后自动恢复，天然可逆。</li>
 *   <li><b>损坏不可卸下</b>：{@code unequipItem} 会拒绝已损坏的装备，
 *       逼玩家先修理再换装，否则"损坏→卸下→装上"又是一次免费重置。</li>
 * </ol>
 */
public class DurabilityService {

    private static final GameLogger LOG = GameLogger.getLogger(DurabilityService.class);

    /** 装备槽位：与 ItemService 的三部位保持一致。 */
    public static final String[] SLOTS = {"weapon", "armor", "accessory"};

    private static final String[] SLOT_NAMES = {"武器", "护甲", "饰品"};

    public static String getSlotDisplayName(String slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].equals(slot)) return SLOT_NAMES[i];
        }
        return slot;
    }

    /** 满耐久值。做成配置项方便运维按服务器节奏调整磨损速度。 */
    public static int getMaxDurability() {
        return Math.max(1, AppConfig.getInt("equipment.max_durability", 100));
    }

    /** 单场 PVE 战斗的耐久消耗。 */
    public static int getPveWear() {
        return Math.max(0, AppConfig.getInt("equipment.wear_pve", 1));
    }

    /** 单场 PVP 战斗的耐久消耗。 */
    public static int getPvpWear() {
        return Math.max(0, AppConfig.getInt("equipment.wear_pvp", 2));
    }

    /** 每点耐久的修理单价：物品价格 / 该系数，最少 1 灵石。 */
    private static int getRepairPriceDivisor() {
        return Math.max(1, AppConfig.getInt("equipment.repair_price_divisor", 50));
    }

    // ==================== 查询 ====================

    /**
     * 单个物品的当前耐久。没有记录 = 从未磨损过 = 满耐久。
     */
    public int getDurability(long playerId, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getDurability(conn, playerId, itemKey);
        } catch (SQLException e) {
            LOG.warn("查询装备耐久失败: " + e.getMessage());
            return getMaxDurability();
        }
    }

    public int getDurability(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT durability FROM player_item_durability WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("durability");
                }
            }
        }
        return getMaxDurability();
    }

    /** 装备栏各槽位的当前耐久（slot → durability）。未装备的槽位不出现在结果里。 */
    public Map<String, Integer> getEquippedDurability(long playerId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String sql = "SELECT e.slot, e.item_key, d.durability FROM players_equipment e "
                + "LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int dur = rs.getInt("durability");
                    if (rs.wasNull()) {
                        dur = getMaxDurability();
                    }
                    result.put(rs.getString("slot"), dur);
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询装备耐久列表失败: " + e.getMessage());
        }
        return result;
    }

    public boolean isBroken(long playerId, String itemKey) {
        return getDurability(playerId, itemKey) <= 0;
    }

    /**
     * 已损坏装备提供的属性加成总和（攻击/防御/速度/灵力）。
     *
     * <p>这些加成在装备时已经物化进 {@code players} 表，所以损坏时要在读取侧减回去。
     * 只统计 {@link BuffEffect} —— 装备的属性就是靠它加上去的，其他效果（回血、学技能等）
     * 是一次性的，没有"损坏了要扣回来"的问题。</p>
     */
    public int[] getBrokenPenalty(long playerId) {
        int[] penalty = {0, 0, 0, 0};
        String sql = "SELECT e.item_key, d.durability FROM players_equipment e "
                + "JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ? AND d.durability <= 0";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Item item = ItemRegistry.get(rs.getString("item_key"));
                    if (item == null) continue;
                    for (ItemEffect effect : item.getEffects()) {
                        if (effect instanceof BuffEffect be) {
                            penalty[0] += be.getAttackBonus();
                            penalty[1] += be.getDefenseBonus();
                            penalty[2] += be.getSpeedBonus();
                            penalty[3] += be.getSpiritBonus();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("统计损坏装备惩罚失败: " + e.getMessage());
        }
        return penalty;
    }

    // ==================== 磨损 ====================

    /**
     * 给玩家当前装备的每件装备扣耐久（下限 0）。战斗结束后调用。
     *
     * <p>用 upsert 一次写三行：没有记录的装备第一次磨损时按"满耐久 - 消耗"落库，
     * 已有记录的直接扣减。耐久不会被扣成负数。</p>
     */
    public void applyWear(long playerId, int amount) {
        if (amount <= 0) {
            return;
        }
        Map<String, String> equipment = ServiceRegistry.getItemService().getEquipment(playerId);
        if (equipment.isEmpty()) {
            return;
        }
        int max = getMaxDurability();
        String sql = DatabaseManager.isSqlite()
                ? "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON CONFLICT(player_id, item_key) DO UPDATE SET "
                + "durability = MAX(0, durability - ?), updated_at = CURRENT_TIMESTAMP"
                : "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "durability = GREATEST(0, durability - ?), updated_at = CURRENT_TIMESTAMP";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : equipment.values()) {
                if (itemKey == null || itemKey.isEmpty()) continue;
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, Math.max(0, max - amount));
                ps.setInt(4, amount);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            // 磨损失败不该让战斗报错：耐久是长期账，漏一次可以接受
            LOG.warn("装备磨损结算失败: " + e.getMessage());
        }
    }

    // ==================== 修理 ====================

    /**
     * 修理装备。
     *
     * @param slot 槽位名，或 {@code all} / {@code 全部} 表示修理全部装备
     */
    public Map<String, Object> repair(long playerId, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean all = "all".equalsIgnoreCase(slot) || "全部".equals(slot) || "所有".equals(slot);

        Map<String, String> equipment = ServiceRegistry.getItemService().getEquipment(playerId);
        if (equipment.isEmpty()) {
            result.put("success", false);
            result.put("message", I18n.t("equip.repair_no_equipment"));
            return result;
        }
        if (!all && !equipment.containsKey(slot)) {
            result.put("success", false);
            result.put("message", I18n.t("equip.repair_slot_empty", getSlotDisplayName(slot)));
            return result;
        }

        int max = getMaxDurability();
        int divisor = getRepairPriceDivisor();
        long totalCost = 0;
        int totalMissing = 0;
        Map<String, Integer> toRepair = new LinkedHashMap<>();
        StringBuilder detail = new StringBuilder();

        for (Map.Entry<String, String> entry : equipment.entrySet()) {
            String slotName = entry.getKey();
            if (!all && !slotName.equals(slot)) continue;
            String itemKey = entry.getValue();
            Item item = ItemRegistry.get(itemKey);
            if (item == null) continue;
            int current = getDurability(playerId, itemKey);
            int missing = max - current;
            if (missing <= 0) continue;
            int unit = Math.max(1, item.getPrice() / divisor);
            long cost = (long) missing * unit;
            totalCost += cost;
            totalMissing += missing;
            toRepair.put(itemKey, missing);
            if (detail.length() > 0) detail.append("、");
            detail.append(getSlotDisplayName(slotName)).append("+").append(missing).append("耐久");
        }

        if (toRepair.isEmpty()) {
            result.put("success", false);
            result.put("message", I18n.t("equip.repair_full"));
            return result;
        }

        ItemService itemService = ServiceRegistry.getItemService();
        if (!itemService.removeSpiritStones(playerId, totalCost)) {
            result.put("success", false);
            result.put("message", I18n.t("equip.repair_no_stones", totalCost));
            return result;
        }

        String sql = DatabaseManager.isSqlite()
                ? "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON CONFLICT(player_id, item_key) DO UPDATE SET "
                + "durability = ?, updated_at = CURRENT_TIMESTAMP"
                : "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE durability = VALUES(durability), updated_at = CURRENT_TIMESTAMP";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : toRepair.keySet()) {
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, max);
                if (DatabaseManager.isSqlite()) {
                    ps.setInt(4, max);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.error("修理装备写库失败: " + e.getMessage());
            result.put("success", false);
            result.put("message", I18n.t("equip.repair_failed"));
            return result;
        }

        result.put("success", true);
        result.put("cost", totalCost);
        result.put("restored", totalMissing);
        result.put("message", I18n.t("equip.repair_ok", detail.toString(), totalCost));
        return result;
    }

    /**
     * 装备面板展示用的一行文本，例如 {@code 武器 87/100}；损坏时标红提示。
     */
    public String describeSlot(long playerId, String slot, String itemKey) {
        int current = getDurability(playerId, itemKey);
        int max = getMaxDurability();
        String text = getSlotDisplayName(slot) + " " + current + "/" + max;
        return current <= 0 ? text + "（" + I18n.t("equip.broken_tag") + "）" : text;
    }
}
