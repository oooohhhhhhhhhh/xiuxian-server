package com.mtxgdn.qq;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.onebot.OneBotAccountFlowClient;
import com.mtxgdn.onebot.OneBotMessageSenderClient;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqOfficialBindingService;
import com.mtxgdn.onebot.command.OneBotClientCommandContext;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.OneBotLogger;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.StatsCollector;
import org.mindrot.jbcrypt.BCrypt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 官方开放平台（QQ 机器人）适配器。
 * <p>
 * 基于 QQ 开放平台 API v2：
 * <ul>
 *   <li>鉴权：通过 AppID + ClientSecret 调用 {@code /app/getAppAccessToken} 获取 access_token
 *       （有效期 7200 秒，自动在过期前刷新），调用开放接口时使用 {@code Authorization: QQBot &lt;token&gt;}</li>
 *   <li>接收：通过 WebSocket 网关（wss://api.sgroup.qq.com/websocket）接收事件，
 *       （Identify/心跳/恢复）协议，支持群聊 @ 消息（GROUP_AT_MESSAGE_CREATE）与单聊消息（C2C_MESSAGE_CREATE）</li>
 *   <li>发送：通过 HTTP REST 接口发送消息（群聊 /v2/groups/{group_openid}/messages，单聊 /v2/users/{openid}/messages）</li>
 * </ul>
 * 该适配器复用 {@link OneBotClientCommandContext} 与现有账号流程（注册/绑定/注销等）业务逻辑，
 * 通过实现 {@link OneBotSession} / {@link OneBotMessageSenderClient} / {@link OneBotAccountFlowClient} 与业务层解耦。
 */
public class QqOfficialAdapter implements OneBotSession, OneBotMessageSenderClient, OneBotAccountFlowClient {

    private static final Gson gson = new Gson();
    private static final GameLogger log = GameLogger.getLogger("QQBot");
    private static final OneBotLogger botLog = OneBotLogger.getInstance();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();

    // ==================== 常量 ====================

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";
    private static final String API_BASE = "https://api.sgroup.qq.com";
    private static final String DEFAULT_GATEWAY = "wss://api.sgroup.qq.com/websocket";
    private static final String SANDBOX_GATEWAY = "wss://sandbox.api.sgroup.qq.com/websocket";

    /** 单条消息最大长度（官方限制 4000 字节，留余量按字符截断） */
    private static final int MAX_CONTENT_LENGTH = 3500;

    /** 被动回复有效窗口：单聊 60 分钟，群聊 5 分钟 */
    private static final long C2C_PASSIVE_WINDOW_MS = 60 * 60 * 1000L;
    private static final long GROUP_PASSIVE_WINDOW_MS = 5 * 60 * 1000L;

    /** 同一条入站消息允许的被动回复次数上限（平台限制，msg_seq 需在同一 msg_id 内递增） */
    private static final int MAX_PASSIVE_REPLIES = 5;

    /** 事件订阅 Intent 映射 */
    private static final Map<String, Integer> INTENTS = new HashMap<>();

    static {
        INTENTS.put("GUILDS", 1 << 0);
        INTENTS.put("GUILD_MEMBERS", 1 << 1);
        INTENTS.put("GUILD_MESSAGES", 1 << 9);
        INTENTS.put("GUILD_MESSAGE_REACTIONS", 1 << 10);
        INTENTS.put("DIRECT_MESSAGE", 1 << 12);
        INTENTS.put("AUDIO_ACTION", 1 << 19);
        INTENTS.put("C2C_MESSAGE_CREATE", 1 << 20);
        INTENTS.put("GROUP_ADD_ROBOT", 1 << 22);
        INTENTS.put("GROUP_DEL_ROBOT", 1 << 23);
        INTENTS.put("GROUP_MESSAGE_CREATE", 1 << 24);
        INTENTS.put("GROUP_AT_MESSAGE_CREATE", 1 << 25);
        INTENTS.put("INTERACTION", 1 << 26);
        INTENTS.put("MESSAGE_AUDIT", 1 << 27);
        INTENTS.put("FORUMS_EVENT", 1 << 28);
    }

    // ==================== 配置字段 ====================

    private String appId = "";
    private String clientSecret = "";
    private String gatewayUrl = DEFAULT_GATEWAY;
    private boolean sandbox = false;
    private int intents = 0;
    private String commandPrefix = "";
    private String sendMode = "text";
    private long reconnectIntervalMs = 5000;
    private boolean removeAt = true;

    // ---- Webhook 模式配置（mode=webhook 时启用）----
    private String mode = "websocket";
    private String webhookHost = "0.0.0.0";
    private int webhookPort = 8080;
    private String webhookPath = "/qq/webhook";
    private long webhookMaxSkewMs = 300_000;
    private volatile QqWebhookServer webhookServer;

    // ==================== 服务与状态 ====================

    /**
     * 绑定服务：在 {@link #loadConfig(AdapterConfig)} 里按本实例的 app_id 重建，
     * 使绑定与身份查找按机器人隔离（openid 是按 AppID 隔离的）。
     */
    private volatile QqOfficialBindingService bindingService = new QqOfficialBindingService();
    private final com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
    private final Map<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    private volatile boolean started = false;
    private volatile boolean stopped = false;

    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private GatewayClient gateway;

    private volatile String selfId = "";
    private volatile String sessionId = "";
    private volatile int lastSeq = 0;

    // access_token 缓存
    private volatile String accessToken = "";
    private volatile long tokenExpireAt = 0;

    // 重连状态
    private volatile int reconnectAttempts = 0;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    // 被动回复追踪（openid/group_openid -> 最近一条消息）
    /**
     * 最近一条入站消息的被动回复上下文。
     *
     * <p>QQ 官方被动回复必须同时携带 {@code msg_id}（入站消息 id）与 {@code msg_seq}
     * （<b>本端生成</b>的回复序号）：同一个 {@code msg_id} 下每次回复都要递增，缺失或重复
     * 会被平台以 {@code 40054005 消息被去重，请检查请求msgseq} 直接拒收。</p>
     *
     * <p>历史 bug：{@code msg_seq} 一直是从入站事件里读的（入站事件从来没有这个字段，
     * 恒为 0），于是回复只带 {@code msg_id}/{@code event_id} 不带序号，被平台成批去重。</p>
     */
    private static class LastMsg {
        final String msgId;
        final String eventId;
        final long timestamp;
        /** 该 msg_id 下已使用的回复序号（0 = 尚未回复，每次发送前 +1） */
        final AtomicInteger replySeq = new AtomicInteger(0);

        LastMsg(String msgId, String eventId, long timestamp) {
            this.msgId = msgId;
            this.eventId = eventId;
            this.timestamp = timestamp;
        }

        /** 取下一个被动回复序号（从 1 开始递增）。 */
        int nextReplySeq() {
            return replySeq.incrementAndGet();
        }
    }

    private final Map<String, LastMsg> lastC2cMsg = new ConcurrentHashMap<>();
    private final Map<String, LastMsg> lastGroupMsg = new ConcurrentHashMap<>();

    // QQ 的 group_openid 是字符串，内部映射为 Long 以复用现有业务逻辑
    private final Map<String, Long> groupIdByOpenid = new ConcurrentHashMap<>();
    private final Map<Long, String> openidByGroupId = new ConcurrentHashMap<>();
    private final AtomicLong nextGroupId = new AtomicLong(1);

    // ==================== PendingSession ====================

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

    // ==================== 生命周期 ====================

