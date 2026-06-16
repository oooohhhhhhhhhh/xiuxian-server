package com.mtxgdn.game.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.RedeemCode;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.common.service.ServiceRegistry;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RedeemCodeService {

    private static final Gson gson = new Gson();
    private static final Type ITEMS_MAP_TYPE = new TypeToken<Map<String, Integer>>() {}.getType();
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    // ==================== Create ====================

    public RedeemCode createCode(String code, String name, Map<String, Integer> items,
                                  long gold, long spiritStones, long exp,
                                  int maxUses, String expiresAt, String createdBy) {
        String itemsJson = items != null ? gson.toJson(items) : null;
        String sql = "INSERT INTO redeem_codes (code, name, items_json, gold, spirit_stones, exp, " +
                "max_uses, current_uses, status, expires_at, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'active', ?, ?)";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code.toUpperCase());
            ps.setString(2, name);
            ps.setString(3, itemsJson);
            ps.setLong(4, gold);
            ps.setLong(5, spiritStones);
            ps.setLong(6, exp);
            ps.setInt(7, maxUses);
            if (expiresAt != null && !expiresAt.isEmpty()) {
                ps.setString(8, expiresAt);
            } else {
                ps.setNull(8, Types.TIMESTAMP);
            }
            ps.setString(9, createdBy);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return findById(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建兑换码失败", e);
        }
        return null;
    }

    // ==================== Query ====================

    public RedeemCode findByCode(String code) {
        String sql = "SELECT * FROM redeem_codes WHERE code = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRedeemCode(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询兑换码失败", e);
        }
        return null;
    }

    public RedeemCode findById(long id) {
        String sql = "SELECT * FROM redeem_codes WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRedeemCode(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询兑换码失败", e);
        }
        return null;
    }

    public List<RedeemCode> listAll() {
        List<RedeemCode> list = new ArrayList<>();
        String sql = "SELECT * FROM redeem_codes ORDER BY created_at DESC";
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRedeemCode(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询兑换码列表失败", e);
        }
        return list;
    }

    // ==================== Update ====================

    public boolean updateCode(long id, String name, Map<String, Integer> items,
                               long gold, long spiritStones, long exp,
                               int maxUses, String expiresAt) {
        String itemsJson = items != null ? gson.toJson(items) : null;
        String sql = "UPDATE redeem_codes SET name = ?, items_json = ?, gold = ?, spirit_stones = ?, " +
                "exp = ?, max_uses = ?, expires_at = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, itemsJson);
            ps.setLong(3, gold);
            ps.setLong(4, spiritStones);
            ps.setLong(5, exp);
            ps.setInt(6, maxUses);
            if (expiresAt != null && !expiresAt.isEmpty()) {
                ps.setString(7, expiresAt);
            } else {
                ps.setNull(7, Types.TIMESTAMP);
            }
            ps.setLong(8, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("更新兑换码失败", e);
        }
    }

    public boolean deleteCode(long id) {
        String sql = "DELETE FROM redeem_codes WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("删除兑换码失败", e);
        }
    }

    // ==================== Redeem ====================

    private String redeem(Connection conn, long playerId, long codeId) throws SQLException {
        String sql = "INSERT INTO redeemed_codes (code_id, player_id) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
            return null;
        } catch (SQLException e) {
            if (isDuplicateKeyError(e)) {
                return "你已经兑换过此码了";
            }
            throw e;
        }
    }

    private boolean hasRedeemed(Connection conn, long playerId, long codeId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM redeemed_codes WHERE code_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private boolean incrementUses(Connection conn, long codeId, int maxUses) throws SQLException {
        String sql = "UPDATE redeem_codes SET current_uses = current_uses + 1, " +
                "status = CASE WHEN current_uses + 1 >= ? AND ? > 0 THEN 'redeemed' ELSE status END " +
                "WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxUses);
            ps.setInt(2, maxUses);
            ps.setLong(3, codeId);
            return ps.executeUpdate() > 0;
        }
    }

    // ==================== Redeem Action ====================

    /**
     * 执行兑换操作，返回结果描述。
     * 返回 null 表示兑换成功，返回字符串表示失败原因。
     */
    public String doRedeem(String codeStr, long playerId) {
        if (codeStr == null || codeStr.isBlank()) {
            return "请输入兑换码";
        }

        RedeemCode rc = findByCode(codeStr.trim());
        if (rc == null) {
            return "兑换码不存在";
        }

        // 系统控制状态判断
        if (rc.isExpired()) {
            return "此兑换码已过期";
        }
        if (rc.isRedeemed()) {
            return "此兑换码已被兑换完了";
        }
        if (!rc.isActive()) {
            return "此兑换码已失效";
        }

        if (rc.getExpiresAt() != null && !rc.getExpiresAt().isEmpty()) {
            try {
                Timestamp expiry = Timestamp.valueOf(rc.getExpiresAt());
                if (System.currentTimeMillis() > expiry.getTime()) {
                    // 自动标记为已过期
                    setStatus(rc.getId(), RedeemCode.STATUS_EXPIRED);
                    return "此兑换码已过期";
                }
            } catch (Exception ignored) {
            }
        }

        // 执行事务
        return DatabaseManager.runTransaction(conn -> {
            // 重复检查
            if (hasRedeemed(conn, playerId, rc.getId())) {
                return "你已经兑换过此码了";
            }

            // 记录兑换
            String err = redeem(conn, playerId, rc.getId());
            if (err != null) return err;

            // 增加使用次数
            if (!incrementUses(conn, rc.getId(), rc.getMaxUses())) {
                return "更新兑换码状态失败";
            }

            // 发放奖励
            var p = playerService.getPlayerById(playerId);
            if (p == null) return "玩家不存在";

            StringBuilder result = new StringBuilder();
            int count = 0;

            if (rc.getGold() > 0) {
                playerService.addGold(playerId, rc.getGold());
                result.append("金币+" + rc.getGold());
                count++;
            }
            if (rc.getSpiritStones() > 0) {
                itemService.addSpiritStones(playerId, rc.getSpiritStones());
                if (count > 0) result.append(", ");
                result.append("灵石+" + rc.getSpiritStones());
                count++;
            }
            if (rc.getExp() > 0) {
                playerService.addExperience(playerId, rc.getExp());
                if (count > 0) result.append(", ");
                result.append("灵力+" + rc.getExp());
                count++;
            }

            Map<String, Integer> items = rc.getItems();
            if (items != null) {
                for (Map.Entry<String, Integer> entry : items.entrySet()) {
                    String itemKey = entry.getKey();
                    int qty = entry.getValue();
                    if (!ItemRegistry.contains(itemKey)) {
                        continue;
                    }
                    itemService.addItem(playerId, itemKey, qty);
                    if (count > 0) result.append(", ");
                    String itemName = com.mtxgdn.util.LangManager.get("item." + itemKey + ".name");
                    result.append(itemName + "x" + qty);
                    count++;
                }
            }

            if (count == 0) {
                return "兑换码配置为空";
            }

            return "SUCCESS:" + result.toString();
        });
    }

    // ==================== Helpers ====================

    private void setStatus(long codeId, String status) {
        String sql = "UPDATE redeem_codes SET status = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, codeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新兑换码状态失败", e);
        }
    }

    private RedeemCode mapRedeemCode(ResultSet rs) throws SQLException {
        RedeemCode rc = new RedeemCode();
        rc.setId(rs.getLong("id"));
        rc.setCode(rs.getString("code"));
        rc.setName(rs.getString("name"));
        rc.setItemsJson(rs.getString("items_json"));
        rc.setGold(rs.getLong("gold"));
        rc.setSpiritStones(rs.getLong("spirit_stones"));
        rc.setExp(rs.getLong("exp"));
        rc.setMaxUses(rs.getInt("max_uses"));
        rc.setCurrentUses(rs.getInt("current_uses"));
        rc.setStatus(rs.getString("status"));
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        rc.setExpiresAt(expiresAt != null ? expiresAt.toString() : null);
        rc.setCreatedBy(rs.getString("created_by"));
        rc.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null);
        rc.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null);
        return rc;
    }

    private boolean isDuplicateKeyError(SQLException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("unique") || msg.contains("duplicate") || msg.contains("primary key");
    }
}
