package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EquipmentFixService {

    private static final GameLogger LOG = GameLogger.getLogger(EquipmentFixService.class);
    private static boolean hasRun = false;

    public void fixEquipmentData() {
        if (hasRun) {
            LOG.info("装备数据修复已执行过，跳过");
            return;
        }
        hasRun = true;
        LOG.info("开始检查装备数据一致性...");
        List<Long> playerIds = getAllPlayerIds();
        
        int fixedCount = 0;
        int errorCount = 0;
        
        for (Long playerId : playerIds) {
            try {
                if (fixPlayerEquipment(playerId)) {
                    fixedCount++;
                }
            } catch (Exception e) {
                LOG.error("修复玩家 " + playerId + " 的装备数据失败: " + e.getMessage());
                errorCount++;
            }
        }
        
        LOG.info("装备数据检查完成: 修复 " + fixedCount + " 个玩家, 错误 " + errorCount + " 个");
    }

    private List<Long> getAllPlayerIds() {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM players";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            LOG.error("获取玩家列表失败: " + e.getMessage());
        }
        return ids;
    }

    private boolean fixPlayerEquipment(long playerId) {
        final boolean[] fixed = {false};
        
        DatabaseManager.runTransaction(conn -> {
            try {
                Map<String, String> equipment = getEquipment(conn, playerId);
                
                for (String slot : equipment.keySet()) {
                    String itemKey = equipment.get(slot);
                    Item item = ItemRegistry.get(itemKey);
                    
                    if (item == null) {
                        LOG.warn("玩家 " + playerId + " 的 " + slot + " 槽位装备不存在: " + itemKey);
                        removeFromEquipment(conn, playerId, slot);
                        fixed[0] = true;
                        continue;
                    }
                    
                    long inBackpack = getItemCount(conn, playerId, itemKey);
                    if (inBackpack > 0) {
                        LOG.info("玩家 " + playerId + " 的 " + slot + " 槽位装备在背包中存在，正在修复...");
                        removeItem(conn, playerId, itemKey, 1);
                        fixed[0] = true;
                    }
                }
                
                List<String> equipmentKeys = getEquipmentKeys(conn, playerId);
                List<String> equipmentItemsInBackpack = getEquipmentTypeItemsInBackpack(conn, playerId);
                
                for (String itemKey : equipmentItemsInBackpack) {
                    if (!equipmentKeys.contains(itemKey)) {
                        Item item = ItemRegistry.get(itemKey);
                        if (item != null && item.getType() == ItemType.EQUIPMENT) {
                            LOG.warn("玩家 " + playerId + " 背包中有装备物品但未装备: " + item.getName());
                        }
                    }
                }
                
            } catch (SQLException e) {
                throw new RuntimeException("修复装备数据失败: " + e.getMessage(), e);
            }
            return true;
        });
        
        return fixed[0];
    }

    private Map<String, String> getEquipment(Connection conn, long playerId) throws SQLException {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        }
        return result;
    }

    private List<String> getEquipmentKeys(Connection conn, long playerId) throws SQLException {
        List<String> keys = new ArrayList<>();
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("item_key"));
                }
            }
        }
        return keys;
    }

    private List<String> getEquipmentTypeItemsInBackpack(Connection conn, long playerId) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemKey = rs.getString("item_key");
                    Item item = ItemRegistry.get(itemKey);
                    if (item != null && item.getType() == ItemType.EQUIPMENT) {
                        result.add(itemKey);
                    }
                }
            }
        }
        return result;
    }

    private long getItemCount(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return 0;
    }

    private void removeFromEquipment(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }
    }

    private void removeItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE player_id = ? AND item_key = ? AND quantity >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.setLong(4, quantity);
            ps.executeUpdate();
            
            String cleanSql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0";
            try (PreparedStatement cleanPs = conn.prepareStatement(cleanSql)) {
                cleanPs.setLong(1, playerId);
                cleanPs.setString(2, itemKey);
                cleanPs.executeUpdate();
            }
        }
    }
}