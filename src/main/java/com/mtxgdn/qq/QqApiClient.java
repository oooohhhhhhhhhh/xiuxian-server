package com.mtxgdn.qq;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.mtxgdn.qq.QqSupport.API_BASE;
import static com.mtxgdn.qq.QqSupport.TOKEN_URL;
import static com.mtxgdn.qq.QqSupport.botLog;
import static com.mtxgdn.qq.QqSupport.gson;
import static com.mtxgdn.qq.QqSupport.log;
import static com.mtxgdn.qq.QqSupport.optString;

/**
 * QQ 官方开放平台 HTTP 客户端：access_token 的获取 / 缓存 / 提前刷新，以及 API 调用。
 *
 * <p>职责边界就是"往外发 HTTP"：它不知道消息怎么组装（那是 {@link QqMessageSender}），
 * 也不知道事件从哪来（那是 {@link QqGatewayClient} / {@link QqEventDispatcher}），
 * 只提供两件事：</p>
 * <ul>
 *   <li>{@link #getAccessToken()}：取缓存的 access_token，临近过期时同步刷新；</li>
 *   <li>{@link #postApi(String, JsonObject)}：带 {@code Authorization: QQBot <token>} 的
 *       开放平台 API 调用与错误码处理。</li>
 * </ul>
 *
 * <p><b>access_token 缓存与提前刷新一行未改</b>（这是踩过坑的地方）：</p>
 * <ul>
 *   <li>缓存值 {@code accessToken} 与到期时间 {@code tokenExpireAt} 仍是两个 volatile 字段；</li>
 *   <li>{@link #getAccessToken()} 仍在"空值或距到期不足 60 秒"时同步刷新，
 *       因此网关连接、消息发送、API 调用读到的永远是同一个缓存值；</li>
 *   <li>{@link #fetchAccessToken()} 仍在拿到新 token 后按 {@code expiresIn - 60}
 *       （最小 60 秒）挂一次定时刷新，失败只记日志、不抛异常 —— 定时链路断掉会让
 *       适配器在 token 过期后静默失联，所以这里的 try/catch 与告警文案都保留原样。</li>
 * </ul>
 *
 * <p>原实现里 {@code fetchAccessToken} 是 {@code synchronized} 实例方法，这里同样
 * {@code synchronized}（锁的是本对象，与拆分前"锁适配器实例"等效：一个适配器只有
 * 一份 token 缓存，不存在第二个竞争者）。</p>
 */
final class QqApiClient {

    private final QqAdapterContext ctx;

    // access_token 缓存
    private volatile String accessToken = "";
    private volatile long tokenExpireAt = 0;

    QqApiClient(QqAdapterContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 获取 access_token（本地缓存过期时同步刷新）。
     */
    String getAccessToken() {
        if (accessToken.isEmpty() || System.currentTimeMillis() >= tokenExpireAt - 60_000) {
            fetchAccessToken();
        }
        return accessToken;
    }

    /**
     * 缓存的 access_token 原值（<b>不</b>触发刷新）。
     *
     * <p>网关的 Identify / Resume 直接用它：拆分前这两处读的就是字段本身，
     * 换成 {@link #getAccessToken()} 会在建连时插入一次同步刷新，属于行为改动。</p>
     */
    String cachedAccessToken() {
        return accessToken;
    }

    /**
     * 调用官方接口获取 access_token，并在过期前定时刷新。
     * 文档: https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/access-token.html
     */
    synchronized void fetchAccessToken() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("appId", ctx.appId);
            body.addProperty("clientSecret", ctx.clientSecret);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = ctx.httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            JsonObject obj = gson.fromJson(resp.body(), JsonObject.class);
            if (obj != null && obj.has("access_token")) {
                accessToken = obj.get("access_token").getAsString();
                long expiresIn = obj.has("expires_in") && !obj.get("expires_in").isJsonNull()
                        ? obj.get("expires_in").getAsLong() : 7200;
                tokenExpireAt = System.currentTimeMillis() + expiresIn * 1000;
                botLog.logSystem("已获取 access_token，有效期 " + expiresIn + " 秒");
                log.info("已获取 access_token，有效期 " + expiresIn + " 秒");

                // 在过期前 60 秒刷新
                ScheduledExecutorService scheduler = ctx.scheduler;
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

    /**
     * 调用 QQ 开放平台 HTTP API。
     */
    void postApi(String path, JsonObject body) {
        try {
            String token = getAccessToken();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + path))
                    .header("Authorization", "QQBot " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = ctx.httpClient.send(req, HttpResponse.BodyHandlers.ofString());
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
}
