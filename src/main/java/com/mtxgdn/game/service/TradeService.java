package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TradeService {

    private static final double TRADE_FEE_RATE = 0.05;
    private final ItemService itemService = new ItemService();
    private final PlayerService playerService = new PlayerService();

    public Map<String, Object> listItem(long playerId, String itemKey, int quantity, long priceSpiritStones) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (quantity <= 0 || priceSpiritStones <= 0) {
            result.put("success", false);
            result.put("message", "数量和价格必须大于 0");
            return result;
        }
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            result.put("success", false);
            result.put("message", "物品不存在，请使用 /物品列表 查看可用物品");
            return result;
        }
        String fullKey = item.getFullKey();
        if (!itemService.hasItem(playerId, fullKey, quantity)) {
            result.put("success", false);
            result.put("message", "背包中没有足够的【" + item.getName() + "】");
            return result;
        }
        long fee = (long)(priceSpiritStones * TRADE_FEE_RATE);
        if (fee < 1) fee = 1;

        Player seller = playerService.getPlayerById(playerId);
        if (seller != null && seller.getSpiritualRoot() != null
                && seller.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.TRADE_FEE_HALF)) {
            fee = fee / 2;
            if (fee < 1) fee = 1;
        }

        itemService.removeItem(playerId, itemKey, quantity);

        String sql = "INSERT INTO trade_listings (seller_player_id, item_key, quantity, price_spirit_stones, fee) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, fullKey);
            ps.setInt(3, quantity);
            ps.setLong(4, priceSpiritStones);
            ps.setLong(5, fee);
            ps.executeUpdate();
        } catch (SQLException e) {
            itemService.addItem(playerId, fullKey, quantity);
            throw new RuntimeException("上架物品失败", e);
        }

        result.put("success", true);
        result.put("message", "已将【" + item.getName() + " (" + fullKey + ")】×" + quantity + " 挂入坊市，售价 " + priceSpiritStones + " 灵石（坊市抽成 " + fee + " 灵石）");
        result.put("fee", fee);
        return result;
    }

    public Map<String, Object> buyItem(long buyerPlayerId, long listingId) {
        Map<String, Object> result = new LinkedHashMap<>();
        TradeListing listing = getListing(listingId);
        if (listing == null) {
            result.put("success", false);
            result.put("message", "该挂单不存在或已被购买");
            return result;
        }
        if (listing.sellerPlayerId == buyerPlayerId) {
            result.put("success", false);
            result.put("message", "不能购买自己的物品");
            return result;
        }

        long totalCost = listing.priceSpiritStones;
        long buyerSpiritStones = itemService.getSpiritStoneCount(buyerPlayerId);
        if (buyerSpiritStones < totalCost) {
            result.put("success", false);
            result.put("message", "灵石不足，需要 " + totalCost + " 灵石，当前仅有 " + buyerSpiritStones + " 灵石");
            return result;
        }

        removeListing(listingId);
        itemService.removeSpiritStones(buyerPlayerId, totalCost);
        itemService.addItem(buyerPlayerId, listing.itemKey, listing.quantity);

        long sellerReceive = totalCost - listing.fee;
        itemService.addSpiritStones(listing.sellerPlayerId, sellerReceive);

        Item item = ItemRegistry.get(listing.itemKey);
        result.put("success", true);
        result.put("message", "在坊市中购得【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity
                + "，花费 " + totalCost + " 灵石（坊主抽成 " + listing.fee + " 灵石）");
        result.put("cost", totalCost);
        result.put("fee", listing.fee);
        return result;
    }

    public Map<String, Object> cancelListing(long playerId, long listingId) {
        Map<String, Object> result = new LinkedHashMap<>();
        TradeListing listing = getListing(listingId);
        if (listing == null) {
            result.put("success", false);
            result.put("message", "该挂单不存在或已被购买");
            return result;
        }
        if (listing.sellerPlayerId != playerId) {
            result.put("success", false);
            result.put("message", "这不是你的挂单");
            return result;
        }

        removeListing(listingId);
        itemService.addItem(playerId, listing.itemKey, listing.quantity);

        Item item = ItemRegistry.get(listing.itemKey);
        result.put("success", true);
        result.put("message", "撤回了坊市挂单【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity + "（坊市抽成不退还）");
        return result;
    }

    public List<TradeListing> getActiveListings() {
        String sql = "SELECT * FROM trade_listings WHERE status = 'active' ORDER BY created_at DESC";
        List<TradeListing> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapListing(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取坊市列表失败", e);
        }
        return result;
    }

    public List<TradeListing> getPlayerListings(long playerId) {
        String sql = "SELECT * FROM trade_listings WHERE seller_player_id = ? AND status = 'active'";
        List<TradeListing> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapListing(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取个人挂单失败", e);
        }
        return result;
    }

    private TradeListing getListing(long listingId) {
        String sql = "SELECT * FROM trade_listings WHERE id = ? AND status = 'active'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapListing(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询挂单失败", e);
        }
        return null;
    }

    private void removeListing(long listingId) {
        String sql = "UPDATE trade_listings SET status = 'sold' WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("移除挂单失败", e);
        }
    }

    private TradeListing mapListing(ResultSet rs) throws SQLException {
        TradeListing l = new TradeListing();
        l.id = rs.getLong("id");
        l.sellerPlayerId = rs.getLong("seller_player_id");
        l.itemKey = rs.getString("item_key");
        l.quantity = rs.getInt("quantity");
        l.priceSpiritStones = rs.getLong("price_spirit_stones");
        l.fee = rs.getLong("fee");
        l.createdAt = rs.getString("created_at");
        return l;
    }

    public static class TradeListing {
        public long id;
        public long sellerPlayerId;
        public String itemKey;
        public int quantity;
        public long priceSpiritStones;
        public long fee;
        public String createdAt;
    }
}
