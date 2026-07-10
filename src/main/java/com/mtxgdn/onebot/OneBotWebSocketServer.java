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
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.OneBotLogger;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.StatsCollector;
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

    // 黑名单自动续期禁言定时任务
    private final java.util.concurrent.ScheduledExecutorService muteRenewalScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final BlacklistService blacklistService = new BlacklistService();
    private final OneBotGroupConfigService groupConfigService = new OneBotGroupConfigService();

    public OneBotWebSocketServer() {
        // 每12小时检查一次黑名单用户并续期禁言
        muteRenewalScheduler.scheduleAtFixedRate(this::renewMuteForBlacklistedUsers,
                12, 12, java.util.concurrent.TimeUnit.HOURS);
        log.info("黑名单自动续期禁言定时任务已启动（每12小时执行一次）");
    }

    private void renewMuteForBlacklistedUsers() {
        if (botSessions.isEmpty()) {
            return;
        }
        java.util.List<Blacklist> blacklist = blacklistService.getAllBlacklist();
        if (blacklist.isEmpty()) {
            return;
        }
        java.util.List<OneBotGroupConfig> configs = groupConfigService.getAllConfigs();
        if (configs.isEmpty()) {
            return;
        }

        // 解析所有黑名单条目对应的QQ号（userId自动查绑定）
        java.util.List<String> targetQqs = new java.util.ArrayList<>();
        for (Blacklist b : blacklist) {
            targetQqs.addAll(blacklistService.resolveQqNumbers(b));
        }

        for (Map.Entry<String, WebSocket> entry : botSessions.entrySet()) {
            String selfId = entry.getKey();
            WebSocket socket = entry.getValue();

            for (OneBotGroupConfig config : configs) {
                if (!config.isAutoMuteEnabled()) {
                    continue;
                }
                Long groupId = config.getGroupId();
                int muteDays = config.getMuteDurationDays();

                for (String targetQq : targetQqs) {
                    checkAndRenewMute(socket, selfId, groupId, targetQq, muteDays);
                }
            }
        }
        log.info("黑名单自动续期禁言检查完成，处理 " + blacklist.size() + " 个黑名单条目，共 " + targetQqs.size() + " 个目标QQ");
    }

    private void checkAndRenewMute(WebSocket socket, String selfId, Long groupId, String targetQq, int muteDays) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "get_group_member_info");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("user_id", selfId);
        api.add("params", params);
        api.addProperty("echo", "renew_mute_" + groupId + "_" + targetQq + "_" + muteDays);
        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);

        // 同时检查目标用户是否群管理（管理员/群主不能被禁言）
        JsonObject api2 = new JsonObject();
        api2.addProperty("action", "get_group_member_info");
        JsonObject params2 = new JsonObject();
        params2.addProperty("group_id", groupId);
        params2.addProperty("user_id", targetQq);
        api2.add("params", params2);
        api2.addProperty("echo", "check_target_admin_" + groupId + "_" + targetQq + "_" + muteDays);
        String jsonStr2 = gson.toJson(api2);
        botLog.logSendToGroup(groupId, jsonStr2);
        socket.send(jsonStr2);
    }

    private void handleRenewMuteResponse(WebSocket socket, JsonObject json) {
        String echo = json.has("echo") ? json.get("echo").getAsString() : "";
        if (!echo.startsWith("renew_mute_") && !echo.startsWith("check_target_admin_")) return;

        String[] parts;
        Long groupId;
        String targetQq;
        int muteDays;

        if (echo.startsWith("check_target_admin_")) {
            parts = echo.substring("check_target_admin_".length()).split("_");
            if (parts.length < 3) return;
            groupId = Long.parseLong(parts[0]);
            targetQq = parts[1];
            muteDays = Integer.parseInt(parts[2]);

            // 检查目标用户是否为管理员/群主，如果是则不能禁言
            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                String targetRole = data.has("role") ? data.get("role").getAsString() : "member";
                if (!"admin".equals(targetRole) && !"owner".equals(targetRole)) {
                    setGroupBan(socket, groupId, targetQq, muteDays);
                }
            }
            return;
        }

        parts = echo.substring("renew_mute_".length()).split("_");
        if (parts.length < 3) return;

        groupId = Long.parseLong(parts[0]);
        targetQq = parts[1];
        muteDays = Integer.parseInt(parts[2]);

        if (json.has("data")) {
            JsonObject data = json.getAsJsonObject("data");
            String role = data.has("role") ? data.get("role").getAsString() : "member";
            if ("admin".equals(role) || "owner".equals(role)) {
                // 机器人是管理员，续期禁言
                setGroupBan(socket, groupId, targetQq, muteDays);
            }
        }
    }

    private static class PendingSession {
        Long sourceGroupId;
        String type;
        String state;
        String username;
        String password;
        String email;
        String verificationCode;

        PendingSession(String type, Long sourceGroupId) {
            this.type = type;
            this.sourceGroupId = sourceGroupId;
            this.state = "WAITING_USERNAME";
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

    public void shutdown() {
        muteRenewalScheduler.shutdown();
        log.info("黑名单自动续期禁言定时任务已停止");
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
        StatsCollector.getInstance().recordMessage(senderQq, null);

        PendingSession session = pendingSessions.get(senderQq);
        if (session != null) {
            handlePendingFlow(socket, selfId, senderQq, session, trimmed);
            return;
        }

        com.mtxgdn.onebot.quiz.QuizService.getInstance().processMessage(rawMessage);

        if (tryHandleQuiz(socket, selfId, senderQq, null, trimmed)) return;

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
        StatsCollector.getInstance().recordMessage(senderQq, groupId);

        // 黑名单检查：如果在黑名单中且群组启用了自动禁言，则禁言
        if (blacklistService.isBlacklisted(senderQq)) {
            if (groupConfigService.isAutoMuteEnabled(groupId)) {
                int muteDays = groupConfigService.getMuteDuration(groupId);
                checkAndMuteBlacklistedUser(socket, selfId, groupId, senderQq, muteDays);
            }
            return;
        }

        com.mtxgdn.onebot.quiz.QuizService.getInstance().processMessage(rawMessage);

        if (tryHandleQuiz(socket, selfId, senderQq, groupId, trimmed)) return;

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

    private boolean tryHandleQuiz(WebSocket socket, String selfId, String senderQq,
                                   Long groupId, String message) {
        com.mtxgdn.onebot.quiz.QuizService quiz = com.mtxgdn.onebot.quiz.QuizService.getInstance();
        com.mtxgdn.onebot.quiz.QuizQuestion q = quiz.findMatch(message);
        if (q == null) return false;

        StringBuilder sb = new StringBuilder();
        sb.append(q.getQuestion()).append("\n");
        sb.append("\n修仙答").append(q.getAnswer());
        replyToSource(socket, selfId, senderQq, groupId, sb.toString());
        return true;
    }

    private void dispatchCommand(WebSocket socket, String selfId, String senderQq,
                                  String senderNickname, Long groupId, String cmd, String arg) {
        StatsCollector.getInstance().recordCommand(cmd, groupId);
        Command command = CommandRegistry.get(cmd);
        if (command == null) {
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
        // 处理管理员检查响应
        handleAdminCheckResponse(socket, json);
        // 处理续期禁言响应
        handleRenewMuteResponse(socket, json);
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
        QqBinding existingBinding = bindingService.findByQq(senderQq);
        if (existingBinding != null) {
            // 检查是否"已绑定但无角色"的异常情况（上次注册时 createPlayer 失败）
            if (ServiceRegistry.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                // 孤立的账号：跳过用户创建，直接补建角色
                try {
                    PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(
                            existingBinding.getUserId(), arg.trim());
                    String guideMsg = NewbieGuideService.getWelcomeMessage(player);
                    replyToSource(socket, selfId, senderQq, sourceGroupId, guideMsg);
                    actionLog.logCreatePlayer(existingBinding.getUserId(), player.getName());
                } catch (RuntimeException e) {
                    replyToSource(socket, selfId, senderQq, sourceGroupId,
                            "创建角色失败: " + e.getMessage());
                }
                return;
            }
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

    @Override
    public void handleChangePassword(WebSocket socket, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(socket, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        PendingSession session = new PendingSession("changePassword", null);
        session.state = "WAITING_OLD_PASSWORD";
        pendingSessions.put(senderQq, session);
        sendPrivateMsg(socket, selfId, senderQq,
                "===== 修改密码 =====\n请输入当前密码：\n(输入 /cancel 取消)");
    }

    @Override
    public void handleDeleteAccount(WebSocket socket, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(socket, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        PendingSession session = new PendingSession("deleteAccount", null);
        session.state = "WAITING_DELETE_CONFIRM";
        pendingSessions.put(senderQq, session);
        sendPrivateMsg(socket, selfId, senderQq,
                "===== 注销账号 =====\n⚠ 此操作不可逆！\n注销后将删除你的角色、物品、灵石等全部数据！\n\n请输入账号密码确认注销：\n(输入 /cancel 取消)");
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
            case "WAITING_OLD_PASSWORD":
                handleOldPasswordInput(socket, selfId, senderQq, session, message.trim());
                break;
            case "WAITING_NEW_PASSWORD":
                handleNewPasswordInput(socket, selfId, senderQq, session, message.trim());
                break;
            case "WAITING_DELETE_CONFIRM":
                handleDeleteConfirm(socket, selfId, senderQq, session, message.trim());
                break;
            case "WAITING_PASSWORD":
                String passwordInput = message.trim();
                if (passwordInput.length() < 6) {
                    sendPrivateMsg(socket, selfId, senderQq, "密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if ("register".equals(session.type)) {
                    session.password = passwordInput;
                    boolean verifyCodeEnabled = AppConfig.getBoolean("verify_code.enabled", true);
                    if (verifyCodeEnabled) {
                        session.state = "WAITING_EMAIL";
                        sendPrivateMsg(socket, selfId, senderQq, "请输入邮箱地址：\n(输入 /cancel 取消)");
                    } else {
                        doRegisterComplete(socket, selfId, senderQq, session, passwordInput, null, null);
                        pendingSessions.remove(senderQq);
                    }
                } else {
                    doBindComplete(socket, selfId, senderQq, session, passwordInput);
                    pendingSessions.remove(senderQq);
                }
                break;
            case "WAITING_EMAIL":
                String emailInput = message.trim();
                if (emailInput.isEmpty()) {
                    sendPrivateMsg(socket, selfId, senderQq, "邮箱不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if (!isValidEmail(emailInput)) {
                    sendPrivateMsg(socket, selfId, senderQq, "邮箱格式不正确，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                session.email = emailInput.toLowerCase();
                try {
                    VerificationCodeService verificationCodeService = new VerificationCodeService();
                    if (!verificationCodeService.canSendCode(session.email)) {
                        sendPrivateMsg(socket, selfId, senderQq, "发送过于频繁，请稍后再试：\n(输入 /cancel 取消)");
                        return;
                    }
                    String code = verificationCodeService.generateAndStoreCode(session.email);
                    EmailService.sendVerificationCode(session.email, code);
                    session.state = "WAITING_CODE";
                    sendPrivateMsg(socket, selfId, senderQq, "验证码已发送至邮箱，请输入验证码：\n(输入 /cancel 取消)");
                } catch (Exception e) {
                    sendPrivateMsg(socket, selfId, senderQq, "发送验证码失败: " + e.getMessage() + "\n请重新输入邮箱：\n(输入 /cancel 取消)");
                }
                break;
            case "WAITING_CODE":
                String codeInput = message.trim();
                if (codeInput.isEmpty()) {
                    sendPrivateMsg(socket, selfId, senderQq, "验证码不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                VerificationCodeService vcService = new VerificationCodeService();
                if (!vcService.verifyCode(session.email, codeInput)) {
                    sendPrivateMsg(socket, selfId, senderQq, "验证码错误或已过期，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                doRegisterComplete(socket, selfId, senderQq, session, session.password, session.email, codeInput);
                pendingSessions.remove(senderQq);
                break;
        }
    }

    private void handleOldPasswordInput(WebSocket socket, String selfId, String senderQq,
                                         PendingSession session, String oldPassword) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = getUsernameByUserId(b.getUserId());
        Long userId = verifyUserCredentials(username, oldPassword);
        if (userId == null) {
            sendPrivateMsg(socket, selfId, senderQq, "当前密码错误，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        session.state = "WAITING_NEW_PASSWORD";
        sendPrivateMsg(socket, selfId, senderQq, "请输入新密码（不少于6位）：\n(输入 /cancel 取消)");
    }

    private void handleNewPasswordInput(WebSocket socket, String selfId, String senderQq,
                                         PendingSession session, String newPassword) {
        if (newPassword.length() < 6) {
            sendPrivateMsg(socket, selfId, senderQq, "新密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        try {
            String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            updatePassword(b.getUserId(), hashedPassword);
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "密码修改成功！");
            actionLog.logCustom(b.getUserId(), "系统", "修改密码", "成功");
        } catch (RuntimeException e) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "密码修改失败: " + e.getMessage());
        }
    }

    private void handleDeleteConfirm(WebSocket socket, String selfId, String senderQq,
                                      PendingSession session, String password) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = getUsernameByUserId(b.getUserId());
        Long userId = verifyUserCredentials(username, password);
        if (userId == null) {
            sendPrivateMsg(socket, selfId, senderQq, "密码错误，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        try {
            // 解绑QQ
            bindingService.unbindByQq(senderQq);
            // 删除玩家数据
            PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
            if (player != null) {
                ServiceRegistry.getPlayerService().deletePlayer(player.getId());
                actionLog.logCustom(userId, username, "注销账号", "角色数据已删除");
            }
            // 删除用户账号
            deleteUser(userId);
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "账号已注销。感谢您的陪伴，修仙之路后会有期！");
        } catch (RuntimeException e) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "注销失败: " + e.getMessage());
        }
    }

    private void doRegisterComplete(WebSocket socket, String selfId, String senderQq,
                                     PendingSession session, String password, String email, String code) {
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
        Long userId = null;
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            userId = insertUser(session.username, hashedPassword, email);
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
            // 创建角色失败，回滚已创建的 user 和绑定
            if (userId != null) {
                try { bindingService.unbindByQq(senderQq); } catch (Exception ignored) {}
                try { deleteUser(userId); } catch (Exception ignored) {}
            }
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
            String email = senderQq + "@qq.com";
            UserService userService = new UserService();
            String existingEmail = userService.getUserEmail(userId);
            if (existingEmail == null || existingEmail.isEmpty()) {
                userService.updateUserEmail(userId, email);
                sendPrivateMsg(socket, selfId, senderQq,
                        "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId + "\n已自动绑定邮箱: " + email);
            } else {
                sendPrivateMsg(socket, selfId, senderQq,
                        "绑定成功！\n用户名: " + session.username + "\n用户ID: " + userId);
            }
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

    private Long insertUser(String username, String hashedPassword, String email) {
        String sql = "INSERT INTO users (username, password, email) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, hashedPassword);
            stmt.setString(3, email);
            if (stmt.executeUpdate() > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
            }
        } catch (SQLException e) { throw new RuntimeException("用户插入失败", e); }
        return null;
    }

    private void deleteUser(long userId) {
        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("删除用户失败", e); }
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    private String getUsernameByUserId(long userId) {
        String sql = "SELECT username FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("username");
            }
        } catch (SQLException e) { log.error("查询用户名失败", e); }
        return null;
    }

    private void updatePassword(long userId, String hashedPassword) {
        String sql = "UPDATE users SET password = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, hashedPassword);
            stmt.setLong(2, userId);
            if (stmt.executeUpdate() == 0) {
                throw new RuntimeException("用户不存在");
            }
        } catch (SQLException e) { throw new RuntimeException("密码更新失败", e); }
    }

    // ==================== 黑名单自动禁言 ====================

    private void checkAndMuteBlacklistedUser(WebSocket socket, String selfId, Long groupId, String senderQq, int muteDays) {
        // 先检查机器人是否为管理员
        checkBotAdminStatus(socket, selfId, groupId, senderQq, muteDays);
    }

    private void checkBotAdminStatus(WebSocket socket, String selfId, Long groupId, String targetQq, int muteDays) {
        JsonObject api = new JsonObject();
        api.addProperty("action", "get_group_member_info");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("user_id", selfId);
        api.add("params", params);
        api.addProperty("echo", "check_admin_" + groupId + "_" + targetQq + "_" + muteDays);
        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);
    }

    private void handleAdminCheckResponse(WebSocket socket, JsonObject json) {
        String echo = json.has("echo") ? json.get("echo").getAsString() : "";
        if (!echo.startsWith("check_admin_")) return;

        String[] parts = echo.substring("check_admin_".length()).split("_");
        if (parts.length < 3) return;

        Long groupId = Long.parseLong(parts[0]);
        String targetQq = parts[1];
        int muteDays = Integer.parseInt(parts[2]);

        if (json.has("data")) {
            JsonObject data = json.getAsJsonObject("data");
            String role = data.has("role") ? data.get("role").getAsString() : "member";
            if ("admin".equals(role) || "owner".equals(role)) {
                // 机器人是管理员，执行禁言
                setGroupBan(socket, groupId, targetQq, muteDays);
            }
        }
    }

    private void setGroupBan(WebSocket socket, Long groupId, String targetQq, int muteDays) {
        // 禁言时长转换为秒（天数 * 24小时 * 60分钟 * 60秒）
        long durationSeconds = muteDays * 24 * 60 * 60L;
        JsonObject api = new JsonObject();
        api.addProperty("action", "set_group_ban");
        JsonObject params = new JsonObject();
        params.addProperty("group_id", groupId);
        params.addProperty("user_id", targetQq);
        params.addProperty("duration", durationSeconds);
        api.add("params", params);
        api.addProperty("echo", UUID.randomUUID().toString());
        String jsonStr = gson.toJson(api);
        botLog.logSendToGroup(groupId, jsonStr);
        socket.send(jsonStr);
        log.info("[黑名单禁言] 群:" + groupId + " QQ:" + targetQq + " 禁言" + muteDays + "天");
    }
}
