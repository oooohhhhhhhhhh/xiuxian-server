package com.mtxgdn.db;

import com.mtxgdn.game.entity.Friend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 好友关系（{@code friends} 表）的读写入口。
 *
 * <p>从 {@code FriendService} 抽出来的第 7 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"获取好友列表失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么要 Connection 重载</b>：接受好友申请是"改申请状态 → 补一条反向记录"
 * 两步，原实现里这两步共用同一条连接（先在 {@code acceptRequest} 里 UPDATE，
 * 再把 {@code conn} 传给 {@code insertReverseRelationship}）。抽 DAO 之后如果
 * 让 {@code acceptRequest} 分别调两个自取连接的重载，两步就会落在不同连接上、
 * 各自自动提交 —— 中间任何一步失败都会留下"申请已接受但没有反向记录"的脏数据，
 * 对方的好友列表里看不到你。所以这里必须保留"同一条连接走完两步"。</p>
 *
 * <p>SQL 逐字搬自原 {@code FriendService}，包括 {@code OR} 拼的对称条件、
 * {@code INSERT OR REPLACE} / {@code ON DUPLICATE KEY UPDATE} 的方言分支，
 * 以及按 {@code ResultSetMetaData} 列数决定填哪些字段的写法。</p>
 */
public class FriendDAO {

    // ==================== 查询 ====================

    /** 双向查一次：返回 playerId 与 targetPlayerId 之间任一条关系的 status，无则 null。 */
    public String checkExistingRelationship(long playerId, long targetPlayerId) {
        String sql = "SELECT status FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.setLong(3, targetPlayerId);
            ps.setLong(4, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("检查好友关系失败", e);
        }
        return null;
    }

