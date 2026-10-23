package com.mtxgdn.qq;

import com.google.gson.JsonObject;
import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.onebot.OneBotAccountFlowClient;
import com.mtxgdn.onebot.OneBotMessageSenderClient;
import com.mtxgdn.onebot.OneBotSession;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;

import static com.mtxgdn.qq.QqSupport.botLog;
import static com.mtxgdn.qq.QqSupport.log;

/**
 * QQ 官方开放平台（QQ 机器人）适配器 —— <b>门面</b>。
 *
 * <p>基于 QQ 开放平台 API v2：</p>
 * <ul>
 *   <li>鉴权：通过 AppID + ClientSecret 调用 {@code /app/getAppAccessToken} 获取 access_token
 *       （有效期 7200 秒，自动在过期前刷新），调用开放接口时使用 {@code Authorization: QQBot &lt;token&gt;}</li>
 *   <li>接收：通过 WebSocket 网关（wss://api.sgroup.qq.com/websocket）接收事件，
 *       （Identify/心跳/恢复）协议，支持群聊 @ 消息（GROUP_AT_MESSAGE_CREATE）与单聊消息（C2C_MESSAGE_CREATE）；
 *       {@code mode=webhook} 时改由本地回调服务接收，事件形状相同</li>
 *   <li>发送：通过 HTTP REST 接口发送消息（群聊 /v2/groups/{group_openid}/messages，单聊 /v2/users/{openid}/messages）</li>
 * </ul>
 * 该适配器复用 {@code OneBotClientCommandContext} 与现有账号流程（注册/绑定/注销等）业务逻辑，
 * 通过实现 {@link OneBotSession} / {@link OneBotMessageSenderClient} / {@link OneBotAccountFlowClient} 与业务层解耦。
 *
 * <p><b>为什么这里只剩门面</b>：拆分前它是 1859 行的上帝类，同时扮演六种角色 ——
 * 开放平台 HTTP 客户端、WebSocket 网关会话、事件分发、消息发送、指令路由、账号对话流程。
 * 现按"职责域 + 依赖集合"拆成以下协作类，<b>本类保留为门面</b>：</p>
 * <ul>
 *   <li>{@link QqApiClient}：access_token 获取 / 缓存 / 提前刷新，以及开放平台 API 调用与错误码处理；</li>
 *   <li>{@link QqGatewayClient}：WebSocket 建连、Identify/Resume、心跳、opcode 分发与指数退避重连；</li>
 *   <li>{@link QqEventDispatcher}：Dispatch 事件 → 玩家消息 / 按钮点击（Webhook 与网关共用）；</li>
 *   <li>{@link QqMessageSender}：私聊/群聊发送、分段、按钮键盘、@ 占位符清理、被动回复上下文（msg_id + 自增 msg_seq）；</li>
 *   <li>{@link QqMessageRouter}：黑名单、限流、离线奖励、指令解析与投递；</li>
 *   <li>{@link QqAccountFlow}：注册 / 绑定 / 解绑 / 改密 / 注销的多轮对话状态机与凭据校验；</li>
 *   <li>{@link QqAdapterContext}：配置与运行时状态（AppID、绑定服务、待处理会话、selfId、sessionId、lastSeq 等）的唯一存放处；</li>
 *   <li>{@link QqSupport}：跨域共用的 Gson、日志器、URL/窗口常量、Intent 映射。</li>
 * </ul>
 *
 * <p><b>对外协议零改动</b>：本类实现的三个接口方法、四个带按钮的发送方法、以及
 * {@code start / stop / getAppId / isWsConnected / dispatchWebhookEvent} 的签名与语义
 * 逐字未变（{@code javap -public} 导出前后完全一致），内部一律转调上面的域类。
 * 调用方（{@code AdapterManager}、{@code QqButtonCommand}、{@code PvpCommand} 等）
 * 一行都不用改。</p>
 *
 * <p><b>生命周期留在门面</b>：{@code start} 里创建 {@code HttpClient} 与主调度器、
 * {@code stop} 里按原顺序释放（webhook → 网关 → 调度器 → HttpClient），
 * {@code startWebhook} 里装配本地回调服务 —— 这些属于"服务生命周期"而不是某个业务域，
 * 放在门面才不会让域类之间互相持有对方的资源。</p>
 *
 * <p><b>域类的接线顺序</b>（构造器里一次接好，运行期不再互相 new）：
 * 上下文 → API 客户端 → 发送器 → 账号流程 → 消息路由 → 事件分发 → 网关。
 * 网关与事件分发互指（网关把 op=0 交给分发器，分发器在 READY 时清零网关的重连计数），
 * 因此最后用一次 {@link QqEventDispatcher#attachGateway} 显式收尾。</p>
 */
