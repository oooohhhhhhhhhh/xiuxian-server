package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemEffect;
import com.mtxgdn.game.item.ItemRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ItemService {

    private final PlayerService playerService = new PlayerService();

    public boolean addItem(long playerId, String itemKey, int quantity) {
        if (!ItemRegistry.contains(itemKey)) {
            return false;
        }
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON CONFLICT(player_id, item_key) DO UPDATE SET quantity = quantity + excluded.quantity
                """;
        } else {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)
                """;
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.setInt(3, quantity);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("添加物品失败", e);
        }
    }

    public boolean removeItem(long playerId, String itemKey, int quantity) {
        String checkSql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    int currentQty = rs.getInt("quantity");
                    if (currentQty < quantity) {
                        return false;
                    }
                    if (currentQty == quantity) {
                        String delSql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ?";
                        try (PreparedStatement delPs = conn.prepareStatement(delSql)) {
                            delPs.setLong(1, playerId);
                            delPs.setString(2, itemKey);
                            return delPs.executeUpdate() > 0;
                        }
                    } else {
                        String updSql = "UPDATE players_items SET quantity = quantity - ? WHERE player_id = ? AND item_key = ?";
                        try (PreparedStatement updPs = conn.prepareStatement(updSql)) {
                            updPs.setInt(1, quantity);
                            updPs.setLong(2, playerId);
                            updPs.setString(3, itemKey);
                            return updPs.executeUpdate() > 0;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("移除物品失败", e);
        }
    }

    public List<InventoryEntry> getInventory(long playerId) {
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? ORDER BY item_key";
        List<InventoryEntry> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemKey = rs.getString("item_key");
                    int quantity = rs.getInt("quantity");
                    Item item = ItemRegistry.get(itemKey);
                    if (item != null) {
                        result.add(new InventoryEntry(item, quantity));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取背包失败", e);
        }
        return result;
    }

    public int getItemCount(long playerId, String itemKey) {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("quantity");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询物品数量失败", e);
        }
        return 0;
    }

    public boolean hasItem(long playerId, String itemKey, int quantity) {
        return getItemCount(playerId, itemKey) >= quantity;
    }

    public long getSpiritStoneCount(long playerId) {
        return getItemCount(playerId, CurrencyEffect.SPIRIT_STONE_KEY);
    }

    public boolean removeSpiritStones(long playerId, long amount) {
        return removeItem(playerId, CurrencyEffect.SPIRIT_STONE_KEY, (int) amount);
    }

    public boolean addSpiritStones(long playerId, long amount) {
        return addItem(playerId, CurrencyEffect.SPIRIT_STONE_KEY, (int) amount);
    }

    public Map<String, Object> equipItem(long playerId, String itemKey, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        Item item = ItemRegistry.get(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "物品不存在");
            return result;
        }
        if (!hasItem(playerId, itemKey, 1)) {
            result.put("success", false);
            result.put("message", "背包中没有该物品");
            return result;
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (ItemEffect effect : item.getEffects()) {
            String effectMsg = effect.execute(playerId, playerService, this);
            if (effectMsg != null && !effectMsg.isEmpty()) {
                msgBuilder.append(effectMsg);
            }
        }

        String occupied = getEquippedItem(playerId, slot);
        if (occupied != null) {
            Item oldItem = ItemRegistry.get(occupied);
            if (oldItem != null) {
                for (ItemEffect effect : oldItem.getEffects()) {
                    if (effect instanceof BuffEffect be) {
                        if (be.getAttackBonus() > 0) playerService.addAttack(playerId, -be.getAttackBonus());
                        if (be.getDefenseBonus() > 0) playerService.addDefense(playerId, -be.getDefenseBonus());
                        if (be.getSpeedBonus() > 0) playerService.addSpeed(playerId, -be.getSpeedBonus());
                        if (be.getSpiritBonus() > 0) playerService.addSpirit(playerId, -be.getSpiritBonus());
                    }
                }
                addItem(playerId, occupied, 1);
            }
        }

        removeItem(playerId, itemKey, 1);

        String upsertSql;
        if (DatabaseManager.isSqlite()) {
            upsertSql = """
                INSERT INTO players_equipment (player_id, slot, item_key)
                VALUES (?, ?, ?)
                ON CONFLICT(player_id, slot) DO UPDATE SET item_key = excluded.item_key
                """;
        } else {
            upsertSql = """
                INSERT INTO players_equipment (player_id, slot, item_key)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE item_key = VALUES(item_key)
                """;
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertSql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.setString(3, itemKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("装备失败", e);
        }

        String msg;
        if (msgBuilder.length() > 0) {
            msg = "装备了【" + item.getName() + "】: " + msgBuilder.toString();
            if (msg.endsWith("，")) {
                msg = msg.substring(0, msg.length() - 1);
            }
        } else {
            msg = "装备了【" + item.getName() + "】";
        }

        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    public Map<String, Object> unequipItem(long playerId, String slot) {
        Map<String, Object> result = new LinkedHashMap<>();
        String itemKey = getEquippedItem(playerId, slot);
        if (itemKey == null) {
            result.put("success", false);
            result.put("message", "该装备栏位为空");
            return result;
        }
        Item item = ItemRegistry.get(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "物品数据异常");
            return result;
        }

        String delSql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(delSql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("卸下装备失败", e);
        }

        StringBuilder msgBuilder = new StringBuilder();
        for (ItemEffect effect : item.getEffects()) {
            if (effect instanceof BuffEffect be) {
                if (be.getAttackBonus() > 0) {
                    playerService.addAttack(playerId, -be.getAttackBonus());
                    msgBuilder.append("攻击力 -").append(be.getAttackBonus()).append("，");
                }
                if (be.getDefenseBonus() > 0) {
                    playerService.addDefense(playerId, -be.getDefenseBonus());
                    msgBuilder.append("防御力 -").append(be.getDefenseBonus()).append("，");
                }
                if (be.getSpeedBonus() > 0) {
                    playerService.addSpeed(playerId, -be.getSpeedBonus());
                    msgBuilder.append("速度 -").append(be.getSpeedBonus()).append("，");
                }
                if (be.getSpiritBonus() > 0) {
                    playerService.addSpirit(playerId, -be.getSpiritBonus());
                    msgBuilder.append("灵力 -").append(be.getSpiritBonus()).append("，");
                }
            }
        }

        addItem(playerId, itemKey, 1);

        String msg;
        if (msgBuilder.length() > 0) {
            msg = "卸下了【" + item.getName() + "】: " + msgBuilder.toString();
            if (msg.endsWith("，")) {
                msg = msg.substring(0, msg.length() - 1);
            }
        } else {
            msg = "卸下了【" + item.getName() + "】";
        }

        result.put("success", true);
        result.put("message", msg);
        return result;
    }

    public String getEquippedItem(long playerId, String slot) {
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
            throw new RuntimeException("查询装备失败", e);
        }
        return null;
    }

    public Map<String, String> getEquipment(long playerId) {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取装备列表失败", e);
        }
        return result;
    }

    public Map<String, Object> useItem(long playerId, String itemKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "物品不存在");
            return result;
        }
        String fullKey = item.getFullKey();
        if (!hasItem(playerId, fullKey, 1)) {
            result.put("success", false);
            result.put("message", "背包中没有该物品");
            return result;
        }
        removeItem(playerId, fullKey, 1);

        StringBuilder msgBuilder = new StringBuilder();
        for (ItemEffect effect : item.getEffects()) {
            String effectMsg = effect.execute(playerId, playerService, this);
            if (effectMsg != null && !effectMsg.isEmpty()) {
                msgBuilder.append(effectMsg);
            }
        }

        String msg;
        if (msgBuilder.length() > 0) {
            msg = "使用了【" + item.getName() + "】: " + msgBuilder.toString();
            if (msg.endsWith("，")) {
                msg = msg.substring(0, msg.length() - 1);
            }
        } else {
            msg = "使用了【" + item.getName() + "】";
        }

        result.put("success", true);
        result.put("message", msg);
        result.put("item", item);
        result.put("effects", item.getEffects());
        return result;
    }

    public static class InventoryEntry {
        private final Item item;
        private final int quantity;

        InventoryEntry(Item item, int quantity) {
            this.item = item;
            this.quantity = quantity;
        }

        public Item getItem() {
            return item;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
