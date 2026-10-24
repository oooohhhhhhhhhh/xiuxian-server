package com.mtxgdn.db;

import com.mtxgdn.game.entity.RedeemCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 兑换码（{@code redeem_codes} / {@code redeemed_codes}）的读写入口。
 *
 * <p>从 {@code RedeemCodeService} 抽出来的第 8 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"创建兑换码失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ul>
 *
 * <p><b>兑换流程的四个步骤必须共用同一条连接</b>：查是否已兑换 → 插入兑换记录 →
 * 递增使用次数 → 发奖励。任何一步单独提交都会留下脏数据 —— 比如奖励发了但
 * 兑换记录没写上，同一个码就能反复刷。</p>
 */
public class RedeemCodeDAO {

    // ==================== Create ====================

    /**
     * 创建兑换码。
     *
     * @return 新建的 id；取不到自增主键时返回 -1
     */
    public long createCode(String code, String name, String itemsJson,
                           long gold, long spiritStones, long exp,
                           int maxUses, String expiresAt, String createdBy) {
        String sql = "INSERT INTO redeem_codes (code, name, items_json, gold, spirit_stones, exp, " +
                "max_uses, current_uses, status, expires_at, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'active', ?, ?)";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindCreate(ps, code, name, itemsJson, gold, spiritStones, exp, maxUses, expiresAt, createdBy);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("创建兑换码失败", e);
        }
        return -1;
    }

    public long createCode(Connection conn, String code, String name, String itemsJson,
                           long gold, long spiritStones, long exp,
                           int maxUses, String expiresAt, String createdBy) throws SQLException {
        String sql = "INSERT INTO redeem_codes (code, name, items_json, gold, spirit_stones, exp, " +
                "max_uses, current_uses, status, expires_at, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'active', ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindCreate(ps, code, name, itemsJson, gold, spiritStones, exp, maxUses, expiresAt, createdBy);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return -1;
    }

    private void bindCreate(PreparedStatement ps, String code, String name, String itemsJson,
                            long gold, long spiritStones, long exp,
                            int maxUses, String expiresAt, String createdBy) throws SQLException {
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

    public RedeemCode findByCode(Connection conn, String code) throws SQLException {
        String sql = "SELECT * FROM redeem_codes WHERE code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRedeemCode(rs);
                }
            }
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

    public RedeemCode findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT * FROM redeem_codes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRedeemCode(rs);
                }
            }
        }
        return null;
    }

    /** 全部兑换码，按创建时间倒序。 */
    public List<RedeemCode> listAll() {
        String sql = "SELECT * FROM redeem_codes ORDER BY created_at DESC";
        List<RedeemCode> list = new ArrayList<>();
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

    public List<RedeemCode> listAll(Connection conn) throws SQLException {
        String sql = "SELECT * FROM redeem_codes ORDER BY created_at DESC";
        List<RedeemCode> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRedeemCode(rs));
            }
        }
        return list;
    }

    // ==================== Update ====================

    public boolean updateCode(long id, String name, String itemsJson,
                              long gold, long spiritStones, long exp,
                              int maxUses, String expiresAt) {
        String sql = "UPDATE redeem_codes SET name = ?, items_json = ?, gold = ?, spirit_stones = ?, " +
                "exp = ?, max_uses = ?, expires_at = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindUpdate(ps, id, name, itemsJson, gold, spiritStones, exp, maxUses, expiresAt);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("更新兑换码失败", e);
        }
    }

    public boolean updateCode(Connection conn, long id, String name, String itemsJson,
                              long gold, long spiritStones, long exp,
                              int maxUses, String expiresAt) throws SQLException {
        String sql = "UPDATE redeem_codes SET name = ?, items_json = ?, gold = ?, spirit_stones = ?, " +
                "exp = ?, max_uses = ?, expires_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindUpdate(ps, id, name, itemsJson, gold, spiritStones, exp, maxUses, expiresAt);
            return ps.executeUpdate() > 0;
        }
    }

    private void bindUpdate(PreparedStatement ps, long id, String name, String itemsJson,
                            long gold, long spiritStones, long exp,
                            int maxUses, String expiresAt) throws SQLException {
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

    public boolean deleteCode(Connection conn, long id) throws SQLException {
        String sql = "DELETE FROM redeem_codes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public void updateStatus(long codeId, String status) {
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

    public void updateStatus(Connection conn, long codeId, String status) throws SQLException {
        String sql = "UPDATE redeem_codes SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, codeId);
            ps.executeUpdate();
        }
    }

    // ==================== 兑换流程（事务内一律用 Connection 重载） ====================

    /**
     * 插入兑换记录。
     *
     * @return 成功返回 {@code null}；撞主键（已兑换过）返回错误文案
     */
    public String insertRedemption(Connection conn, long playerId, long codeId) throws SQLException {
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

    /** 该玩家是否已兑换过这个码。 */
    public boolean hasRedeemed(Connection conn, long playerId, long codeId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM redeemed_codes WHERE code_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, codeId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 递增使用次数，用满时把状态改成 {@code redeemed}。
     *
     * <p>{@code maxUses <= 0} 表示不限次，此时 {@code CASE WHEN} 的第二个条件不成立，
     * 状态保持原值 —— 这是原实现的语义，不能"顺手"改成 {@code maxUses > 0} 就翻转。</p>
     *
     * @return 是否更新到行
     */
    public boolean incrementUses(Connection conn, long codeId, int maxUses) throws SQLException {
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

    // ==================== ResultSet → 实体 ====================

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

    // ==================== 内部工具 ====================

    /**
     * 判断是否主键/唯一键冲突。
     *
     * <p>靠错误文案匹配而不是 SQLState：SQLite 和 MySQL 报的 SQLState 不一样，
     * 但文案里都带 {@code unique}/{@code duplicate}/{@code primary key}。</p>
     */
    static boolean isDuplicateKeyError(SQLException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("unique") || msg.contains("duplicate") || msg.contains("primary key");
    }
}
