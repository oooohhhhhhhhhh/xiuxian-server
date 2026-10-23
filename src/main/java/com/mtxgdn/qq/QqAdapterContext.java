package com.mtxgdn.qq;

import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.onebot.QqOfficialBindingService;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * QQ 官方适配器的<b>运行时状态与配置</b>载体。
 *
 * <p><b>为什么需要它</b>：拆分前这些字段全是 {@code QqOfficialAdapter} 的实例字段，
 * 而它们被六种职责同时读写 —— 配置（AppID / Secret / 网关地址 / intents / 指令前缀）
 * 被网关与发送器读；{@code selfId} 被消息路由与发送器读；{@code bindingService} /
 * {@code blacklistService} / {@code pendingSessions} 被路由与账号流程同时读写。
 * 拆成多个协作类之后，如果让每个类各存一份，就会出现"网关读到旧 AppID""路由查不到
 * 刚建立的待处理会话"这类只有线上才发现的错位 —— 所以状态只留一份，放在这里，
 * 各协作类持有同一个引用。</p>
 *
 * <p><b>行为不变</b>：字段的默认值、{@link #loadConfig} 的读取顺序与默认值、
 * {@code bindingService} 按 AppID 重建的时机（配置装载时）都与拆分前逐字相同。
 * {@code httpClient} / {@code scheduler} 仍由门面在 {@code start} 里创建、在
 * {@code stop} 里释放，这里只是它们的存放处。</p>
 *
 * <p><b>没有搬过来的状态</b>：access_token 缓存归 {@link QqApiClient}；
 * 被动回复上下文与群 openid 映射归 {@link QqMessageSender}；网关会话 / 心跳 /
 * 重连计数归 {@link QqGatewayClient}。它们只被单个协作类读写，留在各自域里
 * 才能真正做到"某个域依赖谁、持有什么，一眼看得出来"。</p>
 */
final class QqAdapterContext {

    // ==================== 配置字段 ====================

    String appId = "";
    String clientSecret = "";
    String gatewayUrl = QqSupport.DEFAULT_GATEWAY;
    boolean sandbox = false;
    int intents = 0;
    String commandPrefix = "";
    String sendMode = "text";
    long reconnectIntervalMs = 5000;
    boolean removeAt = true;

    // ---- Webhook 模式配置（mode=webhook 时启用）----
    String mode = "websocket";
    String webhookHost = "0.0.0.0";
    int webhookPort = 8080;
    String webhookPath = "/qq/webhook";
    long webhookMaxSkewMs = 300_000;
    volatile QqWebhookServer webhookServer;

    // ==================== 服务与状态 ====================

    /**
     * 绑定服务：在 {@link #loadConfig(AdapterConfig)} 里按本实例的 app_id 重建，
     * 使绑定与身份查找按机器人隔离（openid 是按 AppID 隔离的）。
     */
    volatile QqOfficialBindingService bindingService = new QqOfficialBindingService();

    /** 黑名单（与 OneBot ws_server 模式一致，覆盖 QQ 官方通道）。拆分前用的是全限定名，这里逐字保留。 */
    final com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();

    /** 注册 / 绑定 / 改密 / 注销等对话流程的待处理会话（按 openid 索引）。 */
    final Map<String, QqPendingSession> pendingSessions = new ConcurrentHashMap<>();

    volatile boolean started = false;
    volatile boolean stopped = false;

    /** HTTP 客户端：由门面在 {@code start} 里创建，access_token 与 API 调用共用。 */
    HttpClient httpClient;

    /** 通道调度器：access_token 定时刷新、网关重连、心跳都挂在它上面。 */
    ScheduledExecutorService scheduler;

    /** 机器人自身 id（READY 事件下发；webhook 模式下即 AppID）。 */
    volatile String selfId = "";

    /** 网关会话 id（Resume 用）。 */
    volatile String sessionId = "";

    /** 网关事件序号（心跳与 Resume 用）。 */
    volatile int lastSeq = 0;

    /**
     * 本适配器自身作为 {@link OneBotSession} 的引用。
     *
     * <p>拆分前各内部方法直接把 {@code this} 当 session 传给 {@code replyToSource} /
     * {@link com.mtxgdn.onebot.command.OneBotClientCommandContext}。命令上下文会把它
     * 存下来、在回复时再传回 {@code replyToSource(session, ...)}，所以这里必须是
     * <b>门面实例</b>，不能传 null 或某个域对象。</p>
     */
    OneBotSession self;

    /** 装载适配器配置（字段读取顺序、默认值、绑定服务重建时机都与拆分前一致）。 */
    void loadConfig(AdapterConfig cfg) {
        this.appId = cfg.getString("app_id", "");
        this.clientSecret = cfg.getString("client_secret", "");
        // 绑定按 AppID 隔离：openid 是按 AppID 下发的，不同机器人（不同 AppID）openid 不同
        this.bindingService = new QqOfficialBindingService(this.appId);
        this.gatewayUrl = cfg.getString("gateway_url", QqSupport.DEFAULT_GATEWAY);
        this.sandbox = cfg.getBoolean("sandbox", false);
        this.intents = QqSupport.parseIntents(cfg.getString("intents", "GROUP_AT_MESSAGE_CREATE,C2C_MESSAGE_CREATE"));
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
}
