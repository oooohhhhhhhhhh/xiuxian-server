package com.mtxgdn.game.service;

import com.mtxgdn.db.ChatDAO;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.util.TextSanitizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ChatService {

    private static final int MAX_WORLD_HISTORY = 100;
    private static final int MAX_PRIVATE_HISTORY = 50;
    private static final int MAX_CONTENT_LENGTH = 500;

    /** {@code chat_messages} 的读写入口（无状态，可共享）。 */
    private static final ChatDAO CHAT_DAO = new ChatDAO();

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

        CHAT_DAO.insertWorldMessage(senderPlayerId, content);

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

        CHAT_DAO.insertPrivateMessage(senderPlayerId, receiverPlayerId, content);

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
        return CHAT_DAO.findWorldMessages(limit);
    }

    public List<ChatMessage> getPrivateMessages(long playerId1, long playerId2, int limit) {
        if (limit <= 0 || limit > MAX_PRIVATE_HISTORY) {
            limit = MAX_PRIVATE_HISTORY;
        }
        return CHAT_DAO.findPrivateMessages(playerId1, playerId2, limit);
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
