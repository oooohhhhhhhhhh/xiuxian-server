package com.mtxgdn.qq;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.util.GameLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * QQ 官方开放平台 Webhook 回调服务。
 *
 * <p>QQ 官方要求 webhook 回调端口<b>必须为 80 / 443 / 8080 / 8443</b>，
 * 签名规范见官方文档：
 * <a href="https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html">接口框架 - 签名</a></p>
 *
 * <p>验签算法：</p>
 * <ol>
 *   <li>请求头携带 {@code X-Tsign-Open-App-Id} / {@code X-Tsign-Open-Timestamp} / {@code X-Tsign-Open-Nonce} / {@code X-Tsign-Open-Signature}</li>
 *   <li>待签名字符串 = {@code timestamp + "\n" + nonce + "\n" + body}（body 为原始请求体字符串）</li>
 *   <li>以机器人 AppSecret 为密钥计算 <b>HMAC-SHA256</b>，结果 <b>Base64 编码</b>，与 {@code X-Tsign-Open-Signature} 常量时间比较</li>
 * </ol>
 */
public class QqWebhookServer {

    private static final GameLogger log = GameLogger.getLogger("QQWebhook");
    private static final Gson gson = new Gson();

    /** QQ 官方允许的 webhook 端口 */
    public static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443, 8080, 8443);

    private static final String H_APP_ID = "X-Tsign-Open-App-Id";
    private static final String H_TIMESTAMP = "X-Tsign-Open-Timestamp";
    private static final String H_NONCE = "X-Tsign-Open-Nonce";
    private static final String H_SIGNATURE = "X-Tsign-Open-Signature";

    private final String host;
    private final int port;
    private final String path;
    private final String appId;
    private final String appSecret;
    private final long maxSkewMillis;
    private final Consumer<JsonObject> eventHandler;

    private volatile HttpServer server;

    public QqWebhookServer(String host, int port, String path, String appId, String appSecret,
                           long maxSkewMillis, Consumer<JsonObject> eventHandler) {
        this.host = host == null || host.isBlank() ? "0.0.0.0" : host;
        this.port = port;
        this.path = (path == null || path.isBlank() ? "/qq/webhook"
                : path.startsWith("/") ? path : "/" + path);
        this.appId = appId;
        this.appSecret = appSecret;
        this.maxSkewMillis = maxSkewMillis;
        this.eventHandler = eventHandler;
    }

    public boolean isAllowedPort() {
        return ALLOWED_PORTS.contains(port);
    }

    public String getListenUrl() {
        return "http://" + host + ":" + port + path;
    }

    public boolean isRunning() {
        return server != null;
    }

    /** 启动监听（端口必须为 80/443/8080/8443，否则抛 IllegalArgumentException） */
    public void start() throws IOException {
        if (!isAllowedPort()) {
            throw new IllegalArgumentException(
                    "QQ 官方 webhook 端口必须为 80/443/8080/8443，当前配置: " + port);
        }
        HttpServer srv = HttpServer.create(new InetSocketAddress(host, port), 64);
        srv.createContext(path, this::handle);
        srv.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "QQ-Webhook");
            t.setDaemon(true);
            return t;
        }));
        srv.start();
        server = srv;
        log.info("QQ 官方 Webhook 已监听 " + getListenUrl() + "（AppID: " + appId + "）");
    }

    public void stop() {
        HttpServer srv = server;
        server = null;
        if (srv != null) {
            srv.stop(0);
            log.info("QQ 官方 Webhook 已停止");
        }
    }

    // ==================== 请求处理 ====================

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "method not allowed");
                return;
            }

            String reqAppId = header(exchange, H_APP_ID);
            String timestamp = header(exchange, H_TIMESTAMP);
            String nonce = header(exchange, H_NONCE);
            String signature = header(exchange, H_SIGNATURE);

            if (reqAppId == null || timestamp == null || nonce == null || signature == null) {
                log.warn("QQ webhook 缺少签名头: appId=" + reqAppId
                        + " ts=" + (timestamp != null) + " nonce=" + (nonce != null) + " sig=" + (signature != null));
                respond(exchange, 401, "missing signature headers");
                return;
            }

            // 读取原始请求体（签名与 JSON 解析都基于同一份字节）
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

            // 1. AppId 校验
            if (!appId.equals(reqAppId)) {
                log.warn("QQ webhook AppId 不匹配: 期望=" + appId + " 收到=" + reqAppId);
                respond(exchange, 403, "app id mismatch");
                return;
            }

            // 2. 时间戳新鲜度（防重放，秒/毫秒自适应）
            if (maxSkewMillis > 0 && !isTimestampFresh(timestamp)) {
                log.warn("QQ webhook 时间戳过期: " + timestamp);
                respond(exchange, 401, "timestamp expired");
                return;
            }

            // 3. 签名校验：Base64(HMAC-SHA256(appSecret, timestamp + "\n" + nonce + "\n" + body))
            String expected = sign(appSecret, timestamp + "\n" + nonce + "\n" + bodyStr);
            if (!constantTimeEquals(expected, signature)) {
                log.warn("QQ webhook 签名校验失败: appId=" + reqAppId + " ts=" + timestamp);
                respond(exchange, 401, "invalid signature");
                return;
            }

            // 4. 解析事件（与 WebSocket 网关相同的 envelope: {t, id, d, s}）并派发
            JsonObject payload;
            try {
                payload = gson.fromJson(bodyStr, JsonObject.class);
            } catch (Exception e) {
                respond(exchange, 400, "invalid json");
                return;
            }
            if (payload != null && eventHandler != null) {
                try {
                    eventHandler.accept(payload);
                } catch (Exception e) {
                    log.error("处理 QQ webhook 事件异常: " + e.getMessage(), e);
                }
            }
            respond(exchange, 200, "");
        } catch (Exception e) {
            log.error("QQ webhook 请求处理异常: " + e.getMessage(), e);
            respond(exchange, 500, "internal error");
        }
    }

    /**
     * 签名算法：Base64( HMAC-SHA256(appSecret, 待签名字符串) )。
     * 官方文档: https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/interface-framework/sign.html
     */
    public static String sign(String appSecret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /** 常量时间比较，防时序攻击 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 时间戳新鲜度校验（13 位按毫秒、10 位按秒） */
    private boolean isTimestampFresh(String timestamp) {
        try {
            long raw = Long.parseLong(timestamp.trim());
            long tsMs = String.valueOf(raw).length() >= 13 ? raw : raw * 1000L;
            return Math.abs(System.currentTimeMillis() - tsMs) <= maxSkewMillis;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String header(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        return value != null && !value.isBlank() ? value : null;
    }

    private void respond(HttpExchange exchange, int code, String message) throws IOException {
        if (message == null || message.isEmpty()) {
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
            return;
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