public class QqOfficialAdapter implements OneBotSession, OneBotMessageSenderClient, OneBotAccountFlowClient {

    /** 配置与运行时状态（AppID、绑定服务、待处理会话、selfId 等只有这一份）。 */
    private final QqAdapterContext ctx = new QqAdapterContext();

    private final QqApiClient apiClient;
    private final QqMessageSender sender;
    private final QqAccountFlow accountFlow;
    private final QqMessageRouter router;
    private final QqEventDispatcher events;
    private final QqGatewayClient gateway;

    public QqOfficialAdapter() {
        this.apiClient = new QqApiClient(ctx);
        this.sender = new QqMessageSender(apiClient);
        this.accountFlow = new QqAccountFlow(ctx, sender);
        this.router = new QqMessageRouter(ctx, sender, accountFlow, this);
        this.events = new QqEventDispatcher(ctx, apiClient, sender, router);
        this.gateway = new QqGatewayClient(ctx, apiClient, events);
        this.events.attachGateway(gateway);
        // 命令上下文会把 session 存下来、回复时再传回 replyToSource：必须是门面自己
        this.ctx.self = this;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动适配器：获取 access_token 并连接 QQ WebSocket 网关。
     */
    public void start(AdapterConfig cfg) {
        if (ctx.started) {
            log.warn("QqOfficialAdapter 已在运行中");
            return;
        }

        ctx.loadConfig(cfg);
        ctx.started = true;
        ctx.stopped = false;

        ctx.httpClient = HttpClient.newHttpClient();
        ctx.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QQBot-Main");
            t.setDaemon(true);
            return t;
        });

        botLog.logSystem("QQ官方适配器启动中，AppID: " + ctx.appId);
        log.info("QQ官方适配器启动，AppID=" + ctx.appId + " id=" + cfg.getId());

        if (ctx.appId.isEmpty() || ctx.clientSecret.isEmpty()) {
            log.error("QQ官方适配器缺少 app_id / client_secret 配置");
            botLog.logSystem("配置错误：缺少 app_id 或 client_secret");
            return;
        }

        apiClient.fetchAccessToken();

