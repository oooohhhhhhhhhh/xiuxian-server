package com.mtxgdn.qq;

import com.google.gson.JsonObject;

import static com.mtxgdn.qq.QqSupport.botLog;
import static com.mtxgdn.qq.QqSupport.log;
import static com.mtxgdn.qq.QqSupport.optString;

/**
 * 网关 / Webhook 事件分发域：把 QQ 官方下行的 Dispatch 事件翻译成"一条玩家消息"或"一次按钮点击"，
 * 再交给 {@link QqMessageRouter} 走指令管线。
 *
 * <p>拆分前这些方法（{@code handleDispatch} / {@code handleGroupAtMessage} / {@code handleC2cMessage} /
 * {@code handleInteractionCreate}）散在适配器里，与网关协议、消息发送、账号流程混在一起。
 * 现在它们是"事件 → 业务"的唯一入口，两条接入方式共用同一份代码：</p>
 * <ul>
 *   <li>WebSocket 网关：{@link QqGatewayClient} 收到 op=0 后调用 {@link #handleDispatch};</li>
 *   <li>Webhook 模式：门面的 {@code dispatchWebhookEvent} 直接调用 {@link #handleDispatch}
 *       （envelope 形状相同：{@code {t, id, d, s}}）。</li>
 * </ul>
 *
 * <p><b>两处容易踩坑的地方原样保留</b>：</p>
 * <ol>
 *   <li><b>入站消息即被动回复上下文</b>：每收到一条群聊 / 单聊消息，立刻用它的 {@code msg_id}
 *       覆盖 {@link QqMessageSender} 里的最近消息记录 —— 这是 {@code msg_seq} 从 1 重新计数的
 *       唯一时机，漏掉就会一直沿用上一条消息的序号而被平台去重；</li>
 *   <li><b>事件序号单调递增</b>：{@code s} 只在比当前值大时才写入，否则 Resume 会带着更小的
 *       seq 被网关判定为非法会话。</li>
 * </ol>
 *
 * <p>本类不发送任何业务消息（互动回传的 ACK 除外），也不认识账号流程 —— 那些分别属于
 * {@link QqMessageSender} 与 {@link QqAccountFlow}。</p>
 */
final class QqEventDispatcher {

    private final QqAdapterContext ctx;
    private final QqApiClient api;
    private final QqMessageSender sender;
    private final QqMessageRouter router;

    /**
     * 网关对象，由门面在构造完成后注入。
     *
     * <p>READY 事件要把重连计数清零，而重连计数属于网关；网关又要把 op=0 交给本类分发。
     * 两者互指，构造期无法互相传入，因此这里用一次显式注入打破环，并在门面里接线。</p>
     */
    private QqGatewayClient gateway;

    QqEventDispatcher(QqAdapterContext ctx, QqApiClient api, QqMessageSender sender, QqMessageRouter router) {
        this.ctx = ctx;
        this.api = api;
        this.sender = sender;
        this.router = router;
    }

    /** 门面构造末尾调用：把网关接上（READY 事件需要重置它的重连计数）。 */
    void attachGateway(QqGatewayClient gateway) {
        this.gateway = gateway;
    }

    /**
     * 处理 Dispatch 事件。
     */
    void handleDispatch(JsonObject payload) {
        if (payload.has("s") && !payload.get("s").isJsonNull()) {
            int s = payload.get("s").getAsInt();
            if (s > ctx.lastSeq) ctx.lastSeq = s;
        }
        String t = payload.has("t") && !payload.get("t").isJsonNull()
                ? payload.get("t").getAsString() : "";
        JsonObject d = payload.has("d") && payload.get("d").isJsonObject()
                ? payload.getAsJsonObject("d") : null;
        if (d == null) return;

        switch (t) {
            case "READY" -> {
                if (d.has("session_id")) ctx.sessionId = d.get("session_id").getAsString();
                if (d.has("user") && d.getAsJsonObject("user").has("id")) {
                    ctx.selfId = d.getAsJsonObject("user").get("id").getAsString();
                }
                if (gateway != null) {
                    gateway.resetReconnectAttempts();
                }
                botLog.logSystem("QQ Bot 已上线: " + ctx.selfId);
                log.info("QQ Bot 已上线: " + ctx.selfId);
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

        String trimmed = (ctx.removeAt ? stripMention(content) : content).trim();
        Long groupId = sender.getGroupId(groupOpenid);
        // 新消息 = 新的被动回复上下文（msg_seq 从 1 重新计数）
        sender.trackGroupInbound(groupOpenid, msgId);

        log.info("[QQ群:" + groupOpenid + "] [openid:" + senderOpenid + "] " + nickname + ": " + trimmed);
        router.handleGroupMessage(groupId, senderOpenid, nickname, trimmed);
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

        sender.trackC2cInbound(openid, msgId);
        log.info("[QQ私聊:" + openid + "] " + nickname + ": " + com.mtxgdn.util.LogMasker.mask(content));
        router.handlePrivateMessage(openid, nickname, content);
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
            router.handleGroupMessage(sender.getGroupId(groupOpenid), userOpenid, "按钮互动", cmd);
        } else {
            router.handlePrivateMessage(userOpenid, "按钮互动", cmd);
        }
    }

    /** 互动回传：POST /interactions/{interaction_id}，让按钮结束"转圈"状态 */
    private void ackInteraction(String interactionId) {
        JsonObject body = new JsonObject();
        body.addProperty("code", 0);
        body.add("data", new JsonObject());
        api.postApi("/interactions/" + interactionId, body);
    }

    /**
     * 移除群消息中的机器人 @ 占位符（形如 &lt;@!xxx&gt;）。
     */
    private String stripMention(String content) {
        return content.replaceAll("<@!?[0-9a-zA-Z_\\-]+>", "").trim();
    }
}
