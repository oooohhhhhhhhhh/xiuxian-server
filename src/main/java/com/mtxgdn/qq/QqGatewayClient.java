package com.mtxgdn.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mtxgdn.qq.QqSupport.DEFAULT_GATEWAY;
import static com.mtxgdn.qq.QqSupport.SANDBOX_GATEWAY;
import static com.mtxgdn.qq.QqSupport.botLog;
import static com.mtxgdn.qq.QqSupport.gson;
import static com.mtxgdn.qq.QqSupport.log;

/**
 * QQ 官方 WebSocket 网关客户端：建连、Identify / Resume / 心跳、opcode 分发与重连退避。
 *
 * <p>拆分前这一整块（含 {@code GatewayClient} 这个 {@link WebSocket.Listener} 内部类）
 * 与消息发送、事件处理、账号流程混在同一个类里，读代码时很难看出"哪一段只跟连接有关"。
 * 现在连接生命周期自成一域：它<b>只</b>关心"连接是否活着"，事件内容一律交给
 * {@link QqEventDispatcher}。</p>
 *
 * <p><b>原样保留的防坑逻辑</b>（一行未改）：</p>
 * <ul>
 *   <li><b>心跳超时主动断开</b>：超过 {@code heartbeatIntervalMs * 3} 没收到 ACK 就主动
 *       close + 重连，避免连接"假死"（TCP 还在、事件全丢）；</li>
 *   <li><b>指数退避重连</b>：{@code min(base << min(attempts,6), 60000)} 毫秒，
 *       并且用 {@link AtomicBoolean} 保证同一时刻只有一条重连链在排队；</li>
 *   <li><b>Resume 优先</b>：有 {@code session_id} 时发 Resume（op=6）而不是重新 Identify，
 *       否则会丢掉断线期间的事件；</li>
 *   <li><b>4014 / op=9 排查提示</b>：intents 未开通时给出可操作的告警文案。</li>
 * </ul>
 *
 * <p>网关会话状态（socket、心跳任务、重连计数）留在这里；而 {@code session_id} /
 * {@code lastSeq} / {@code selfId} 放在 {@link QqAdapterContext}，因为事件分发器也要写它们
 * （READY 与 Dispatch 序号）—— 放在一处才能保证心跳发出的 seq 与事件处理看到的是同一个。</p>
 */
final class QqGatewayClient {

    private final QqAdapterContext ctx;
    private final QqApiClient api;
    private final QqEventDispatcher events;

    private volatile Session gateway;

    // 重连状态
    private volatile int reconnectAttempts = 0;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    QqGatewayClient(QqAdapterContext ctx, QqApiClient api, QqEventDispatcher events) {
        this.ctx = ctx;
        this.api = api;
        this.events = events;
    }

    // ==================== 连接生命周期 ====================

    /** 连接是否可用（供 {@code isOpen()} / {@code isWsConnected()} 使用）。 */
    boolean isConnected() {
        Session gw = gateway;
        return gw != null && gw.isOpen();
    }

    /** 关闭底层 WebSocket（不置空引用，供 {@code OneBotSession.close()} 使用）。 */
    void closeConnection() {
        Session gw = gateway;
        if (gw != null) {
            gw.closeInternal();
        }
    }

    /** 关闭并释放网关引用（供 {@code stop()} 使用）。 */
    void shutdown() {
        Session gw = gateway;
        if (gw != null) {
            gw.closeInternal();
            gateway = null;
        }
    }

    /** READY / Webhook 启动时把重连计数清零。 */
    void resetReconnectAttempts() {
        reconnectAttempts = 0;
    }

    void connect() {
        if (ctx.stopped) return;
        if (api.cachedAccessToken().isEmpty()) {
            log.error("access_token 为空，无法连接网关");
            botLog.logSystem("access_token 为空，无法连接网关");
            scheduleReconnect();
            return;
        }
        try {
            String url = ctx.sandbox ? SANDBOX_GATEWAY
                    : (ctx.gatewayUrl != null && !ctx.gatewayUrl.isEmpty() ? ctx.gatewayUrl : DEFAULT_GATEWAY);
            Session gw = new Session();
            gateway = gw;
            botLog.logSystem("正在连接 QQ 网关: " + url);
            log.info("正在连接 QQ 网关: " + url);
            ctx.httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(url), gw)
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
        long base = ctx.reconnectIntervalMs;
        int exp = Math.min(reconnectAttempts, 6);
        return Math.min(base * (1L << exp), 60000L);
    }

    private void scheduleReconnect() {
        if (ctx.stopped) return;
        if (reconnecting.compareAndSet(false, true)) {
            long delay = computeReconnectDelay();
            reconnectAttempts++;
            botLog.logSystem("QQ 网关断开，" + (delay / 1000) + "秒后重连（第" + reconnectAttempts + "次）");
            log.warn("QQ 网关断开，" + (delay / 1000) + "秒后重连（第" + reconnectAttempts + "次）");
            ScheduledExecutorService scheduler = ctx.scheduler;
            if (scheduler != null) {
                scheduler.schedule(() -> {
                    reconnecting.set(false);
                    if (!ctx.stopped) {
                        connect();
                    }
                }, delay, TimeUnit.MILLISECONDS);
            } else {
                reconnecting.set(false);
            }
        }
    }

    /**
     * 网关连接被拒（4014 disallowed intents / op=9 Invalid Session）时的排查提示。
     * 常见原因：intents 中订阅了平台未授权的 Intent（如 INTERACTION 消息互动需在开放平台申请开通）。
     */
    private void logIntentsHint(String cause) {
        String hint = "QQ 网关鉴权被拒（" + cause + "）。若 intents 配置包含未开通的事件（如 INTERACTION 等），"
                + "请到 QQ 开放平台确认/申请对应权限，或在 config/adapters.json 中移除未授权的 intent 后重启。"
                + "当前 intents: " + Integer.toBinaryString(ctx.intents);
        log.warn(hint);
        botLog.logSystem("⚠ " + hint);
    }

    /**
     * 处理网关下行 payload（opcode 分发）。
     */
    private void handleGatewayPayload(Session gw, JsonObject payload) {
        int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
        switch (op) {
            case 10 -> { // Hello：携带心跳间隔，随后鉴权
                JsonObject d = payload.has("d") && payload.get("d").isJsonObject()
                        ? payload.getAsJsonObject("d") : null;
                long interval = d != null && d.has("heartbeat_interval")
                        ? d.get("heartbeat_interval").getAsLong() : 40000;
                gw.startHeartbeat(interval);
                if (!ctx.sessionId.isEmpty()) {
                    gw.sendResume();
                } else {
                    gw.sendIdentify();
                }
            }
            case 11 -> gw.lastHeartbeatAck = System.currentTimeMillis(); // Heartbeat ACK
            case 1 -> gw.sendHeartbeat(); // 服务端请求心跳
            case 0 -> events.handleDispatch(payload); // Dispatch
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
     * QQ 网关 WebSocket 客户端会话。
     */
    private class Session implements WebSocket.Listener {

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
            hb.addProperty("d", ctx.lastSeq);
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
            d.addProperty("token", "QQBot " + api.cachedAccessToken());
            d.addProperty("intents", ctx.intents);
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
            d.addProperty("token", "QQBot " + api.cachedAccessToken());
            d.addProperty("session_id", ctx.sessionId);
            d.addProperty("seq", ctx.lastSeq);
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
            if (ctx.stopped || !open) return;
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
}
