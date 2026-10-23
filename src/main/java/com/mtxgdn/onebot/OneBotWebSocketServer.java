package com.mtxgdn.onebot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.OfflineRewardService;
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
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.websockets.DataFrame;
import org.glassfish.grizzly.websockets.WebSocket;
import org.glassfish.grizzly.websockets.WebSocketApplication;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
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
    //
    // 必须是 daemon 线程并且有名字：
    //  - 非 daemon 会让 JVM 无法退出（关服时卡住，只能强杀）；
    //  - 匿名线程在 jstack / 线程转储里无法辨认是谁泄漏的。
    // 另外每个 OneBotWebSocketServer 实例都会创建一个，热重载时若没人调用 shutdown()
    // 就会一直累积 —— 见 AdapterManager.cleanupOneBotServer() 里显式调用 shutdown()。
    private final java.util.concurrent.ScheduledExecutorService muteRenewalScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "onebot-mute-renewal");
                t.setDaemon(true);
                return t;
            });
    private final BlacklistService blacklistService = new BlacklistService();
    private final OneBotGroupConfigService groupConfigService = new OneBotGroupConfigService();

    /**
     * 期望的 OneBot 访问令牌。空字符串表示"未配置"——此时只允许回环地址接入，
     * 避免默认部署把指令通道暴露给整个网络。
     */
    private final String accessToken;

    public OneBotWebSocketServer() {
        this("");
    }

    public OneBotWebSocketServer(String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        // 每12小时检查一次黑名单用户并续期禁言
        muteRenewalScheduler.scheduleAtFixedRate(this::renewMuteForBlacklistedUsers,
                12, 12, java.util.concurrent.TimeUnit.HOURS);
        log.info("黑名单自动续期禁言定时任务已启动（每12小时执行一次）");
        if (this.accessToken.isEmpty()) {
            log.warn("OneBot 未配置 access_token：仅允许本机(127.0.0.1/::1)接入；"
                    + "如需远程接入，请在 config/adapters.json 中为该适配器设置 access_token");
        }
    }

    /**
     * 握手前校验：在 WebSocket 升级阶段就拒绝未授权连接，而不是先建连再关闭。
     *
     * <p>OneBot v11 客户端（go-cqhttp / NapCat / LLOneBot 等）支持两种携带方式，这里都接受：
     * {@code Authorization: Bearer <token>} 请求头，或 {@code ?access_token=<token>} 查询参数。
     */
    @Override
    public boolean isApplicationRequest(HttpRequestPacket request) {
        if (!super.isApplicationRequest(request)) {
            return false;
        }
        String remote = request.getRemoteAddress();
        if (accessToken.isEmpty()) {
            if (isLoopback(remote)) {
                return true;
            }
            log.warn("OneBot 握手被拒绝：未配置 access_token 且来源不是本机（来源 " + remote + "）");
            return false;
        }
        String provided = extractAccessToken(request);
        if (provided != null && constantTimeEquals(accessToken, provided)) {
            return true;
        }
        log.warn("OneBot 握手被拒绝：access_token 缺失或不匹配（来源 " + remote + "）");
        return false;
    }

    private static String extractAccessToken(HttpRequestPacket request) {
        String auth = request.getHeader("Authorization");
        if (auth != null) {
            String v = auth.trim();
            if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
                v = v.substring(7).trim();
            }
            if (!v.isEmpty()) {
                return v;
            }
        }
        String token = queryParam(request.getQueryString(), "access_token");
        if (token != null && !token.isEmpty()) {
            return token;
        }
        String header = request.getHeader("X-OneBot-Token");
        return header != null && !header.isBlank() ? header.trim() : null;
    }

    /** 从原始 query string 取参数。 */
    private static String queryParam(String queryString, String name) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (pair.substring(0, eq).equals(name)) {
                String raw = pair.substring(eq + 1);
                try {
                    return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return raw;
                }
            }
        }
        return null;
    }

    private static boolean isLoopback(String remote) {
        if (remote == null) {
            return false;
        }
        return remote.startsWith("127.") || remote.equals("::1")
                || remote.equals("0:0:0:0:0:0:0:1") || remote.equalsIgnoreCase("localhost");
    }

    /** 定长比较，避免通过响应时间差推断令牌。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
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
        botLog.logRecv(com.mtxgdn.util.LogMasker.mask(text));
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

    /**
     * 离线奖励结算节流：QQ -> 上次结算的毫秒时间戳。
     *
     * <p><b>为什么需要</b>：原来私聊/群聊<b>每条消息</b>（包括根本不构成指令的闲聊）
     * 都会先 {@code bindingService.findByQq(...)} 查一次绑定，
     * 再调 {@code processOfflineRewards(userId)} 做一次完整的离线结算。
     * 一个热闹的群里这等于每说一句话就跑一次"读玩家 + 算离线时长 + 写回状态"，
     * 既浪费数据库，也会因为频繁写同一行而制造锁竞争。</p>
     *
     * <p>离线奖励本来就是"一段时间没上线后第一次互动"才该结算的东西，
     * 所以按 QQ 做冷却：冷却期内直接返回，连绑定都不查（一次 HashMap 查询替代两次 DB 操作）。
     * 冷却时间由 {@code onebot.offline_reward_cooldown_seconds} 控制，默认 3600 秒；
     * 设为 0 或负数表示不节流（退化为原来的行为）。</p>
     */
    private final Map<String, Long> lastOfflineRewardAt = new ConcurrentHashMap<>();

    private static final long OFFLINE_REWARD_COOLDOWN_MS =
            Math.max(0, AppConfig.getInt("onebot.offline_reward_cooldown_seconds", 3600)) * 1000L;

    /** 冷却表超过这个规模就顺手清一遍过期项，避免长期运行后无限增长。 */
    private static final int OFFLINE_REWARD_CACHE_SWEEP_THRESHOLD = 5000;

    /**
     * 到点才结算离线奖励：冷却窗口内直接跳过，不查绑定、不碰数据库。
     */
    private void settleOfflineRewardIfDue(WebSocket socket, String selfId, String senderQq) {
        long now = System.currentTimeMillis();
        if (OFFLINE_REWARD_COOLDOWN_MS > 0) {
            Long last = lastOfflineRewardAt.get(senderQq);
            if (last != null && now - last < OFFLINE_REWARD_COOLDOWN_MS) {
                return;
            }
            if (lastOfflineRewardAt.size() > OFFLINE_REWARD_CACHE_SWEEP_THRESHOLD) {
                lastOfflineRewardAt.entrySet()
                        .removeIf(e -> now - e.getValue() >= OFFLINE_REWARD_COOLDOWN_MS);
            }
        }
        QqBinding binding = bindingService.findByQq(senderQq);
        if (binding == null) {
            // 未绑定：不记时间戳，玩家绑定后的第一条消息就能立刻拿到离线奖励
            return;
        }
        lastOfflineRewardAt.put(senderQq, now);
        processOfflineReward(socket, selfId, senderQq, binding.getUserId());
    }

    private void processOfflineReward(WebSocket socket, String selfId, String senderQq, Long userId) {
        OfflineRewardService offlineService = ServiceRegistry.getOfflineRewardService();
        OfflineRewardService.OfflineRewardResult result = offlineService.processOfflineRewards(userId);

        if (!result.hasReward) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 离线奖励 =====\n");
        sb.append("离线时长: ").append(result.offlineHours).append("小时").append(result.offlineMinutes % 60).append("分钟\n");

        if (result.hpRecovered > 0) {
            sb.append("生命恢复: +").append(result.hpRecovered).append("\n");
        }
        if (result.mpRecovered > 0) {
            sb.append("法力恢复: +").append(result.mpRecovered).append("\n");
        }

        if (result.wasCultivating) {
            sb.append("修炼获得经验: +").append(result.expGained).append("\n");
            if (result.rawExpGained != result.expGained) {
                sb.append("(原始经验: ").append(result.rawExpGained).append(")\n");
            }

            if (result.heartDemonTriggered) {
                sb.append("\n⚠️ 心魔发作！\n");
                sb.append(result.heartDemonNarrative).append("\n");
                sb.append("损失经验: ").append(result.heartDemonExpLost).append("\n");
            }
        }

        sendPrivateMsg(socket, selfId, senderQq, sb.toString());
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
        log.info("[私聊:" + senderQq + "] " + senderNickname + ": " + com.mtxgdn.util.LogMasker.mask(rawMessage));
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, null);

        // 节流：冷却窗口内连绑定都不查（原来每条消息都要查一次绑定 + 跑一次离线结算）
        settleOfflineRewardIfDue(socket, selfId, senderQq);

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
        log.info("[群聊:" + groupId + "] [QQ:" + senderQq + "] " + senderNickname + ": " + com.mtxgdn.util.LogMasker.mask(rawMessage));
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, groupId);

        // 节流：冷却窗口内连绑定都不查（原来每条消息都要查一次绑定 + 跑一次离线结算）
        settleOfflineRewardIfDue(socket, selfId, senderQq);

        // 黑名单检查：如果在黑名单中且群组启用了自动禁言，则禁言
        if (blacklistService.isBlacklisted(senderQq)) {
            if (groupConfigService.isAutoMuteEnabled(groupId)) {
                int muteDays = groupConfigService.getMuteDuration(groupId);
                checkAndMuteBlacklistedUser(socket, selfId, groupId, senderQq, muteDays);
            }
            return;
        }

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
        StatsCollector.getInstance().recordCommand(cmd, groupId);

        // "/cancel" 是向导流程的保留取消词（待处理向导在上游已拦截）；
        // 无待取消操作时给出提示，避免误触其他指令或静默无响应
        if ("cancel".equalsIgnoreCase(cmd)) {
            replyToSource(socket, selfId, senderQq, groupId, "当前没有待取消的操作。");
            return;
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) {
            // 命令存在但所属内置模块已停用：给出提示而非静默忽略
            Command raw = CommandRegistry.getRaw(cmd);
            if (raw != null && !raw.isModuleEnabled()) {
                replyToSource(socket, selfId, senderQq, groupId, "该玩法系统已停用，暂时无法使用。");
            }
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
        // 插件事件：命令执行
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND)
                        .data("command", command.getNames() != null && command.getNames().length > 0 ? command.getNames()[0] : cmd)
                        .data("arg", arg == null ? "" : arg)
                        .data("senderQq", senderQq)
                        .data("groupId", groupId == null ? 0L : groupId)
                        .build());
        // 指令执行挪到独立线程池：一条指令 3~12 次 DB 查询，/注册 /改密 还要跑 BCrypt，
        // 不能占着 Grizzly 的 worker 线程（那个池同时服务所有 REST 请求）
        final Command execCmd = command;
        final OneBotCommandContext execCtx = ctx;
        if (!com.mtxgdn.common.command.CommandExecutor.submit("onebot:/" + cmd,
                () -> execCmd.execute(execCtx))) {
            replyToSource(socket, selfId, senderQq, groupId, "服务器繁忙，请稍后再试。");
        }
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
        if (UserAccountDAO.isUsernameExists(name)) {
            replyToSource(socket, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }
        PendingSession session = new PendingSession("register", sourceGroupId);
        session.username = name;
        session.state = "WAITING_PASSWORD";
        pendingSessions.put(senderQq, session);
        replyToSource(socket, selfId, senderQq, sourceGroupId,
                "角色名【" + name + "】可用！\n请在私聊中发送你的密码（不少于6位）\n(输入 /cancel 取消)");
    }

    @Override
    public void handleBind(WebSocket socket, String selfId, String senderQq) {
        // 只查一次：原来先 findByQq(...) != null 判断、再 findByQq(...) 取对象，
        // 同一份数据打两次库（中间还可能被并发解绑，导致 b 为 null 时 NPE）
        QqBinding existing = bindingService.findByQq(senderQq);
        if (existing != null) {
            sendPrivateMsg(socket, selfId, senderQq,
                    "你已绑定账号 (用户ID: " + existing.getUserId() + ")，/unbind 可解绑。");
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
                if (!UserAccountDAO.isValidEmail(emailInput)) {
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
        String username = UserAccountDAO.getUsernameByUserId(b.getUserId());
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
            UserAccountDAO.updatePassword(b.getUserId(), hashedPassword);
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
        String username = UserAccountDAO.getUsernameByUserId(b.getUserId());
        Long userId = verifyUserCredentials(username, password);
        if (userId == null) {
            sendPrivateMsg(socket, selfId, senderQq, "密码错误，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        try {
            PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(userId);
            if (player != null) {
                ServiceRegistry.getPlayerService().deletePlayer(player.getId());
            }
            UserAccountDAO.deleteUser(userId);
            bindingService.unbindByQq(senderQq);
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "账号已注销。感谢您的陪伴，修仙之路后会有期！");
        } catch (RuntimeException e) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(socket, selfId, senderQq, "注销失败: " + e.getMessage());
        }
    }

    private void doRegisterComplete(WebSocket socket, String selfId, String senderQq,
                                     PendingSession session, String password, String email, String code) {
        if (UserAccountDAO.isUsernameExists(session.username)) {
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
            userId = UserAccountDAO.insertUser(session.username, hashedPassword, email);
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
                try { UserAccountDAO.deleteUser(userId); } catch (Exception ignored) {}
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
        String rateLimitKey = "onebot_login_" + username.toLowerCase();
        if (!RateLimiter.allow(rateLimitKey, 5, 60)) {
            return null;
        }

        String sql = "SELECT id, password, failed_attempts, locked_until FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp lockedUntil = rs.getTimestamp("locked_until");
                    if (lockedUntil != null && lockedUntil.getTime() > System.currentTimeMillis()) {
                        return null;
                    }

                    if (BCrypt.checkpw(password, rs.getString("password"))) {
                        resetFailedAttempts(rs.getLong("id"));
                        return rs.getLong("id");
                    } else {
                        incrementFailedAttempts(rs.getLong("id"));
                    }
                }
            }
        } catch (SQLException e) { log.error("验证用户凭据失败", e); }
        return null;
    }

    private void incrementFailedAttempts(long userId) {
        int maxAttempts = 5;
        long lockDurationMinutes = 15;
        String sql = "UPDATE users SET failed_attempts = failed_attempts + 1, " +
                "locked_until = COALESCE(" +
                "CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE NULL END, " +
                "locked_until) " +
                "WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxAttempts);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis() + lockDurationMinutes * 60 * 1000));
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("更新失败次数失败", e); }
    }

    private void resetFailedAttempts(long userId) {
        String sql = "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("重置失败次数失败", e); }
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
