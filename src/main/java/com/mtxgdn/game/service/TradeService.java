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

    /**
     * 上架物品。使用事务确保物品扣除与挂单创建原子性。
     */
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
        long fee;
        long baseRate = (long)(priceSpiritStones * TRADE_FEE_RATE);

        Player seller = playerService.getPlayerById(playerId);
        boolean halfFee = seller != null && seller.getSpiritualRoot() != null
                && seller.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.TRADE_FEE_HALF);

        if (halfFee) {
            fee = Math.max(1, baseRate / 2);
        } else {
            fee = Math.max(1, baseRate);
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                if (!itemService.removeItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("物品扣除失败: " + fullKey);
                }

                String sql = "INSERT INTO trade_listings (seller_player_id, item_key, quantity, price_spirit_stones, fee) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, fullKey);
                    ps.setInt(3, quantity);
                    ps.setLong(4, priceSpiritStones);
                    ps.setLong(5, fee);
                    ps.executeUpdate();
                }
                return null;
            });

            result.put("success", true);
            result.put("message", "已将【" + item.getName() + " (" + fullKey + ")】×" + quantity + " 挂入坊市，售价 " + priceSpiritStones + " 灵石（坊市抽成 " + fee + " 灵石）");
            result.put("fee", fee);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上架失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 购买物品。使用事务确保灵石转移与物品转移原子性。
     */
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

        try {
            DatabaseManager.runTransaction(conn -> {
                // 先标记挂单为已售（防止并发重复购买）
                int removed = removeListing(conn, listingId);
                if (removed == 0) {
                    throw new SQLException("挂单已失效");
                }

                // 灵石转移
                if (!itemService.removeItem(conn, buyerPlayerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, totalCost)) {
                    throw new SQLException("灵石扣除失败");
                }
                itemService.addItem(conn, buyerPlayerId, listing.itemKey, listing.quantity);

                long sellerReceive = totalCost - listing.fee;
                if (!itemService.addSpiritStones(conn, listing.sellerPlayerId, sellerReceive)) {
                    throw new SQLException("发放灵石失败");
                }
                return null;
            });

            Item item = ItemRegistry.get(listing.itemKey);
            result.put("success", true);
            result.put("message", "在坊市中购得【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity
                    + "，花费 " + totalCost + " 灵石（坊主抽成 " + listing.fee + " 灵石）");
            result.put("cost", totalCost);
            result.put("fee", listing.fee);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "购买失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 撤回挂单。使用事务确保挂单删除与物品返还原子性。
     */
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

        try {
            DatabaseManager.runTransaction(conn -> {
                int removed = removeListing(conn, listingId);
                if (removed == 0) {
                    throw new SQLException("挂单已失效");
                }
                itemService.addItem(conn, playerId, listing.itemKey, listing.quantity);
                return null;
            });

            Item item = ItemRegistry.get(listing.itemKey);
            result.put("success", true);
            result.put("message", "撤回了坊市挂单【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity + "（坊市抽成不退还）");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "撤回失败: " + e.getMessage());
        }
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

    /**
     * 原子标记挂单状态，返回受影响行数（0 表示已被其他人购买）。
     */
    private int removeListing(Connection conn, long listingId) throws SQLException {
        String sql = "UPDATE trade_listings SET status = 'sold' WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return ps.executeUpdate();
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