        // Webhook 模式：启动本地回调服务（端口必须为 80/443/8080/8443）；否则连接 WebSocket 网关
        if ("webhook".equalsIgnoreCase(ctx.mode)) {
            startWebhook();
        } else {
            gateway.connect();
        }
    }

    /**
     * 启动 QQ 官方 Webhook 回调服务。
     * 官方要求端口必须为 80/443/8080/8443，签名规范见 QqWebhookServer 类注释。
     */
    private void startWebhook() {
        if (!QqWebhookServer.ALLOWED_PORTS.contains(ctx.webhookPort)) {
            log.error("QQ 官方 webhook 端口必须为 80/443/8080/8443，当前配置: " + ctx.webhookPort);
            botLog.logSystem("Webhook 端口非法（必须为 80/443/8080/8443）: " + ctx.webhookPort);
            return;
        }
        try {
            QqWebhookServer srv = new QqWebhookServer(
                    ctx.webhookHost, ctx.webhookPort, ctx.webhookPath, ctx.appId, ctx.clientSecret,
                    ctx.webhookMaxSkewMs, this::dispatchWebhookEvent);
            srv.start();
            ctx.webhookServer = srv;
            // Webhook 模式没有 READY 事件，机器人自身 id 即 AppID
            ctx.selfId = ctx.appId;
            gateway.resetReconnectAttempts();
            botLog.logSystem("QQ 官方适配器 Webhook 模式已启动: " + srv.getListenUrl());
            log.info("QQ 官方适配器 Webhook 模式已启动: " + srv.getListenUrl());
        } catch (java.net.BindException e) {
            log.error("QQ webhook 端口被占用（" + ctx.webhookPort + "）: " + e.getMessage());
            botLog.logSystem("QQ webhook 端口被占用: " + ctx.webhookPort + "（请换用 80/443/8080/8443 中未被占用的端口，或关闭占用该端口的服务）");
        } catch (Exception e) {
            log.error("QQ webhook 启动失败: " + e.getMessage(), e);
            botLog.logSystem("QQ webhook 启动失败: " + e.getMessage());
        }
    }

    /** Webhook 事件入口：与 WebSocket 网关使用相同的 Dispatch 分发（envelope: {t, id, d, s}） */
    public void dispatchWebhookEvent(JsonObject payload) {
        if (payload == null) return;
        events.handleDispatch(payload);
    }

    /**
     * 停止适配器，释放所有资源。
     */
    public void stop() {
        ctx.stopped = true;
        ctx.started = false;

        if (ctx.webhookServer != null) {
            ctx.webhookServer.stop();
            ctx.webhookServer = null;
        }
        gateway.shutdown();
        if (ctx.scheduler != null) {
            ctx.scheduler.shutdownNow();
            ctx.scheduler = null;
        }
        if (ctx.httpClient != null) {
            try {
                ctx.httpClient.close();
            } catch (Exception ignored) {
            }
            ctx.httpClient = null;
        }
        botLog.logSystem("QQ官方适配器已停止");
        log.info("QQ官方适配器已停止");
    }

    /** 本实例对应的机器人 AppID（绑定与身份查找按它隔离）。 */
    public String getAppId() {
        return ctx.appId;
    }

    public boolean isWsConnected() {
        if ("webhook".equalsIgnoreCase(ctx.mode)) {
            return ctx.webhookServer != null && ctx.webhookServer.isRunning();
        }
        return gateway.isConnected();
    }

    // ==================== 消息发送（QQ REST API） ====================

    @Override
    public void replyToSource(OneBotSession session, String selfId, String senderQq, Long groupId, String message) {
        sender.replyToSource(session, selfId, senderQq, groupId, message);
    }

    @Override
    public void sendPrivateMsg(OneBotSession session, String selfId, String targetQq, String message) {
        sender.sendPrivateMsg(session, selfId, targetQq, message);
    }

    @Override
    public void sendGroupMsg(OneBotSession session, String selfId, Long groupId, String message) {
        sender.sendGroupMsg(session, selfId, groupId, message);
    }

    /**
     * 群聊发送带按钮的消息（消息交互）。按钮点击后触发 INTERACTION_CREATE 事件。
     */
    public void sendGroupMsgWithButtons(Long groupId, String text, List<QqButton> buttons) {
        sender.sendGroupMsgWithButtons(groupId, text, buttons);
    }

    /**
     * 群聊发送带多行按钮的消息。
     */
    public void sendGroupMsgWithKeyboard(Long groupId, String text, List<List<QqButton>> rows) {
        sender.sendGroupMsgWithKeyboard(groupId, text, rows);
    }

    /**
     * 单聊（C2C）发送带按钮的消息。
     */
    public void sendPrivateMsgWithButtons(String openid, String text, List<QqButton> buttons) {
        sender.sendPrivateMsgWithButtons(openid, text, buttons);
    }

    /**
     * 单聊（C2C）发送带多行按钮的消息。
     */
    public void sendPrivateMsgWithKeyboard(String openid, String text, List<List<QqButton>> rows) {
        sender.sendPrivateMsgWithKeyboard(openid, text, rows);
    }

    // ==================== OneBotSession ====================

    /**
     * 兼容 OneBot 动作 JSON：将 OneBot 动作转换为 QQ REST 调用。
     * 仅支持 send_private_msg / send_group_msg，其余动作忽略。
     */
    @Override
    public void send(String json) {
        sender.handleOneBotAction(json);
    }

    @Override
    public String getSelfId() {
        return ctx.selfId;
    }

    @Override
    public boolean isOpen() {
        return gateway.isConnected();
    }

    @Override
    public void close() {
        gateway.closeConnection();
    }

    // ==================== OneBotAccountFlowClient ====================

    @Override
    public void handleRegister(OneBotSession session, String selfId, String senderQq, String arg, Long sourceGroupId) {
        accountFlow.handleRegister(session, selfId, senderQq, arg, sourceGroupId);
    }

    @Override
    public void handleBind(OneBotSession session, String selfId, String senderQq) {
        accountFlow.handleBind(session, selfId, senderQq);
    }

    @Override
    public void handleUnbind(OneBotSession session, String selfId, String senderQq) {
        accountFlow.handleUnbind(session, selfId, senderQq);
    }

    @Override
    public void handleChangePassword(OneBotSession session, String selfId, String senderQq) {
        accountFlow.handleChangePassword(session, selfId, senderQq);
    }

    @Override
    public void handleDeleteAccount(OneBotSession session, String selfId, String senderQq) {
        accountFlow.handleDeleteAccount(session, selfId, senderQq);
    }
}