    /**
     * 启动适配器：获取 access_token 并连接 QQ WebSocket 网关。
     */
    public void start(AdapterConfig cfg) {
        if (started) {
            log.warn("QqOfficialAdapter 已在运行中");
            return;
        }

        loadConfig(cfg);
        started = true;
        stopped = false;

        httpClient = HttpClient.newHttpClient();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QQBot-Main");
            t.setDaemon(true);
            return t;
        });

        botLog.logSystem("QQ官方适配器启动中，AppID: " + appId);
        log.info("QQ官方适配器启动，AppID=" + appId + " id=" + cfg.getId());

        if (appId.isEmpty() || clientSecret.isEmpty()) {
            log.error("QQ官方适配器缺少 app_id / client_secret 配置");
            botLog.logSystem("配置错误：缺少 app_id 或 client_secret");
            return;
        }

        fetchAccessToken();

        // Webhook 模式：启动本地回调服务（端口必须为 80/443/8080/8443）；否则连接 WebSocket 网关
        if ("webhook".equalsIgnoreCase(mode)) {
            startWebhook();
        } else {
            connectGateway();
        }
    }

    /**
     * 启动 QQ 官方 Webhook 回调服务。
     * 官方要求端口必须为 80/443/8080/8443，签名规范见 QqWebhookServer 类注释。
     */
    private void startWebhook() {
        if (!QqWebhookServer.ALLOWED_PORTS.contains(webhookPort)) {
            log.error("QQ 官方 webhook 端口必须为 80/443/8080/8443，当前配置: " + webhookPort);
            botLog.logSystem("Webhook 端口非法（必须为 80/443/8080/8443）: " + webhookPort);
            return;
        }
        try {
            QqWebhookServer srv = new QqWebhookServer(
                    webhookHost, webhookPort, webhookPath, appId, clientSecret,
                    webhookMaxSkewMs, this::dispatchWebhookEvent);
            srv.start();
            webhookServer = srv;
            // Webhook 模式没有 READY 事件，机器人自身 id 即 AppID
            selfId = appId;
            reconnectAttempts = 0;
            botLog.logSystem("QQ 官方适配器 Webhook 模式已启动: " + srv.getListenUrl());
            log.info("QQ 官方适配器 Webhook 模式已启动: " + srv.getListenUrl());
        } catch (java.net.BindException e) {
            log.error("QQ webhook 端口被占用（" + webhookPort + "）: " + e.getMessage());
            botLog.logSystem("QQ webhook 端口被占用: " + webhookPort + "（请换用 80/443/8080/8443 中未被占用的端口，或关闭占用该端口的服务）");
        } catch (Exception e) {
            log.error("QQ webhook 启动失败: " + e.getMessage(), e);
            botLog.logSystem("QQ webhook 启动失败: " + e.getMessage());
        }
    }

    /** Webhook 事件入口：与 WebSocket 网关使用相同的 Dispatch 分发（envelope: {t, id, d, s}） */
    public void dispatchWebhookEvent(JsonObject payload) {
        if (payload == null) return;
        handleDispatch(null, payload);
    }

    private void loadConfig(AdapterConfig cfg) {
        this.appId = cfg.getString("app_id", "");
        this.clientSecret = cfg.getString("client_secret", "");
        // 绑定按 AppID 隔离：openid 是按 AppID 下发的，不同机器人（不同 AppID）openid 不同
        this.bindingService = new QqOfficialBindingService(this.appId);
        this.gatewayUrl = cfg.getString("gateway_url", DEFAULT_GATEWAY);
        this.sandbox = cfg.getBoolean("sandbox", false);
        this.intents = parseIntents(cfg.getString("intents", "GROUP_AT_MESSAGE_CREATE,C2C_MESSAGE_CREATE"));
        this.commandPrefix = cfg.getString("command_prefix", "");
        this.sendMode = cfg.getString("send_mode", "text");
        this.reconnectIntervalMs = cfg.getInt("reconnect_interval_ms", 5000);
        this.removeAt = cfg.getBoolean("remove_at", true);

        // Webhook 模式配置
        this.mode = cfg.getString("mode", "websocket");
        this.webhookHost = cfg.getString("webhook_host", "0.0.0.0");
        this.webhookPort = cfg.getInt("webhook_port", 8080);
        this.webhookPath = cfg.getString("webhook_path", "/qq/webhook");
        this.webhookMaxSkewMs = cfg.getInt("webhook_max_skew_seconds", 300) * 1000L;
    }

    /**
     * 停止适配器，释放所有资源。
     */
    public void stop() {
        stopped = true;
        started = false;

        if (webhookServer != null) {
            webhookServer.stop();
            webhookServer = null;
        }
        if (gateway != null) {
            gateway.closeInternal();
            gateway = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception ignored) {
            }
            httpClient = null;
        }
        botLog.logSystem("QQ官方适配器已停止");
        log.info("QQ官方适配器已停止");
    }

    /** 本实例对应的机器人 AppID（绑定与身份查找按它隔离）。 */
    public String getAppId() {
        return appId;
    }

    public boolean isWsConnected() {
        if ("webhook".equalsIgnoreCase(mode)) {
            return webhookServer != null && webhookServer.isRunning();
        }
        return gateway != null && gateway.isOpen();
    }

    // ==================== access_token 管理 ====================

    /**
     * 解析 Intent 配置字符串（逗号分隔的事件名）。
     */
    private int parseIntents(String names) {
        int mask = 0;
        if (names == null || names.trim().isEmpty()) {
            return mask;
        }
        for (String name : names.split(",")) {
            String key = name.trim().toUpperCase();
            Integer bit = INTENTS.get(key);
            if (bit != null) {
                mask |= bit;
            } else {
                log.warn("未知 intent: " + name);
            }
        }
        return mask;
    }

    /**
     * 获取 access_token（本地缓存过期时同步刷新）。
     */
    private String getAccessToken() {
        if (accessToken.isEmpty() || System.currentTimeMillis() >= tokenExpireAt - 60_000) {
            fetchAccessToken();
        }
        return accessToken;
    }

    /**
     * 调用官方接口获取 access_token，并在过期前定时刷新。
     * 文档: https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html
     */
    private synchronized void fetchAccessToken() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("appId", appId);
            body.addProperty("clientSecret", clientSecret);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            JsonObject obj = gson.fromJson(resp.body(), JsonObject.class);
            if (obj != null && obj.has("access_token")) {
                accessToken = obj.get("access_token").getAsString();
                long expiresIn = obj.has("expires_in") && !obj.get("expires_in").isJsonNull()
                        ? obj.get("expires_in").getAsLong() : 7200;
                tokenExpireAt = System.currentTimeMillis() + expiresIn * 1000;
                botLog.logSystem("已获取 access_token，有效期 " + expiresIn + " 秒");
                log.info("已获取 access_token，有效期 " + expiresIn + " 秒");

                // 在过期前 60 秒刷新
                if (scheduler != null && !scheduler.isShutdown()) {
                    long delay = Math.max(60, expiresIn - 60);
                    scheduler.schedule(this::fetchAccessToken, delay, TimeUnit.SECONDS);
                }
            } else if (obj != null && obj.has("code")) {
                log.error("获取 access_token 失败: code=" + obj.get("code")
                        + " message=" + optString(obj, "message", ""));
                botLog.logSystem("获取 access_token 失败: " + obj.get("code")
                        + " " + optString(obj, "message", ""));
            } else {
                log.error("获取 access_token 失败: " + resp.body());
            }
        } catch (Exception e) {
            log.error("获取 access_token 异常: " + e.getMessage(), e);
        }
    }

    // ==================== WebSocket 网关 ====================

    private void connectGateway() {
        if (stopped) return;
        if (accessToken.isEmpty()) {
            log.error("access_token 为空，无法连接网关");
            botLog.logSystem("access_token 为空，无法连接网关");
            scheduleReconnect();
            return;
        }
        try {
            String url = sandbox ? SANDBOX_GATEWAY
                    : (gatewayUrl != null && !gatewayUrl.isEmpty() ? gatewayUrl : DEFAULT_GATEWAY);
            gateway = new GatewayClient();
            botLog.logSystem("正在连接 QQ 网关: " + url);
            log.info("正在连接 QQ 网关: " + url);
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), gateway)
                    .exceptionally(e -> {
                        log.error("QQ 网关连接失败: " + e.getMessage(), e);
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            log.error("QQ 网关连接异常: " + e.getMessage(), e);
            scheduleReconnect();
        }
    }

    /**
     * 计算指数退避的重连延迟（毫秒）。
     */
    private long computeReconnectDelay() {
        long base = reconnectIntervalMs;
        int exp = Math.min(reconnectAttempts, 6);
        return Math.min(base * (1L << exp), 60000L);
    }

    private void scheduleReconnect() {
        if (stopped) return;
        if (reconnecting.compareAndSet(false, true)) {
            long delay = computeReconnectDelay();
            reconnectAttempts++;
            botLog.logSystem("QQ 网关断开，" + (delay / 1000) + "秒后重连（第" + reconnectAttempts + "次）");
            log.warn("QQ 网关断开，" + (delay / 1000) + "秒后重连（第" + reconnectAttempts + "次）");
            if (scheduler != null) {
                scheduler.schedule(() -> {
                    reconnecting.set(false);
                    if (!stopped) {
                        connectGateway();
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                reconnecting.set(false);
            }
        }
    }

    /**
     * QQ 网关 WebSocket 客户端会话。
     */
    private class GatewayClient implements WebSocket.Listener {

        private final StringBuilder textBuffer = new StringBuilder();
        private volatile WebSocket webSocket;
        private volatile boolean open;
        private volatile long lastRecvTime = System.currentTimeMillis();
        private volatile long lastHeartbeatAck = System.currentTimeMillis();
        private volatile long heartbeatIntervalMs = 40000;
        private ScheduledExecutorService heartbeatExecutor;
        private ScheduledFuture<?> heartbeatTask;

        boolean isOpen() {
            return open;
        }

        void closeInternal() {
            open = false;
            stopHeartbeat();
            WebSocket ws = webSocket;
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "adapter shutdown");
                } catch (Exception ignored) {
                }
            }
        }

        void sendHeartbeat() {
            WebSocket ws = webSocket;
            if (ws == null || !open) return;
            JsonObject hb = new JsonObject();
            hb.addProperty("op", 1);
            hb.addProperty("d", lastSeq);
            try {
                ws.sendText(gson.toJson(hb), true);
            } catch (Exception e) {
                log.debug("发送心跳失败: " + e.getMessage());
            }
        }

        /**
         * 发送鉴权（Identify, op=2）。
         */
        void sendIdentify() {
            WebSocket ws = webSocket;
            if (ws == null) return;
            JsonObject d = new JsonObject();
            d.addProperty("token", "QQBot " + accessToken);
            d.addProperty("intents", intents);
            JsonArray shard = new JsonArray();
            shard.add(0);
            shard.add(1);
            d.add("shard", shard);
            JsonObject payload = new JsonObject();
            payload.addProperty("op", 2);
            payload.add("d", d);
            ws.sendText(gson.toJson(payload), true);
        }

        /**
         * 发送恢复连接（Resume, op=6）。
         */
        void sendResume() {
            WebSocket ws = webSocket;
            if (ws == null) return;
            JsonObject d = new JsonObject();
            d.addProperty("token", "QQBot " + accessToken);
            d.addProperty("session_id", sessionId);
            d.addProperty("seq", lastSeq);
            JsonObject payload = new JsonObject();
            payload.addProperty("op", 6);
            payload.add("d", d);
            ws.sendText(gson.toJson(payload), true);
        }

        void startHeartbeat(long intervalMs) {
            stopHeartbeat();
            this.heartbeatIntervalMs = intervalMs > 0 ? intervalMs : 40000;
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "QQBot-Heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(this::tickHeartbeat,
                    this.heartbeatIntervalMs, this.heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        }

        void stopHeartbeat() {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdownNow();
                heartbeatExecutor = null;
            }
        }

        private void tickHeartbeat() {
            if (stopped || !open) return;
            long now = System.currentTimeMillis();
            // 心跳超时（无 ACK），主动断开重连，防止连接"假死"
            if (now - lastHeartbeatAck > heartbeatIntervalMs * 3) {
                log.warn("QQ 网关心跳超时（" + (now - lastHeartbeatAck) / 1000 + "s 无 ACK），主动断开重连");
                botLog.logSystem("QQ 网关心跳超时，主动断开重连");
                closeInternal();
                scheduleReconnect();
                return;
            }
            sendHeartbeat();
        }

        // ---------- WebSocket.Listener ----------

        @Override
        public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            this.open = true;
            this.lastRecvTime = System.currentTimeMillis();
            this.lastHeartbeatAck = System.currentTimeMillis();
            botLog.logSystem("QQ 网关 WebSocket 已连接");
            log.info("QQ 网关 WebSocket 已连接");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                this.lastRecvTime = System.currentTimeMillis();
                try {
                    JsonObject payload = gson.fromJson(message, JsonObject.class);
                    if (payload != null) {
                        handleGatewayPayload(this, payload);
                    }
                } catch (Exception e) {
                    log.error("处理网关消息异常: " + e.getMessage(), e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            this.lastRecvTime = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            this.lastRecvTime = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            this.open = false;
            stopHeartbeat();
            botLog.logSystem("QQ 网关已关闭: " + statusCode + " " + reason);
            log.info("QQ 网关已关闭: " + statusCode);
            if (statusCode == 4014) {
                logIntentsHint("网关关闭 4014 disallowed intents");
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.open = false;
            stopHeartbeat();
            log.error("QQ 网关错误: " + error.getMessage(), error);
            scheduleReconnect();
        }
    }

    /**
     * 网关连接被拒（4014 disallowed intents / op=9 Invalid Session）时的排查提示。
     * 常见原因：intents 中订阅了平台未授权的 Intent（如 INTERACTION 消息互动需在开放平台申请开通）。
     */
    private void logIntentsHint(String cause) {
        String hint = "QQ 网关鉴权被拒（" + cause + "）。若 intents 配置包含未开通的事件（如 INTERACTION 等），"
                + "请到 QQ 开放平台确认/申请对应权限，或在 config/adapters.json 中移除未授权的 intent 后重启。"
                + "当前 intents: " + Integer.toBinaryString(intents);
        log.warn(hint);
        botLog.logSystem("⚠ " + hint);
    }

    /**
     * 处理网关下行 payload（opcode 分发）。
     */
    private void handleGatewayPayload(GatewayClient gw, JsonObject payload) {
        int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
        switch (op) {
            case 10 -> { // Hello：携带心跳间隔，随后鉴权
                JsonObject d = payload.has("d") && payload.get("d").isJsonObject()
                        ? payload.getAsJsonObject("d") : null;
                long interval = d != null && d.has("heartbeat_interval")
                        ? d.get("heartbeat_interval").getAsLong() : 40000;
                gw.startHeartbeat(interval);
                if (!sessionId.isEmpty()) {
                    gw.sendResume();
                } else {
                    gw.sendIdentify();
                }
            }
            case 11 -> gw.lastHeartbeatAck = System.currentTimeMillis(); // Heartbeat ACK
            case 1 -> gw.sendHeartbeat(); // 服务端请求心跳
            case 0 -> handleDispatch(gw, payload); // Dispatch
            case 7 -> { // Reconnect：服务端要求重连
                botLog.logSystem("服务端要求重连 (op=7)");
                gw.closeInternal();
                scheduleReconnect();
            }
            case 9 -> { // Invalid Session：重新鉴权连接
                logIntentsHint("Invalid Session (op=9)");
                gw.closeInternal();
                scheduleReconnect();
            }
            default -> log.debug("未处理 opcode: " + op);
        }
    }

    /**
     * 处理 Dispatch 事件。
     */
    private void handleDispatch(GatewayClient gw, JsonObject payload) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) {
            int s = payload.get("s").getAsInt();
            if (s > lastSeq) lastSeq = s;
        }
        String t = payload.has("t") && !payload.get("t").isJsonNull()
                ? payload.get("t").getAsString() : "";
        JsonObject d = payload.has("d") && payload.get("d").isJsonObject()
                ? payload.getAsJsonObject("d") : null;
        if (d == null) return;

        switch (t) {
            case "READY" -> {
                if (d.has("session_id")) sessionId = d.get("session_id").getAsString();
                if (d.has("user") && d.getAsJsonObject("user").has("id")) {
                    selfId = d.getAsJsonObject("user").get("id").getAsString();
                }
                reconnectAttempts = 0;
                botLog.logSystem("QQ Bot 已上线: " + selfId);
                log.info("QQ Bot 已上线: " + selfId);
            }
            case "GROUP_AT_MESSAGE_CREATE" -> handleGroupAtMessage(d);
            case "C2C_MESSAGE_CREATE" -> handleC2cMessage(d);
            case "INTERACTION_CREATE" -> handleInteractionCreate(d);
            case "GROUP_ADD_ROBOT" -> botLog.logSystem("机器人被添加到新群: "
                    + optString(d, "group_openid", "?"));
            case "GROUP_DEL_ROBOT" -> botLog.logSystem("机器人被移出群: "
                    + optString(d, "group_openid", "?"));
            default -> log.debug("未处理事件: " + t);
        }
    }

    // ==================== 事件处理 ====================

    /**
     * 群聊 @ 消息事件。
     */
    private void handleGroupAtMessage(JsonObject d) {
        String groupOpenid = optString(d, "group_openid", "");
        if (groupOpenid.isEmpty()) return;
        String msgId = optString(d, "id", "");
        String content = optString(d, "content", "");
        JsonObject author = d.has("author") && d.get("author").isJsonObject()
                ? d.getAsJsonObject("author") : new JsonObject();
        String senderOpenid = author.has("member_openid") ? author.get("member_openid").getAsString()
                : author.has("user_openid") ? author.get("user_openid").getAsString() : "";
        String nickname = author.has("username") ? author.get("username").getAsString()
                : author.has("member_card") ? author.get("member_card").getAsString() : "未知";
        if (senderOpenid.isEmpty()) return;

        String trimmed = (removeAt ? stripMention(content) : content).trim();
        Long groupId = getGroupId(groupOpenid);
        // 新消息 = 新的被动回复上下文（msg_seq 从 1 重新计数）
        lastGroupMsg.put(groupOpenid, new LastMsg(msgId, msgId, System.currentTimeMillis()));

        log.info("[QQ群:" + groupOpenid + "] [openid:" + senderOpenid + "] " + nickname + ": " + trimmed);
        handleGroupMessage(groupId, senderOpenid, nickname, trimmed);
    }

    /**
     * 单聊（C2C）消息事件。
     */
    private void handleC2cMessage(JsonObject d) {
        String msgId = optString(d, "id", "");
        String content = optString(d, "content", "");
        JsonObject author = d.has("author") && d.get("author").isJsonObject()
                ? d.getAsJsonObject("author") : new JsonObject();
        String openid = author.has("user_openid") ? author.get("user_openid").getAsString() : "";
        String nickname = author.has("username") ? author.get("username").getAsString() : "未知";
        if (openid.isEmpty()) return;

        lastC2cMsg.put(openid, new LastMsg(msgId, msgId, System.currentTimeMillis()));
        log.info("[QQ私聊:" + openid + "] " + nickname + ": " + content);
        handlePrivateMessage(openid, nickname, content);
    }

    /**
     * 消息交互（按钮）回调事件。
     *
     * <p>用户点击消息上的按钮后触发，事件结构：</p>
     * <pre>
     * { "t":"INTERACTION_CREATE", "d":{ "application_id":"..", "group_openid":"..", "type":"0",
     *     "data":{ "button_id":"..", "button_data":"..", "user_openid":"..", "group_openid":"..",
     *              "msg_id":"..", "id":"互动ID" } } }
     * </pre>
     * button_data 约定为游戏指令（如「签到」），此处补全 "/" 前缀后走正常指令管线执行；
     * 同时尽力回传互动接口，避免按钮一直"转圈"。
     */
    private void handleInteractionCreate(JsonObject d) {
        JsonObject data = d.has("data") && d.get("data").isJsonObject()
                ? d.getAsJsonObject("data") : null;
        if (data == null) return;

        String type = optString(d, "type", "0");
        if (!"0".equals(type)) {
            log.debug("暂不支持的消息交互类型: " + type);
            return;
        }

        String buttonId = optString(data, "button_id", "");
        String buttonData = optString(data, "button_data", "");
        String userOpenid = optString(data, "user_openid", "");
        String groupOpenid = optString(data, "group_openid", "");
        String interactionId = optString(data, "id", "");
        if (interactionId.isEmpty()) {
            interactionId = optString(d, "id", "");
        }
        if (userOpenid.isEmpty()) return;

        // 回传互动接口（尽力而为，失败不影响指令执行）
        if (!interactionId.isEmpty()) {
            ackInteraction(interactionId);
        }

        // button_data → 指令（缺 "/" 前缀则补全）
        String cmd = buttonData == null || buttonData.isBlank() ? "" : buttonData.trim();
        if (cmd.isEmpty()) {
            log.warn("按钮无绑定指令: button_id=" + buttonId);
            return;
        }
        if (!cmd.startsWith("/")) {
            cmd = "/" + cmd;
        }
        log.info("[QQ按钮] " + userOpenid + " 点击 " + buttonId + " → " + cmd
                + (groupOpenid.isEmpty() ? "" : "（群:" + groupOpenid + "）"));

        if (!groupOpenid.isEmpty()) {
            handleGroupMessage(getGroupId(groupOpenid), userOpenid, "按钮互动", cmd);
        } else {
            handlePrivateMessage(userOpenid, "按钮互动", cmd);
        }
    }

    /** 互动回传：POST /interactions/{interaction_id}，让按钮结束"转圈"状态 */
    private void ackInteraction(String interactionId) {
        JsonObject body = new JsonObject();
        body.addProperty("code", 0);
        body.add("data", new JsonObject());
        postApi("/interactions/" + interactionId, body);
    }

    /**
     * 安全读取 JsonObject 字符串字段（兼容旧版 Gson，无 optString）。
     */
    private static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        return obj.get(key).getAsString();
    }

    /**
     * 将群 openid 映射为内部 Long 群号（跨重启会变化，仅用于本次运行内路由）。
     */
    private Long getGroupId(String groupOpenid) {
        Long id = groupIdByOpenid.get(groupOpenid);
        if (id == null) {
            id = nextGroupId.getAndIncrement();
            Long existing = groupIdByOpenid.putIfAbsent(groupOpenid, id);
            if (existing != null) id = existing;
            openidByGroupId.put(id, groupOpenid);
        }
        return id;
    }

    /**
     * 移除群消息中的机器人 @ 占位符（形如 &lt;@!xxx&gt;）。
     */
    private String stripMention(String content) {
        return content.replaceAll("<@!?[0-9a-zA-Z_\\-]+>", "").trim();
    }

    // ==================== 消息路由（复用现有业务逻辑） ====================

    private void handlePrivateMessage(String senderQq, String senderNickname, String rawMessage) {
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, null);

        // 黑名单拦截（与 OneBot ws_server 模式一致，覆盖 QQ 官方通道）
        if (blacklistService.isBlacklisted(senderQq)) {
            return;
        }

        QqBinding binding = bindingService.findByQq(senderQq);
        if (binding != null) {
            processOfflineReward(senderQq, binding.getUserId());
        }

        PendingSession pending = pendingSessions.get(senderQq);
        if (pending != null) {
            handlePendingFlow(senderQq, pending, trimmed);
            return;
        }

        // 私聊里的非指令文本（例如玩家直接发用户名/密码想"登录"）此前被静默忽略，
        // 玩家完全得不到反馈，只能反复重试；这里给出明确的下一步指引。
        if (!isCommand(trimmed)) {
            if (trimmed.isEmpty()) return;
            sendPrivateMsg(this, selfId, senderQq,
                    "未识别的输入。" + (binding == null
                            ? "你还没有绑定账号：\n注册新角色: /注册 <角色名>\n绑定已有账号: /绑定"
                            : "你是已绑定的玩家，发送 /帮助 查看全部指令。"));
            return;
        }

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            sendPrivateMsg(this, selfId, senderQq, "操作太频繁，请稍后再试");
            return;
        }
        dispatchCommand(senderQq, senderNickname, null, parsed[0], parsed[1]);
    }

    private void handleGroupMessage(Long groupId, String senderQq, String senderNickname, String rawMessage) {
        String trimmed = rawMessage.trim();
        StatsCollector.getInstance().recordMessage(senderQq, groupId);

        // 黑名单拦截（与 OneBot ws_server 模式一致，覆盖 QQ 官方通道）
        if (blacklistService.isBlacklisted(senderQq)) {
            return;
        }

        QqBinding binding = bindingService.findByQq(senderQq);
        if (binding != null) {
            processOfflineReward(senderQq, binding.getUserId());
        }

        if (!isCommand(trimmed)) return;

        String[] parsed = parseCommand(trimmed);
        if (!RateLimiter.allow("qq:" + senderQq, 10, 60)) {
            replyToSource(this, selfId, senderQq, groupId, "操作太频繁，请稍后再试");
            return;
        }

        Command command = CommandRegistry.get(parsed[0]);
        if (command != null && command.isPrivateOnly()) {
            replyToSource(this, selfId, senderQq, groupId, "请私聊机器人使用此指令，保护您的账户安全。");
            return;
        }
        dispatchCommand(senderQq, senderNickname, groupId, parsed[0], parsed[1]);
    }

    // ==================== 指令分发 ====================

    private void dispatchCommand(String senderQq, String senderNickname, Long groupId, String cmd, String arg) {
        StatsCollector.getInstance().recordCommand(cmd, groupId);

        // "/cancel" 是向导流程的保留取消词（待处理向导在上游已拦截）；
        // 无待取消操作时给出提示，避免误触其他指令或静默无响应
        if ("cancel".equalsIgnoreCase(cmd)) {
            replyToSource(this, selfId, senderQq, groupId, "当前没有待取消的操作。");
            return;
        }

        Command command = CommandRegistry.get(cmd);
        if (command == null) {
            // 命令存在但所属内置模块已停用：给出提示而非静默忽略
            Command raw = CommandRegistry.getRaw(cmd);
            if (raw != null && !raw.isModuleEnabled()) {
                replyToSource(this, selfId, senderQq, groupId, "该玩法系统已停用，暂时无法使用。");
            }
            return;
        }

        String permission = command.getPermission();
        if (permission != null) {
            QqBinding b = bindingService.findByQq(senderQq);
            if (b == null) {
                replyToSource(this, selfId, senderQq, groupId,
                        "请先注册或绑定账号。\n注册: /register <角色名>\n绑定: /bind");
                return;
            }
            if (!PermissionService.hasPermission(b.getUserId(), permission)) {
                replyToSource(this, selfId, senderQq, groupId, "权限不足，你无权使用此功能。");
                return;
            }
        }

        OneBotClientCommandContext ctx = new OneBotClientCommandContext(
                this, selfId, senderQq, senderNickname, groupId, arg, this, this,
                bindingService::findByQq);
        // 插件事件：命令执行
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMMAND)
                        .data("command", cmd)
                        .data("arg", arg == null ? "" : arg)
                        .data("senderQq", senderQq)
                        .data("groupId", groupId == null ? 0L : groupId)
                        .build());
        command.execute(ctx);
    }

    private boolean isCommand(String msg) {
        if (commandPrefix != null && !commandPrefix.isEmpty()) {
            return msg.startsWith(commandPrefix);
        }
        return msg.startsWith("/") || msg.startsWith(".") || msg.startsWith("！") || msg.startsWith("!");
    }

    private String[] parseCommand(String raw) {
        String cmdLine = raw;
        if (commandPrefix != null && !commandPrefix.isEmpty()) {
            if (cmdLine.startsWith(commandPrefix)) {
                cmdLine = "/" + cmdLine.substring(commandPrefix.length());
            }
        } else {
            if (cmdLine.startsWith(".")) cmdLine = "/" + cmdLine.substring(1);
            if (cmdLine.startsWith("！")) cmdLine = "/" + cmdLine.substring(1);
            if (cmdLine.startsWith("!")) cmdLine = "/" + cmdLine.substring(1);
        }
        String[] parts = cmdLine.substring(1).split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";
        return new String[]{cmd, arg};
    }

    // ==================== 离线奖励 ====================

    private void processOfflineReward(String senderQq, Long userId) {
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

        sendPrivateMsg(this, selfId, senderQq, sb.toString());
    }

    // ==================== 消息发送（QQ REST API） ====================

    @Override
    public void replyToSource(OneBotSession session, String selfId, String senderQq, Long groupId, String message) {
        if (groupId != null) {
            sendGroupMsg(session, selfId, groupId, message);
        } else {
            sendPrivateMsg(session, selfId, senderQq, message);
        }
    }

    @Override
    public void sendPrivateMsg(OneBotSession session, String selfId, String targetQq, String message) {
        sendC2cMessage(targetQq, message);
    }

    @Override
    public void sendGroupMsg(OneBotSession session, String selfId, Long groupId, String message) {
        String groupOpenid = openidByGroupId.get(groupId);
        if (groupOpenid == null) {
            log.warn("未知群ID: " + groupId + "，无法发送群消息");
            return;
        }
        sendGroupMessage(groupOpenid, message);
    }

    /**
     * 发送单聊（C2C）消息。被动回复窗口（60 分钟）内携带 msg_id。
     */
    private void sendC2cMessage(String openid, String message) {
        if (openid == null || openid.isEmpty()) return;
        String token = getAccessToken();
        if (token.isEmpty()) return;

        for (String chunk : splitMessage(message)) {
            JsonObject body = new JsonObject();
            body.addProperty("content", chunk);
            body.addProperty("msg_type", 0);
            enrichC2cPassive(body, openid);
            postApi("/v2/users/" + openid + "/messages", body);
            botLog.logSend(openid, gson.toJson(body));
        }
    }

    /**
     * 发送群聊消息。被动回复窗口（5 分钟）内携带 msg_id + msg_seq；无被动条件时明确告警。
     */
    private void sendGroupMessage(String groupOpenid, String message) {
        if (groupOpenid == null || groupOpenid.isEmpty()) return;
        String token = getAccessToken();
        if (token.isEmpty()) return;

        for (String chunk : splitMessage(message)) {
            JsonObject body = new JsonObject();
            body.addProperty("content", chunk);
            body.addProperty("msg_type", 0);
            enrichGroupPassive(body, groupOpenid);
            postApi("/v2/groups/" + groupOpenid + "/messages", body);
            botLog.logSend("group:" + groupOpenid, gson.toJson(body));
        }
    }

    /**
     * 群聊被动回复增强：窗口（5 分钟）内为请求体补充 {@code msg_id} + {@code msg_seq}。
     *
     * <p>{@code msg_seq} 由本端生成并针对同一 {@code msg_id} 递增；缺失会被 QQ 平台以
     * {@code 40054005 消息被去重，请检查请求msgseq} 拒收（历史 bug：这里只在入站事件携带
     * msg_seq 时才写入，而入站事件从不携带，导致所有回复发出即被拒）。</p>
     *
     * <p>超出窗口时不带被动字段直接发送，平台会以「主动消息无权限」拒绝，故此处明确告警。</p>
     */
    private void enrichGroupPassive(JsonObject body, String groupOpenid) {
        LastMsg last = lastGroupMsg.get(groupOpenid);
        boolean inWindow = last != null
                && System.currentTimeMillis() - last.timestamp <= GROUP_PASSIVE_WINDOW_MS;
        if (!inWindow) {
            log.warn("群聊回复超出被动回复窗口（需 @ 机器人后 " + (GROUP_PASSIVE_WINDOW_MS / 60000)
                    + " 分钟内回复；否则需要到 QQ 开放平台开通「主动消息」权限），group=" + groupOpenid
                    + " last=" + (last == null ? "无" : (System.currentTimeMillis() - last.timestamp) + "ms前"));
            return;
        }
        if (!last.msgId.isEmpty()) {
            body.addProperty("msg_id", last.msgId);
        }
        int seq = last.nextReplySeq();
        body.addProperty("msg_seq", seq);
        if (last.eventId != null && !last.eventId.isEmpty()) {
            body.addProperty("event_id", last.eventId);
        }
        if (seq > MAX_PASSIVE_REPLIES) {
            log.warn("同一消息的被动回复已达 " + seq + " 次（平台上限 " + MAX_PASSIVE_REPLIES
                    + " 次），后续回复可能被拒；请等待玩家发送新消息，group=" + groupOpenid);
        }
        log.debug("群聊被动回复: group=" + groupOpenid + " msg_id=" + last.msgId
                + " msg_seq=" + seq + " event_id=" + last.eventId);
    }

    /**
     * 单聊（C2C）被动回复增强：窗口（60 分钟）内补充 {@code msg_id} + {@code msg_seq}。
     * 与群聊同理：{@code msg_seq} 必须由本端按 {@code msg_id} 递增生成，否则被平台去重拒收。
     */
    private void enrichC2cPassive(JsonObject body, String openid) {
        LastMsg last = lastC2cMsg.get(openid);
        boolean inWindow = last != null
                && System.currentTimeMillis() - last.timestamp <= C2C_PASSIVE_WINDOW_MS;
        if (!inWindow) {
            log.warn("单聊回复超出被动回复窗口（" + (C2C_PASSIVE_WINDOW_MS / 60000)
                    + " 分钟）：需玩家先发一条消息，或到 QQ 开放平台开通「主动消息」权限，openid=" + openid);
            return;
        }
        if (!last.msgId.isEmpty()) {
            body.addProperty("msg_id", last.msgId);
        }
        int seq = last.nextReplySeq();
        body.addProperty("msg_seq", seq);
        if (last.eventId != null && !last.eventId.isEmpty()) {
            body.addProperty("event_id", last.eventId);
        }
        if (seq > MAX_PASSIVE_REPLIES) {
            log.warn("同一消息的被动回复已达 " + seq + " 次（平台上限 " + MAX_PASSIVE_REPLIES
                    + " 次），后续回复可能被拒；请等待玩家发送新消息，openid=" + openid);
        }
        log.debug("C2C 被动回复: " + openid + " msg_id=" + last.msgId + " msg_seq=" + seq
                + " event_id=" + last.eventId);
    }

    // ==================== 消息交互（按钮） ====================

    /**
     * 群聊发送带按钮的消息（消息交互）。按钮点击后触发 INTERACTION_CREATE 事件。
     */
    public void sendGroupMsgWithButtons(Long groupId, String text, List<QqButton> buttons) {
        String groupOpenid = openidByGroupId.get(groupId);
        if (groupOpenid == null) {
            log.warn("未知群ID: " + groupId + "，无法发送按钮消息");
            return;
        }
        sendGroupMessageWithKeyboard(groupOpenid, text, java.util.Collections.singletonList(buttons));
    }

    /**
     * 群聊发送带多行按钮的消息。
     */
    public void sendGroupMsgWithKeyboard(Long groupId, String text, List<List<QqButton>> rows) {
        String groupOpenid = openidByGroupId.get(groupId);
        if (groupOpenid == null) {
            log.warn("未知群ID: " + groupId + "，无法发送按钮消息");
            return;
        }
        sendGroupMessageWithKeyboard(groupOpenid, text, rows);
    }

    /**
     * 单聊（C2C）发送带按钮的消息。
     */
    public void sendPrivateMsgWithButtons(String openid, String text, List<QqButton> buttons) {
        sendC2cMessageWithKeyboard(openid, text, java.util.Collections.singletonList(buttons));
    }

    /**
     * 单聊（C2C）发送带多行按钮的消息。
     */
    public void sendPrivateMsgWithKeyboard(String openid, String text, List<List<QqButton>> rows) {
        sendC2cMessageWithKeyboard(openid, text, rows);
    }

    private void sendC2cMessageWithKeyboard(String openid, String text, List<List<QqButton>> rows) {
        if (openid == null || openid.isEmpty()) return;
        String token = getAccessToken();
        if (token.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("content", text == null ? "" : text);
        body.addProperty("msg_type", 0);
        body.add("keyboard", buildKeyboard(rows));
        enrichC2cPassive(body, openid);
        postApi("/v2/users/" + openid + "/messages", body);
        botLog.logSend("c2c:" + openid, gson.toJson(body));
    }

    private void sendGroupMessageWithKeyboard(String groupOpenid, String text, List<List<QqButton>> rows) {
        if (groupOpenid == null || groupOpenid.isEmpty()) return;
        String token = getAccessToken();
        if (token.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("content", text == null ? "" : text);
        body.addProperty("msg_type", 0);
        body.add("keyboard", buildKeyboard(rows));
        enrichGroupPassive(body, groupOpenid);
        postApi("/v2/groups/" + groupOpenid + "/messages", body);
        botLog.logSend("group:" + groupOpenid, gson.toJson(body));
    }

    /**
     * 构建 keyboard 结构：
     * <pre>
     * keyboard: { content: { rows: [ { buttons: [ { id, render_data:{label,style}, action:{type:2, permission:{type:2}, enter, data} } ] } ] } }
     * </pre>
     */
    private static JsonObject buildKeyboard(List<List<QqButton>> rows) {
        JsonObject keyboard = new JsonObject();
        JsonObject content = new JsonObject();
        JsonArray rowArr = new JsonArray();
        if (rows != null) {
            for (List<QqButton> row : rows) {
                if (row == null || row.isEmpty()) continue;
                JsonObject rowObj = new JsonObject();
                JsonArray btnArr = new JsonArray();
                for (QqButton b : row) {
                    btnArr.add(buildButton(b));
                }
                rowObj.add("buttons", btnArr);
                rowArr.add(rowObj);
            }
        }
        content.add("rows", rowArr);
        keyboard.add("content", content);
        return keyboard;
    }

    private static JsonObject buildButton(QqButton b) {
        JsonObject btn = new JsonObject();
        btn.addProperty("id", b.id);
        JsonObject renderData = new JsonObject();
        renderData.addProperty("label", b.label);
        renderData.addProperty("style", b.style);
        btn.add("render_data", renderData);

        JsonObject action = new JsonObject();
        if (b.url != null && !b.url.isEmpty()) {
            // 0=跳转链接
            action.addProperty("type", 0);
            action.addProperty("url", b.url);
        } else {
            // 2=回调互动（无弹窗）；data 作为 button_data 回传；permission 2=所有人可点
            action.addProperty("type", 2);
            action.addProperty("enter", b.enter);
            if (b.data != null) {
                action.addProperty("data", b.data);
            }
            JsonObject permission = new JsonObject();
            permission.addProperty("type", 2);
            action.add("permission", permission);
        }
        btn.add("action", action);
        return btn;
    }

    /**
     * 调用 QQ 开放平台 HTTP API。
     */
    private void postApi(String path, JsonObject body) {
        try {
            String token = getAccessToken();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("QQ API 调用失败 [" + path + "] HTTP " + resp.statusCode() + ": " + resp.body());
                // 群消息发送失败：多为被动回复条件不满足或未开通主动消息权限
                if (path.contains("/groups/") && path.endsWith("/messages")) {
                    botLog.logSystem("群消息发送失败（HTTP " + resp.statusCode() + "）："
                            + "群聊仅支持 @ 机器人后 5 分钟内的被动回复（需携带 msg_seq），"
                            + "超出窗口或需主动推送请到 QQ 开放平台申请「主动消息」权限。");
                }
            }
        } catch (Exception e) {
            log.error("QQ API 调用异常 [" + path + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 超长消息按 MAX_CONTENT_LENGTH 分段，优先在换行处切分。
     */
    private List<String> splitMessage(String message) {
        List<String> chunks = new ArrayList<>();
        if (message == null || message.isEmpty()) {
            return chunks;
        }
        if (message.length() <= MAX_CONTENT_LENGTH) {
            chunks.add(message);
            return chunks;
        }
        int start = 0;
        while (start < message.length()) {
            int end = Math.min(start + MAX_CONTENT_LENGTH, message.length());
            if (end < message.length()) {
                int newline = message.lastIndexOf('\n', end - 1);
                if (newline > start) {
                    end = newline + 1;
                }
            }
            chunks.add(message.substring(start, end));
            start = end;
        }
        return chunks;
    }

    // ==================== OneBotSession ====================

    /**
     * 兼容 OneBot 动作 JSON：将 OneBot 动作转换为 QQ REST 调用。
     * 仅支持 send_private_msg / send_group_msg，其余动作忽略。
     */
    @Override
    public void send(String json) {
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj == null) return;
            String action = obj.has("action") ? obj.get("action").getAsString() : "";
            JsonObject params = obj.has("params") && obj.get("params").isJsonObject()
                    ? obj.getAsJsonObject("params") : null;
            if (params == null) return;
            String message = params.has("message") ? params.get("message").getAsString() : "";
            switch (action) {
                case "send_private_msg" -> sendC2cMessage(params.get("user_id").getAsString(), message);
                case "send_group_msg" -> {
                    if (params.has("group_id")) {
                        String gid = params.get("group_id").getAsString();
                        if (params.get("group_id").isJsonPrimitive()
                                && params.get("group_id").getAsJsonPrimitive().isNumber()) {
                            String openid = openidByGroupId.get(params.get("group_id").getAsLong());
                            if (openid != null) gid = openid;
                        }
                        sendGroupMessage(gid, message);
                    }
                }
                default -> log.debug("QQ 适配器不支持的 OneBot action: " + action);
            }
        } catch (Exception e) {
            log.error("转换 OneBot action 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getSelfId() {
        return selfId;
    }

    @Override
    public boolean isOpen() {
        return gateway != null && gateway.isOpen();
    }

    @Override
    public void close() {
        if (gateway != null) {
            gateway.closeInternal();
        }
    }

    // ==================== OneBotAccountFlowClient ====================

    @Override
    public void handleRegister(OneBotSession session, String selfId, String senderQq, String arg, Long sourceGroupId) {
        QqBinding existingBinding = bindingService.findByQq(senderQq);
        if (existingBinding != null) {
            if (ServiceRegistry.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                try {
                    PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(
                            existingBinding.getUserId(), arg.trim());
                    String guideMsg = NewbieGuideService.getWelcomeMessage(player);
                    replyToSource(session, selfId, senderQq, sourceGroupId, guideMsg);
                    actionLog.logCreatePlayer(existingBinding.getUserId(), player.getName());
                } catch (RuntimeException e) {
                    replyToSource(session, selfId, senderQq, sourceGroupId,
                            "创建角色失败: " + e.getMessage());
                }
                return;
            }
            replyToSource(session, selfId, senderQq, sourceGroupId,
                    "你已注册角色，无需重复注册。如需重来请先用 /unbind 解绑。");
            return;
        }
        String name = arg.trim();
        if (name.isEmpty()) {
            replyToSource(session, selfId, senderQq, sourceGroupId,
                    "用法: /register <角色名>\n密码将在下一步通过私聊安全发送");
            return;
        }
        String[] parts = name.split("\\s+", 2);
        name = parts[0].trim();
        if (name.length() > 16) {
            replyToSource(session, selfId, senderQq, sourceGroupId, "角色名不能超过16个字");
            return;
        }
        if (isUsernameExists(name)) {
            replyToSource(session, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }
        PendingSession ps = new PendingSession("register", sourceGroupId);
        ps.username = name;
        ps.state = "WAITING_PASSWORD";
        pendingSessions.put(senderQq, ps);
        replyToSource(session, selfId, senderQq, sourceGroupId,
                "角色名【" + name + "】可用！\n请在私聊中发送你的密码（不少于6位）\n(输入 /cancel 取消)");
    }

    @Override
    public void handleBind(OneBotSession session, String selfId, String senderQq) {
        if (bindingService.findByQq(senderQq) != null) {
            QqBinding b = bindingService.findByQq(senderQq);
            sendPrivateMsg(session, selfId, senderQq,
                    "你已绑定账号 (用户ID: " + b.getUserId() + ")，/unbind 可解绑。");
            return;
        }
        PendingSession ps = new PendingSession();
        pendingSessions.put(senderQq, ps);
        sendPrivateMsg(session, selfId, senderQq,
                "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 /cancel 取消)");
    }

    @Override
    public void handleUnbind(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号。");
            return;
        }
        try {
            bindingService.unbindByQq(senderQq);
            sendPrivateMsg(session, selfId, senderQq, "解绑成功！");
        } catch (RuntimeException e) {
            sendPrivateMsg(session, selfId, senderQq, "解绑失败: " + e.getMessage());
        }
    }

    @Override
    public void handleChangePassword(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        PendingSession ps = new PendingSession("changePassword", null);
        ps.state = "WAITING_OLD_PASSWORD";
        pendingSessions.put(senderQq, ps);
        sendPrivateMsg(session, selfId, senderQq,
                "===== 修改密码 =====\n请输入当前密码：\n(输入 /cancel 取消)");
    }

    @Override
    public void handleDeleteAccount(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        PendingSession ps = new PendingSession("deleteAccount", null);
        ps.state = "WAITING_DELETE_CONFIRM";
        pendingSessions.put(senderQq, ps);
        sendPrivateMsg(session, selfId, senderQq,
                "===== 注销账号 =====\n⚠ 此操作不可逆！\n注销后将删除你的角色、物品、灵石等全部数据！\n\n请输入账号密码确认注销：\n(输入 /cancel 取消)");
    }

    // ==================== Pending Flow ====================

    private void handlePendingFlow(String senderQq, PendingSession ps, String message) {
        if ("/cancel".equalsIgnoreCase(message.trim())) {
            pendingSessions.remove(senderQq);
            // 向导流程只会在私聊里推进，因此回执也走私聊（群里那条消息的被动回复窗口多半已过期）
            sendPrivateMsg(this, selfId, senderQq, "已取消。");
            return;
        }
        switch (ps.state) {
            case "WAITING_USERNAME":
                ps.username = message.trim();
                ps.state = "WAITING_PASSWORD";
                sendPrivateMsg(this, selfId, senderQq, "请输入密码：\n(输入 /cancel 取消)");
                break;
            case "WAITING_OLD_PASSWORD":
                handleOldPasswordInput(senderQq, ps, message.trim());
                break;
            case "WAITING_NEW_PASSWORD":
                handleNewPasswordInput(senderQq, ps, message.trim());
                break;
            case "WAITING_DELETE_CONFIRM":
                handleDeleteConfirm(senderQq, ps, message.trim());
                break;
            case "WAITING_PASSWORD":
                String passwordInput = message.trim();
                if (passwordInput.length() < 6) {
                    sendPrivateMsg(this, selfId, senderQq, "密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if ("register".equals(ps.type)) {
                    ps.password = passwordInput;
                    // QQ 官方用户以 openid 身份进入，注册无需邮箱验证（跳过 verify_code 流程）
                    doRegisterComplete(senderQq, ps, passwordInput, null, null);
                    pendingSessions.remove(senderQq);
                } else {
                    doBindComplete(senderQq, ps, passwordInput);
                    pendingSessions.remove(senderQq);
                }
                break;
            case "WAITING_EMAIL":
                String emailInput = message.trim();
                if (emailInput.isEmpty()) {
                    sendPrivateMsg(this, selfId, senderQq, "邮箱不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if (!isValidEmail(emailInput)) {
                    sendPrivateMsg(this, selfId, senderQq, "邮箱格式不正确，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                ps.email = emailInput.toLowerCase();
                try {
                    VerificationCodeService verificationCodeService = new VerificationCodeService();
                    if (!verificationCodeService.canSendCode(ps.email)) {
                        sendPrivateMsg(this, selfId, senderQq, "发送过于频繁，请稍后再试：\n(输入 /cancel 取消)");
                        return;
                    }
                    String code = verificationCodeService.generateAndStoreCode(ps.email);
                    EmailService.sendVerificationCode(ps.email, code);
                    ps.state = "WAITING_CODE";
                    sendPrivateMsg(this, selfId, senderQq, "验证码已发送至邮箱，请输入验证码：\n(输入 /cancel 取消)");
                } catch (Exception e) {
                    sendPrivateMsg(this, selfId, senderQq, "发送验证码失败: " + e.getMessage() + "\n请重新输入邮箱：\n(输入 /cancel 取消)");
                }
                break;
            case "WAITING_CODE":
                String codeInput = message.trim();
                if (codeInput.isEmpty()) {
                    sendPrivateMsg(this, selfId, senderQq, "验证码不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                VerificationCodeService vcService = new VerificationCodeService();
                if (!vcService.verifyCode(ps.email, codeInput)) {
                    sendPrivateMsg(this, selfId, senderQq, "验证码错误或已过期，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                doRegisterComplete(senderQq, ps, ps.password, ps.email, codeInput);
                pendingSessions.remove(senderQq);
                break;
        }
    }

    private void handleOldPasswordInput(String senderQq, PendingSession ps, String oldPassword) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = getUsernameByUserId(b.getUserId());
        if (username == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        CredentialCheck check = checkCredentials(username, oldPassword);
        if (check.userId == null) {
            sendPrivateMsg(this, selfId, senderQq, check.error + "\n请重新输入当前密码：\n(输入 /cancel 取消)");
            return;
        }
        ps.state = "WAITING_NEW_PASSWORD";
        sendPrivateMsg(this, selfId, senderQq, "请输入新密码（不少于6位）：\n(输入 /cancel 取消)");
    }

    private void handleNewPasswordInput(String senderQq, PendingSession ps, String newPassword) {
        if (newPassword.length() < 6) {
            sendPrivateMsg(this, selfId, senderQq, "新密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        try {
            String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            updatePassword(b.getUserId(), hashedPassword);
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "密码修改成功！");
            actionLog.logCustom(b.getUserId(), "系统", "修改密码", "成功");
        } catch (RuntimeException e) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "密码修改失败: " + e.getMessage());
        }
    }

    private void handleDeleteConfirm(String senderQq, PendingSession ps, String password) {
        QqBinding b = bindingService.findByQq(senderQq);
        if (b == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = getUsernameByUserId(b.getUserId());
        if (username == null) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        CredentialCheck check = checkCredentials(username, password);
        if (check.userId == null) {
            sendPrivateMsg(this, selfId, senderQq, check.error + "\n请重新输入密码：\n(输入 /cancel 取消)");
            return;
        }
        try {
            PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(check.userId);
            if (player != null) {
                ServiceRegistry.getPlayerService().deletePlayer(player.getId());
            }
            deleteUser(check.userId);
            bindingService.unbindByQq(senderQq);
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "账号已注销。感谢您的陪伴，修仙之路后会有期！");
        } catch (RuntimeException e) {
            pendingSessions.remove(senderQq);
            sendPrivateMsg(this, selfId, senderQq, "注销失败: " + e.getMessage());
        }
    }

    /**
     * 完成注册：仅在<b>私聊</b>上下文调用（密码只能私聊输入），因此结果一律私聊回执。
     *
     * <p>历史 bug：注册可以从群里 {@code /注册} 发起，密码却在私聊输入，完成回执却发回群里，
     * 复用了 {@code /注册} 那条消息的被动回复上下文（同一 msg_id/msg_seq）而被平台去重拒收，
     * 玩家永远等不到注册结果；同时也会把账号信息暴露在群里。</p>
     */
    private void doRegisterComplete(String senderQq, PendingSession ps,
                                    String password, String email, String code) {
        if (isUsernameExists(ps.username)) {
            sendPrivateMsg(this, selfId, senderQq,
                    "角色名【" + ps.username + "】已被占用，请重新注册。");
            return;
        }
        if (bindingService.findByQq(senderQq) != null) {
            sendPrivateMsg(this, selfId, senderQq,
                    "你已注册角色，无需重复注册。");
            return;
        }
        Long userId = null;
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            userId = insertUser(ps.username, hashedPassword, email);
            if (userId == null) {
                sendPrivateMsg(this, selfId, senderQq, "注册失败，请稍后重试。");
                return;
            }
            PermissionService.assignDefaultRole(userId);
            bindingService.bind(senderQq, userId);
            PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(userId, ps.username);
            String guideMsg = NewbieGuideService.getWelcomeMessage(player);
            sendPrivateMsg(this, selfId, senderQq, guideMsg);
            actionLog.logCreatePlayer(userId, ps.username);
        } catch (RuntimeException e) {
            if (userId != null) {
                try { bindingService.unbindByQq(senderQq); } catch (Exception ignored) {}
                try { deleteUser(userId); } catch (Exception ignored) {}
            }
            sendPrivateMsg(this, selfId, senderQq, "注册失败: " + e.getMessage());
        }
    }

    private void doBindComplete(String senderQq, PendingSession ps, String password) {
        CredentialCheck check = checkCredentials(ps.username, password);
        if (check.userId == null) {
            sendPrivateMsg(this, selfId, senderQq, check.error + "\n重试请发送 /bind。");
            return;
        }
        try {
            bindingService.bind(senderQq, check.userId);
            // 绑定成功：不再伪造 <openid>@qq.com 邮箱（QQ官方身份与邮箱无关，且不应覆盖用户已有邮箱）
            sendPrivateMsg(this, selfId, senderQq,
                    "绑定成功！\n用户名: " + ps.username + "\n用户ID: " + check.userId);
        } catch (RuntimeException e) {
            sendPrivateMsg(this, selfId, senderQq, "绑定失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 凭据校验结果。
     *
     * <p>历史 bug：限流（同一用户名 5 次/分钟）与账号锁定（连续 5 次错误，锁 15 分钟）都直接返回
     * {@code null}，被上层一律显示成「用户名或密码错误」，玩家在正确密码下也会反复看到「密码错误」，
     * 且完全不知道自己在被限流/锁定。这里把原因显式带回给调用方。</p>
     */
    private static final class CredentialCheck {
        /** 校验通过时的用户 ID；失败为 null */
        final Long userId;
        /** 面向玩家的失败提示（成功时为 null） */
        final String error;

        CredentialCheck(Long userId, String error) {
            this.userId = userId;
            this.error = error;
        }
    }

    private CredentialCheck checkCredentials(String username, String password) {
        if (username == null || username.isEmpty()) {
            return new CredentialCheck(null, "用户名不能为空。");
        }
        String rateLimitKey = "qq_official_login_" + username.toLowerCase();
        if (!RateLimiter.allow(rateLimitKey, 5, 60)) {
            log.warn("QQ官方登录尝试过于频繁，已限流: username=" + username);
            return new CredentialCheck(null, "尝试过于频繁，请 1 分钟后再试。");
        }

        String sql = "SELECT id, password, locked_until FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp lockedUntil = rs.getTimestamp("locked_until");
                    long now = System.currentTimeMillis();
                    if (lockedUntil != null && lockedUntil.getTime() > now) {
                        long minutes = Math.max(1, (lockedUntil.getTime() - now + 59_999) / 60_000);
                        log.warn("QQ官方登录被拒：账号已锁定 " + minutes + " 分钟, username=" + username);
                        return new CredentialCheck(null,
                                "该账号因多次输错密码已被临时锁定，请约 " + minutes + " 分钟后再试（不是密码错误）。");
                    }

                    if (BCrypt.checkpw(password, rs.getString("password"))) {
                        resetFailedAttempts(rs.getLong("id"));
                        return new CredentialCheck(rs.getLong("id"), null);
                    } else {
                        incrementFailedAttempts(rs.getLong("id"));
                    }
                }
            }
        } catch (SQLException e) { log.error("验证用户凭据失败", e); }
        return new CredentialCheck(null, "用户名或密码错误。");
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
}