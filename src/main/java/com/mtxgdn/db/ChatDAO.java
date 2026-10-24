package com.mtxgdn.db;

import com.mtxgdn.game.entity.ChatMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息（{@code chat_messages} 表）的读写入口。
 *
 * <p>从 {@code ChatService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"发送世界消息失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：聊天记录目前是"写一条读一批"的独立链路，
 * 没有跨表事务。但 {@code ChatService} 是插件通过
 * {@code PluginContext.getChatService()} 直接拿到手的服务，插件完全可能在自己的
 * {@code runTransaction} lambda 里调它（比如"发送系统公告同时写一张自定义表"）。
 * 抽 DAO 之后如果只留自取连接的重载，那种调用会在 SQLite 下撞
 * {@code SQLITE_BUSY_SNAPSHOT}，而且堆栈指向聊天写入，跟真正的事务毫无关系。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>两条查询都带 {@code LIMIT ?}：条数由调用方决定，DAO 不写死。</li>
 *   <li>世界频道只 SELECT 5 列（没有 {@code receiver_player_id}），
 *       私聊 SELECT 6 列。所以世界消息读出来后 {@code receiverPlayerId} 是 null，
 *       私聊消息才填 —— 下游 {@code setSenderNames} 正是靠
 *       {@code msg.getReceiverPlayerId() != null} 决定要不要补接收方名字的。</li>
 *   <li>插入语句里 {@code channel} 是<b>字面量</b>（{@code 'world'} / {@code 'private'}），
 *       不是绑定参数。逐字保留。</li>
 *   <li>{@code created_at} 由数据库默认值生成，插入时不显式写。</li>
 * </ul>
 */
public class ChatDAO {

    /** 写一条世界频道消息。 */
    public void insertWorldMessage(long senderPlayerId, String content) {
        String sql = "INSERT INTO chat_messages (channel, sender_player_id, content) VALUES ('world', ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderPlayerId);
            ps.setString(2, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发送世界消息失败", e);
        }
    }

    public void insertWorldMessage(Connection conn, long senderPlayerId, String content) throws SQLException {
        String sql = "INSERT INTO chat_messages (channel, sender_player_id, content) VALUES ('world', ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderPlayerId);
            ps.setString(2, content);
            ps.executeUpdate();
        }
    }

    /** 写一条私聊消息。 */
    public void insertPrivateMessage(long senderPlayerId, long receiverPlayerId, String content) {
        String sql = "INSERT INTO chat_messages (channel, sender_player_id, receiver_player_id, content) VALUES ('private', ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderPlayerId);
            ps.setLong(2, receiverPlayerId);
            ps.setString(3, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发送私聊消息失败", e);
        }
    }

    public void insertPrivateMessage(Connection conn, long senderPlayerId, long receiverPlayerId,
                                     String content) throws SQLException {
        String sql = "INSERT INTO chat_messages (channel, sender_player_id, receiver_player_id, content) VALUES ('private', ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderPlayerId);
            ps.setLong(2, receiverPlayerId);
            ps.setString(3, content);
            ps.executeUpdate();
        }
    }

    /**
     * 最近的世界频道消息（新的在前）。
     *
     * <p>只 SELECT 5 列，读出来的 {@code receiverPlayerId} 是 null ——
     * 这是世界频道与私聊在实体上的唯一区别。</p>
     */
    public List<ChatMessage> findWorldMessages(int limit) {
        String sql = "SELECT id, channel, sender_player_id, content, created_at FROM chat_messages WHERE channel = 'world' ORDER BY id DESC LIMIT ?";
        List<ChatMessage> messages = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(rs.getLong("id"));
                    msg.setChannel(rs.getString("channel"));
                    msg.setSenderPlayerId(rs.getLong("sender_player_id"));
                    msg.setContent(rs.getString("content"));
                    msg.setCreatedAt(rs.getString("created_at"));
                    messages.add(msg);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取世界消息失败", e);
        }
        return messages;
    }

    public List<ChatMessage> findWorldMessages(Connection conn, int limit) throws SQLException {
        String sql = "SELECT id, channel, sender_player_id, content, created_at FROM chat_messages WHERE channel = 'world' ORDER BY id DESC LIMIT ?";
        List<ChatMessage> messages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(rs.getLong("id"));
                    msg.setChannel(rs.getString("channel"));
                    msg.setSenderPlayerId(rs.getLong("sender_player_id"));
                    msg.setContent(rs.getString("content"));
                    msg.setCreatedAt(rs.getString("created_at"));
                    messages.add(msg);
                }
            }
        }
        return messages;
    }

    /**
     * 两个玩家之间的私聊记录（新的在前）。
     *
     * <p>条件是 {@code OR} 拼的对称式：{@code A→B} 或 {@code B→A} 都要查到。
     * SELECT 6 列（含 {@code receiver_player_id}）。</p>
     */
    public List<ChatMessage> findPrivateMessages(long playerId1, long playerId2, int limit) {
        String sql = "SELECT id, channel, sender_player_id, receiver_player_id, content, created_at FROM chat_messages " +
                "WHERE channel = 'private' AND ((sender_player_id = ? AND receiver_player_id = ?) OR (sender_player_id = ? AND receiver_player_id = ?)) " +
                "ORDER BY id DESC LIMIT ?";
        List<ChatMessage> messages = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId1);
            ps.setLong(2, playerId2);
            ps.setLong(3, playerId2);
            ps.setLong(4, playerId1);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(rs.getLong("id"));
                    msg.setChannel(rs.getString("channel"));
                    msg.setSenderPlayerId(rs.getLong("sender_player_id"));
                    msg.setReceiverPlayerId(rs.getLong("receiver_player_id"));
                    msg.setContent(rs.getString("content"));
                    msg.setCreatedAt(rs.getString("created_at"));
                    messages.add(msg);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取私聊消息失败", e);
        }
        return messages;
    }

    public List<ChatMessage> findPrivateMessages(Connection conn, long playerId1, long playerId2,
                                                 int limit) throws SQLException {
        String sql = "SELECT id, channel, sender_player_id, receiver_player_id, content, created_at FROM chat_messages " +
                "WHERE channel = 'private' AND ((sender_player_id = ? AND receiver_player_id = ?) OR (sender_player_id = ? AND receiver_player_id = ?)) " +
                "ORDER BY id DESC LIMIT ?";
        List<ChatMessage> messages = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId1);
            ps.setLong(2, playerId2);
            ps.setLong(3, playerId2);
            ps.setLong(4, playerId1);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage();
                    msg.setId(rs.getLong("id"));
                    msg.setChannel(rs.getString("channel"));
                    msg.setSenderPlayerId(rs.getLong("sender_player_id"));
                    msg.setReceiverPlayerId(rs.getLong("receiver_player_id"));
                    msg.setContent(rs.getString("content"));
                    msg.setCreatedAt(rs.getString("created_at"));
                    messages.add(msg);
                }
            }
        }
        return messages;
    }
}