    public String checkExistingRelationship(Connection conn, long playerId, long targetPlayerId) throws SQLException {
        String sql = "SELECT status FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.setLong(3, targetPlayerId);
            ps.setLong(4, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("status");
                }
            }
        }
        return null;
    }

    /**
     * 好友列表。
     *
     * <p>原实现的去重在 Java 侧做：一条 {@code friends} 记录可能以
     * {@code player_id = 我} 或 {@code friend_player_id = 我} 出现，
     * 所以查出来后要按"对方 id"去重，并把 {@code friendPlayerId} 归一成对方。</p>
     */
    public List<Friend> findFriends(long playerId) {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at, f.updated_at FROM friends f " +
                "WHERE ((f.player_id = ?) OR (f.friend_player_id = ? AND f.status = 'accepted')) AND f.status = 'accepted'";
        List<Friend> friends = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Friend f = mapFriend(rs);
                    long friendId = (f.getPlayerId() == playerId) ? f.getFriendPlayerId() : f.getPlayerId();
                    if (f.getFriendPlayerId() != friendId) {
                        f.setFriendPlayerId(friendId);
                    }
                    friends.add(f);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取好友列表失败", e);
        }
        return friends;
    }

    public List<Friend> findFriends(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at, f.updated_at FROM friends f " +
                "WHERE ((f.player_id = ?) OR (f.friend_player_id = ? AND f.status = 'accepted')) AND f.status = 'accepted'";
        List<Friend> friends = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Friend f = mapFriend(rs);
                    long friendId = (f.getPlayerId() == playerId) ? f.getFriendPlayerId() : f.getPlayerId();
                    if (f.getFriendPlayerId() != friendId) {
                        f.setFriendPlayerId(friendId);
                    }
                    friends.add(f);
                }
            }
        }
        return friends;
    }

    /**
     * 待处理的好友申请（别人发给我的）。
     *
     * <p>这里只 SELECT 了 5 列（没有 {@code updated_at}），
     * {@link #mapFriend} 按实际列数填字段，不会去读不存在的列。</p>
     */
    public List<Friend> findPendingRequests(long playerId) {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at FROM friends f " +
                "WHERE f.friend_player_id = ? AND f.status = 'pending'";
        List<Friend> requests = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    requests.add(mapFriend(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取好友申请失败", e);
        }
        return requests;
    }

    public List<Friend> findPendingRequests(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT f.id, f.player_id, f.friend_player_id, f.status, f.created_at FROM friends f " +
                "WHERE f.friend_player_id = ? AND f.status = 'pending'";
        List<Friend> requests = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    requests.add(mapFriend(rs));
                }
            }
        }
        return requests;
    }

    // ==================== 写操作 ====================

    /** 发一条 pending 申请。 */
    public void insertRequest(long playerId, long targetPlayerId) {
        String sql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'pending')";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发送好友申请失败", e);
        }
    }

    public void insertRequest(Connection conn, long playerId, long targetPlayerId) throws SQLException {
        String sql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'pending')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, targetPlayerId);
            ps.executeUpdate();
        }
    }

    /**
     * 接受申请：把申请人 → 我的那条 pending 改成 accepted。
     *
     * @return 受影响行数，0 表示申请不存在或已被处理
     */
    public int acceptRequest(long playerId, long requesterPlayerId) {
        String sql = "UPDATE friends SET status = 'accepted', updated_at = CURRENT_TIMESTAMP WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("接受好友申请失败", e);
        }
    }

    public int acceptRequest(Connection conn, long playerId, long requesterPlayerId) throws SQLException {
        String sql = "UPDATE friends SET status = 'accepted', updated_at = CURRENT_TIMESTAMP WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            return ps.executeUpdate();
        }
    }

    /** 拒绝申请：删掉申请人 → 我的那条 pending。 */
    public boolean rejectRequest(long playerId, long requesterPlayerId) {
        String sql = "DELETE FROM friends WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("拒绝好友申请失败", e);
        }
    }

    public boolean rejectRequest(Connection conn, long playerId, long requesterPlayerId) throws SQLException {
        String sql = "DELETE FROM friends WHERE player_id = ? AND friend_player_id = ? AND status = 'pending'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, requesterPlayerId);
            ps.setLong(2, playerId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 删好友：双向都删。 */
    public boolean removeFriend(long playerId, long friendPlayerId) {
        String sql = "DELETE FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, friendPlayerId);
            ps.setLong(3, friendPlayerId);
            ps.setLong(4, playerId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("删除好友失败", e);
        }
    }

    public boolean removeFriend(Connection conn, long playerId, long friendPlayerId) throws SQLException {
        String sql = "DELETE FROM friends WHERE (player_id = ? AND friend_player_id = ?) OR (player_id = ? AND friend_player_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, friendPlayerId);
            ps.setLong(3, friendPlayerId);
            ps.setLong(4, playerId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 补一条反向的好友记录（我 → 对方）。
     *
     * <p>两个方言分支：SQLite 用 {@code INSERT OR REPLACE}，
     * MySQL 用 {@code ... ON DUPLICATE KEY UPDATE}。两边都不能用
     * 普通的 {@code INSERT} —— 重复接受同一条申请时会撞主键。</p>
     *
     * <p><b>必须由调用方传入 Connection</b>：这个方法只该出现在
     * {@code acceptRequest} 改完申请状态之后，两步必须共用同一条连接。</p>
     */
    public void insertReverseRelationship(Connection conn, long playerId, long friendPlayerId) throws SQLException {
        String reverseSql;
        if (DatabaseManager.isSqlite()) {
            reverseSql = "INSERT OR REPLACE INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted')";
        } else {
            reverseSql = "INSERT INTO friends (player_id, friend_player_id, status) VALUES (?, ?, 'accepted') ON DUPLICATE KEY UPDATE status = 'accepted'";
        }
        try (PreparedStatement ps = conn.prepareStatement(reverseSql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, friendPlayerId);
            ps.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    /**
     * 按实际列数填字段。
     *
     * <p>{@code findFriends} SELECT 了 6 列（含 {@code created_at} / {@code updated_at}），
     * {@code findPendingRequests} 只 SELECT 了 5 列（没有 {@code updated_at}），
     * 所以先问 {@code ResultSetMetaData} 再读，避免"无效的列名"。</p>
     */
    private Friend mapFriend(ResultSet rs) throws SQLException {
        Friend f = new Friend();
        f.setId(rs.getLong("id"));
        f.setPlayerId(rs.getLong("player_id"));
        f.setFriendPlayerId(rs.getLong("friend_player_id"));
        f.setStatus(rs.getString("status"));
        if (rs.getMetaData().getColumnCount() > 4) {
            f.setCreatedAt(rs.getString("created_at"));
        }
        if (rs.getMetaData().getColumnCount() > 5) {
            f.setUpdatedAt(rs.getString("updated_at"));
        }
        return f;
    }
}
