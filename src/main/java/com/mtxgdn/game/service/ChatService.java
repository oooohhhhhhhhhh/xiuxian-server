package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.util.TextSanitizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChatService {

    private static final int MAX_WORLD_HISTORY = 100;
    private static final int MAX_PRIVATE_HISTORY = 50;
    private static final int MAX_CONTENT_LENGTH = 500;

    public ChatMessage sendWorldMessage(long senderPlayerId, String senderName, String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        // 世界频道限流：每玩家 30 秒最多 5 条
        if (!com.mtxgdn.util.RateLimiter.allow("chat_world:" + senderPlayerId, 5, 30)) {
            throw new RuntimeException("发言过于频繁，请稍后再试");
        }
        content = TextSanitizer.sanitizeChatContent(content.trim());
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

        String sql = "INSERT INTO chat_messages (channel, sender_player_id, content) VALUES ('world', ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, senderPlayerId);
            ps.setString(2, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("发送世界消息失败", e);
        }

        ChatMessage msg = new ChatMessage();
        msg.setChannel("world");
        msg.setSenderPlayerId(senderPlayerId);
        msg.setSenderName(senderName);
        msg.setContent(content);
        msg.setCreatedAt(String.valueOf(System.currentTimeMillis()));
        return msg;
    }

    public ChatMessage sendPrivateMessage(long senderPlayerId, String senderName, long receiverPlayerId, String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        // 私聊限流：每玩家 30 秒最多 10 条
        if (!com.mtxgdn.util.RateLimiter.allow("chat_private:" + senderPlayerId, 10, 30)) {
            throw new RuntimeException("发言过于频繁，请稍后再试");
        }
        content = TextSanitizer.sanitizeChatContent(content.trim());
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }

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

        ChatMessage msg = new ChatMessage();
        msg.setChannel("private");
        msg.setSenderPlayerId(senderPlayerId);
        msg.setSenderName(senderName);
        msg.setReceiverPlayerId(receiverPlayerId);
        msg.setContent(content);
        msg.setCreatedAt(String.valueOf(System.currentTimeMillis()));
        return msg;
    }

    public List<ChatMessage> getWorldMessages(int limit) {
        if (limit <= 0 || limit > MAX_WORLD_HISTORY) {
            limit = 50;
        }

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

    public List<ChatMessage> getPrivateMessages(long playerId1, long playerId2, int limit) {
        if (limit <= 0 || limit > MAX_PRIVATE_HISTORY) {
            limit = MAX_PRIVATE_HISTORY;
        }

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

    public void setSenderNames(List<ChatMessage> messages, PlayerService playerService) {
        Map<Long, String> nameCache = new HashMap<>();
        List<Long> ids = messages.stream()
                .map(ChatMessage::getSenderPlayerId)
                .collect(Collectors.toList());
        for (ChatMessage msg : messages) {
            if (msg.getReceiverPlayerId() != null) {
                ids.add(msg.getReceiverPlayerId());
            }
        }

        for (Long id : ids) {
            if (!nameCache.containsKey(id)) {
                var player = playerService.getPlayerById(id);
                nameCache.put(id, player != null ? player.getName() : null);
            }
        }

        for (ChatMessage msg : messages) {
            msg.setSenderName(nameCache.getOrDefault(msg.getSenderPlayerId(), "未知"));
            if (msg.getReceiverPlayerId() != null) {
                msg.setReceiverName(nameCache.getOrDefault(msg.getReceiverPlayerId(), "未知"));
            }
        }
    }
}
