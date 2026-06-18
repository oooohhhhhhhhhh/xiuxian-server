package com.mtxgdn.onebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.onebot.command.OneBotCommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.OneBotLogger;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OneBotWebSocketServer extends WebSocketApplication
        implements OneBotMessageSender, OneBotAccountFlow {

    private static final Gson gson = new Gson();
    private static final GameLogger log = GameLogger.getLogger("OneBotServer");
    private static final OneBotLogger botLog = OneBotLogger.getInstance();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    private final QqBindingService bindingService = new QqBindingService();
    private final Map<WebSocket, String> sessionBots = new ConcurrentHashMap<>();
    private final Map<String, WebSocket> botSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    private static class PendingSession {
        Long sourceGroupId;
        String type;
        String state;
        String username;

        PendingSession(String type, Long sourceGroupId) {
            this.type = type;
            this.sourceGroupId = sourceGroupId;
            this.state = "WAITING_PASSWORD";
        }

        PendingSession() {
            this.type = "bind";
            this.state = "WAITING_USERNAME";
        }
    }

    @Override
    public void onConnect(WebSocket socket) {
        botLog.logSystem("OneBot 客户端已连接");
        log.info("OneBot 客户端已连接");
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
        botLog.logRecv(text);
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            if (json.has("post_type")) {
                handleEvent(socket, json);
            } else if (json.has("echo") && json.has("status")) {
                handleApiResponse(socket, json);
            } else if (json.has("meta_event_type")) {
                handleMetaEvent(socket, json);
            } else {
                log.warn("未知消息类型");
            }
        } catch (Exception e) {
            log.error("处理消息异常: " + e.getMessage(), e);
        }
    }

    @Override
    public void onClose(WebSocket socket, DataFrame frame) {
        String selfId = sessionBots.remove(socket);
        if (selfId != null) {
            botSessions.remove(selfId);
            botLog.logSystem("OneBot 客户端已断开: " + selfId);
            log.info("OneBot 客户端已断开: " + selfId);
        }
    }

    private void handleMetaEvent(WebSocket socket, JsonObject json) {
        String metaType = json.get("meta_event_type").getAsString();
        if ("lifecycle".equals(metaType)) {
            String selfId = json.has("self_id") ? json.get("self_id").getAsString() : "unknown";
            sessionBots.put(socket, selfId);
            botSessions.put(selfId, socket);
            botLog.logSystem("OneBot Bot 已上线: " + selfId);
            log.info("OneBot Bot 已上线: " + selfId);
        }
    }

    private void handleEvent(WebSocket socket, JsonObject json) {
        String postType = json.get("post_type").getAsString();
        switch (postType) {
            case "message":
                handleMessageEvent(socket, json);
                break;
            case "notice":
            case "request":
            default:
                break;
        }
    }

    private void handleMessageEvent(WebSocket socket, JsonObject json) {
        String messageType = json.get("message_type").getAsString();
        String selfId = json.get("self_id").getAsString();
        String rawMessage = json.get("raw_message").getAsString();
        JsonObject sender = json.getAsJsonObject("sender");
        String senderQq = sender.get("user_id").getAsString();
        String senderNickname = sender.has("nickname") ? sender.get("nickname").getAsString() : "未知";

        if ("private".equals(messageType)) {
            handlePrivateMessage(socket, selfId, senderQq, senderNickname, rawMessage);
        } else if ("group".equals(messageType)) {
            Long groupId = json.get("group_id").getAsLong();
            handleGroupMessage(socket, selfId, groupId, senderQq, senderNickname, rawMessage);
        }
    }

    private void handlePrivateMessage(WebSocket socket, String selfId, String senderQq,
                                       String senderNickname, String rawMessage) {
        log.info("[私聊:" + senderQq + "] " + senderNickname + ": " + rawMessage);
        String trimmed = rawMessage.trim();

        PendingSession session = pendingSessions.get(senderQq);
        if (session != null) {
            handlePendingFlow(socket, selfId, senderQq, session, trimmed);
            return;
        }

        if (!isCommand(trimmed)) return;

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sendPrivateMsg(socket, selfId, senderQq, "操作太频繁，请稍后再试");
            return;
        }
        dispatchCommand(socket, selfId, senderQq, senderNickname, null, parsed[0], parsed[1]);
    }

    private void handleGroupMessage(WebSocket socket, String selfId, Long groupId,
                                     String senderQq, String senderNickname, String rawMessage) {
        log.info("[群聊:" + groupId + "] [QQ:" + senderQq + "] " + senderNickname + ": " + rawMessage);
        String trimmed = rawMessage.trim();
        if (!isCommand(trimmed)) return;

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            replyToSource(socket, selfId, senderQq, groupId, "操作太频繁，请稍后再试");
            return;
        }

        Command command = CommandRegistry.get(parsed[0]);
        if (command != null && command.isPrivateOnly()) {
            replyToSource(socket, selfId, senderQq, groupId, "请私聊机器人使用此指令，保护您的账户安全。");
            return;
        }
        dispatchCommand(socket, selfId, senderQq, senderNickname, groupId, parsed[0], parsed[1]);
    }

    private void dispatchCommand(WebSocket socket, String selfId, String senderQq,
                                  String senderNickname, Long groupId, String cmd, String arg) {
        Command command = CommandRegistry.get(cmd);
        if (command == null) {
            String msg = "未知指令，请输入 /help 查看可用指令";
            replyToSource(socket, selfId, senderQq, groupId, msg);
            return;
        }

        String permission = command.getPermission();
        if (permission != null) {
            QqBinding b = bindingService.findByQq(senderQq);
            if (b == null) {
                replyToSource(socket, selfId, senderQq, groupId,
                        "请先注册或绑定账号。\n注册: /register <角色名>\n绑定: /bind");
                return;
            }
            if (!PermissionService.hasPermission(b.getUserId(), permission)) {
                replyToSource(socket, selfId, senderQq, groupId, "权限不足，你无权使用此功能。");
                return;
            }
        }

        OneBotCommandContext ctx = new OneBotCommandContext(
                socket, selfId, senderQq, senderNickname, groupId, arg, this, this);
        command.execute(ctx);
    }

    private void handleApiResponse(WebSocket socket, JsonObject json) {
        String echo = json.has("echo") ? json.get("echo").getAsString() : "";
        String status = json.get("status").getAsString();
        if (!"ok".equals(status)) {
            log.warn("API 调用失败 [echo=" + echo + "]");
        }
    }

    // ==================== OneBotMessageSender ====================

    private static final String SEND_MODE = AppConfig.get("onebot.send_mode", "text");

    private boolean isChatRecordMode() {
        return "chat_record".equalsIgnoreCase(SEND_MODE);
    }

    private JsonArray buildForwardMessages(String name, String content) {
        JsonArray messages = new JsonArray();
        JsonObject node = new JsonObject();
        node.addProperty("type", "node");
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("content", content);
        node.add("data", data);
        messages.add(node);
        return messages;
    }

    @Override
    public void replyToSource(WebSocket socket, String selfId, String senderQq, Long groupId, String message) {
        if (groupId != null) {
            if (isChatRecordMode()) {
                sendGroupForwardMsg(socket, selfId, groupId, "系统消息", message);
            } else {
                sendGroupMsg(socket, selfId, groupId,
                        "[CQ:at,qq=" + senderQq + "]\n" + message);
            }
        } else {
            sendPrivateMsg(socket, selfId, senderQq, message);
        }
    }

    @Override
    public void sendPrivateMsg(WebSocket socket, String selfId, String targetQq, String message) {
        if (isChatRecordMode()) {
            sendPrivateForwardMsg(socket, selfId, targetQq, "系统消息", message);
            return;
        }
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_private_msg");
        JsonObject params = new JsonObject();
        params.addProperty("user_id", targetQq);
        params.addProperty("message", message);
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());
        String jsonStr = gson.toJson(api);
        botLog.logSend(targetQq, jsonStr);
        socket.send(jsonStr);
    }

    @Override
    public void sendGroupMsg(WebSocket socket, String selfId, Long groupId, String message) {
        if (isChatRecordMode()) {
            sendGroupForwardMsg(socket, selfId, groupId, "系统消息", message);
            return;
        }
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_group_msg");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("message", message);
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());
        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);
    }

    private void sendPrivateForwardMsg(WebSocket socket, String selfId, String targetQq,
                                       String senderName, String content) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_private_forward_msg");
        JsonObject params = new JsonObject();
        params.addProperty("user_id", targetQq);
        params.add("messages", buildForwardMessages(senderName, content));
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());
        String jsonStr = gson.toJson(api);
        botLog.logSend(targetQq, jsonStr);
        socket.send(jsonStr);
    }

    private void sendGroupForwardMsg(WebSocket socket, String selfId, Long groupId,
                                     String senderName, String content) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "send_group_forward_msg");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.add("messages", buildForwardMessages(senderName, content));
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());
        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);
    }

    // ==================== OneBotAccountFlow ====================

    @Override
    public void handleRegister(WebSocket socket, String selfId, String senderQq, String arg, Long sourceGroupId) {
        if (bindingService.findByQq(senderQq) != null) {
            replyToSource(socket, selfId, senderQq, sourceGroupId,
                    "你已注册角色，无需重复注册。如需重来请先用 /unbind 解绑。");
            return;
        }
        String name = arg.trim();
        if (name.isEmpty()) {
            replyToSource(socket, selfId, senderQq, sourceGroupId,
                    "用法: /register <角色名>\n密码将在下一步通过私聊安全发送");
            return;
        }
        String[] parts = name.split("\\s+", 2);
        name = parts[0].trim();
        if (name.length() > 16) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名不能超过16个字");
            return;
        }
        if (isUsernameExists(name)) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }
        PendingSession session = new PendingSession("register", sourceGroupId);
        session.username = name;
        pendingSessions.put(senderQq, session);
        replyToSource(socket, selfId, senderQq, sourceGroupId,
                "角色名【" + name + "】可用！\n请在私聊中发送你的密码（不少于6位）\n(输入 /cancel 取消)");
    }

    @Override
    public void handleBind(WebSocket socket, String selfId, String senderQq) {
        if (bindingService.findByQq(senderQq) != null) {
            QqBinding b = bindingService.findByQq(senderQq);
            sendPrivateMsg(socket, selfId, senderQq,
                    "你已绑定账号 (用户ID: " + b.getUserId() + ")，/unbind 可解绑。");
            return;
        }
        PendingSession session = new PendingSession();
        pendingSessions.put(senderQq, session);
        sendPrivateMsg(socket, selfId, senderQq,
                "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 /cancel 取消)");
    }

    @Override
    public void handleUnbind(WebSocket socket, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(socket, selfId, senderQq, "你尚未绑定账号。");
            return;
        }
        try {
            bindingService.unbindByQq(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "解绑成功！");
        } catch (RuntimeException e) {
            sendPrivateMsg(socket, selfId, senderQq, "解绑失败: " + e.getMessage());
        }
    }

    // ==================== Pending Flow ====================

    private void handlePendingFlow(WebSocket socket, String selfId, String senderQq,
                                    PendingSession session, String message) {
        if ("/cancel".equalsIgnoreCase(message.trim())) {
            pendingSessions.remove(senderQq);
            replyToSource(socket, selfId, senderQq, session.sourceGroupId, "已取消。");
            return;
        }
        switch (session.state) {
            case "WAITING_USERNAME":
                session.username = message.trim();
                session.state = "WAITING_PASSWORD";
                sendPrivateMsg(socket, selfId, senderQq, "请输入密码：\n(输入 /cancel 取消)");
                break;
            case "WAITING_PASSWORD":
                String password = message.trim();
                if (password.length() < 6) {
                    sendPrivateMsg(socket, selfId, senderQq, "密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if ("register".equals(session.type)) {
                    doRegisterComplete(socket, selfId, senderQq, session, password);
                } else {
                    doBindComplete(socket, selfId, senderQq, session, password);
                }
                pendingSessions.remove(senderQq);
                break;
        }
    }

    private void doRegisterComplete(WebSocket socket, String selfId, String senderQq,
                                     PendingSession session, String password) {
        if (isUsernameExists(session.username)) {
            replyToSource(socket, selfId, senderQq, session.sourceGroupId,
                    "角色名【" + session.username + "】已被占用，请重新注册。");
            return;
        }
        if (bindingService.findByQq(senderQq) != null) {
            replyToSource(socket, selfId, senderQq, session.sourceGroupId,
                    "你已注册角色，无需重复注册。");
            return;
        }
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            Long userId = insertUser(session.username, hashedPassword);
            if (userId == null) {
                replyToSource(socket, selfId, senderQq, session.sourceGroupId,
                        "注册失败，请稍后重试。");
                return;
            }
            PermissionService.assignDefaultRole(userId);
            bindingService.bind(senderQq, userId);
            PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(userId, session.username);
            String guideMsg = NewbieGuideService.getWelcomeMessage(player);
            replyToSource(socket, selfId, senderQq, session.sourceGroupId, guideMsg);
            actionLog.logCreatePlayer(userId, session.username);
        } catch (RuntimeException e) {
            replyToSource(socket, selfId, senderQq, session.sourceGroupId,
                    "注册失败: " + e.getMessage());
        }
    }

    private void doBindComplete(WebSocket socket, String selfId, String senderQq,
                                 PendingSession session, String password) {
        Long userId = verifyUserCredentials(session.username, password);
        if (userId == null) {
            sendPrivateMsg(socket, selfId, senderQq, "用户名或密码错误，请重新 /bind。");
            return;
        }
        try {
            bindingService.bind(senderQq, userId);
            sendPrivateMsg(socket, selfId, senderQq,
                    "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId);
        } catch (RuntimeException e) {
            sendPrivateMsg(socket, selfId, senderQq, "绑定失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private boolean isCommand(String msg) {
        return msg.startsWith("/") || msg.startsWith(".") || msg.startsWith("！") || msg.startsWith("!");
    }

    private String[] parseCommand(String raw) {
        String cmdLine = raw;
        if (cmdLine.startsWith(".")) cmdLine = "/" + cmdLine.substring(1);
        if (cmdLine.startsWith("！")) cmdLine = "/" + cmdLine.substring(1);
        if (cmdLine.startsWith("!")) cmdLine = "/" + cmdLine.substring(1);
        String[] parts = cmdLine.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        return new String[]{cmd, arg};
    }

    private Long verifyUserCredentials(String username, String password) {
        String sql = "SELECT id, password FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && BCrypt.checkpw(password, rs.getString("password"))) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) { log.error("验证用户凭据失败", e); }
        return null;
    }

    private boolean isUsernameExists(String username) {
        String sql = "SELECT COUNT(*) FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) { log.error("查询用户名失败", e); }
        return false;
    }

    private Long insertUser(String username, String hashedPassword) {
        String sql = "INSERT INTO users (username, password) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            if (stmt.executeUpdate() > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("用户插入失败", e); }
        return null;
    }
}
