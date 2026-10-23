package com.mtxgdn.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.actionLog;
import static com.mtxgdn.websocket.WsSupport.chatService;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 聊天域处理器：世界聊天、私聊、世界频道历史。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code chat} / {@code chat_private} /
 * {@code chat_history} 三个 action 的处理函数（{@code handleChat}、
 * {@code handlePrivateChat}、{@code handleChatHistory}）整块搬到这里。</p>
 *
 * <p><b>为什么单独成域</b>：这三个 action 是唯一会<b>把内容推给别人</b>的动作
 * （{@code broadcast} 给全体在线 / {@code sendToUser} 给单个玩家），依赖集合也只有
 * {@code chatService} + {@code playerService} + 行为日志，与其它域没有交集；
 * 它们还共同承载一条安全约束 —— 对外发送的内容必须取 {@code ChatService}
 * 清洗+截断后的那份，不能用客户端原始字符串。放在同一个类里，这条约束的
 * 三处落点一眼可见。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级，
 * 因为分发入口在 {@code GameWebSocketApp}）。响应 type 字段、错误码、
 * 广播/回执的消息结构、以及"先广播再回执"的顺序都没有变。
 * 权限检查（{@code game.chat.world} / {@code game.chat.private}）仍留在
 * {@code GameWebSocketApp} 的分发 switch 里，不在本类。</p>
 */
final class WsChatHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("chat", "chat_private", "chat_history");

    WsChatHandler(WsContext context) {
        super(context);
    }

    void handleChat(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null) return;
        String content = data.has("content") ? data.get("content").getAsString() : "";
        if (content.isEmpty()) {
            socket.send(GameMessage.error(msgId, "chat", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "chat", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        String name = player.getName();

        ChatMessage msg = chatService.sendWorldMessage(player.getId(), name, content);
        if (msg == null) {
            socket.send(GameMessage.error(msgId, "chat", GameErrorCode.PARAM_INVALID).toJson());
            return;
        }

        // 广播/回执必须用 ChatService 处理过的那份内容，不能用客户端原始字符串。
        // ChatService 会做 TextSanitizer 清洗并截断到 500 字，而原来这里把原始 content
        // 原样广播给所有在线玩家：
        //   1) 清洗白做了 —— 客户端收到的是未清洗的原文；
        //   2) 长度没上限 —— 一条 10MB 的发言会被复制给每个在线连接，
        //      一次请求即可放大成 N 份，典型的放大式 DoS。
        String safeContent = msg.getContent();

        JsonObject chatData = new JsonObject();
        chatData.addProperty("senderPlayerId", player.getId());
        chatData.addProperty("senderName", name);
        chatData.addProperty("content", safeContent);
        chatData.addProperty("timestamp", System.currentTimeMillis());

        GameMessage chatMessage = GameMessage.ok(0, "chat", chatData);
        broadcast(chatMessage);

        socket.send(GameMessage.ok(msgId, "chat", "发送成功", chatData).toJson());
        actionLog.logChat(userId, name, safeContent);
    }

    void handlePrivateChat(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null) return;
        String content = data.has("content") ? data.get("content").getAsString() : "";
        long targetId = data.has("targetPlayerId") ? data.get("targetPlayerId").getAsLong() : 0;

        if (targetId == 0 || content.trim().isEmpty()) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (targetId == player.getId()) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.CHAT_SELF_MESSAGE).toJson());
            return;
        }

        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.CHAT_RECEIVER_NOT_FOUND).toJson());
            return;
        }

        // 同世界频道：转发给收件人的内容必须是 ChatService 清洗+截断后的那份，
        // 否则未清洗原文会直接落到对方客户端，且长度不受限（还会写进操作日志）。
        ChatMessage sent = chatService.sendPrivateMessage(player.getId(), player.getName(), targetId, content);
        String safeContent = sent != null ? sent.getContent() : content;
        actionLog.logChat(userId, player.getName(), "[私聊→" + target.getName() + "] " + safeContent);

        JsonObject chatData = new JsonObject();
        chatData.addProperty("fromUserId", userId);
        chatData.addProperty("senderPlayerId", player.getId());
        chatData.addProperty("senderName", player.getName());
        chatData.addProperty("receiverPlayerId", targetId);
        chatData.addProperty("receiverName", target.getName());
        chatData.addProperty("content", safeContent);
        chatData.addProperty("timestamp", System.currentTimeMillis());

        GameMessage chatMessage = GameMessage.ok(0, "chat_private", chatData);
        sendToUser((long) target.getUserId(), chatMessage);

        socket.send(GameMessage.ok(msgId, "chat_private", "发送成功", chatData).toJson());
    }

    void handleChatHistory(WebSocket socket, long msgId, JsonObject data) {
        int limit = data != null && data.has("limit") ? data.get("limit").getAsInt() : 50;
        List<ChatMessage> messages = chatService.getWorldMessages(limit);
        chatService.setSenderNames(messages, playerService);

        JsonArray arr = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("id", msg.getId());
            o.addProperty("senderPlayerId", msg.getSenderPlayerId());
            o.addProperty("senderName", msg.getSenderName() != null ? msg.getSenderName() : "未知");
            o.addProperty("content", msg.getContent());
            o.addProperty("createdAt", msg.getCreatedAt());
            arr.add(o);
        }
        JsonObject respData = new JsonObject();
        respData.add("messages", arr);
        socket.send(GameMessage.ok(msgId, "chat_history", respData).toJson());
    }
}
