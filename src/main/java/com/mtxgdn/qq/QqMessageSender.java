package com.mtxgdn.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.onebot.OneBotSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.mtxgdn.qq.QqSupport.C2C_PASSIVE_WINDOW_MS;
import static com.mtxgdn.qq.QqSupport.GROUP_PASSIVE_WINDOW_MS;
import static com.mtxgdn.qq.QqSupport.MAX_CONTENT_LENGTH;
import static com.mtxgdn.qq.QqSupport.MAX_PASSIVE_REPLIES;
import static com.mtxgdn.qq.QqSupport.botLog;
import static com.mtxgdn.qq.QqSupport.gson;
import static com.mtxgdn.qq.QqSupport.log;

/**
 * QQ 官方通道的消息发送域：私聊 / 群聊发消息、发图（分段）、按钮键盘、@ 提及清理，
 * 以及被动回复上下文（{@code msg_id} + 自增 {@code msg_seq}）的维护。
 *
 * <p><b>为什么这一域必须单独成类</b>：被动回复是本通道最容易踩坑的地方，
 * 它的状态（{@code lastC2cMsg} / {@code lastGroupMsg}）必须在"收到入站消息"与
 * "发出回复"两端共享，而这两端分属事件分发与消息发送两个动作。把状态和它的
 * 唯一写入者（{@link #trackC2cInbound} / {@link #trackGroupInbound}）与唯一读取者
 * （{@link #enrichC2cPassive} / {@link #enrichGroupPassive}）放在同一个类里，
 * 才能保证拆分不会把它拆成两半。</p>
 *
 * <p><b>原样保留的防坑逻辑</b>（拆分时一行未改，这是踩过坑才有的）：</p>
 * <ul>
 *   <li><b>msg_seq 由本端生成并递增</b>：同一个 {@code msg_id} 下每次回复 +1，
 *       缺失或重复会被平台以 {@code 40054005 消息被去重，请检查请求msgseq} 拒收。
 *       历史 bug 就是 msg_seq 从入站事件里读（恒为 0）；</li>
 *   <li><b>被动回复窗口</b>：单聊 60 分钟、群聊 5 分钟，超出窗口时明确告警
 *       （平台会以「主动消息无权限」拒绝）；</li>
 *   <li><b>回复次数上限</b>：同一 {@code msg_id} 超过 {@code MAX_PASSIVE_REPLIES} 次时告警；</li>
 *   <li><b>超长消息分段</b>：按 {@code MAX_CONTENT_LENGTH} 优先在换行处切分，
 *       避免被平台按 4000 字节截断。</li>
 * </ul>
 *
 * <p>本类不判断"该不该发"（那是路由与账号流程的事），只负责"怎么发出去"。</p>
 */
final class QqMessageSender {

    private final QqApiClient api;

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

    QqMessageSender(QqApiClient api) {
        this.api = api;
    }

    // ==================== 入站消息登记（被动回复上下文） ====================

    /**
     * 登记一条新的入站群消息：新消息 = 新的被动回复上下文（msg_seq 从 1 重新计数）。
     */
    void trackGroupInbound(String groupOpenid, String msgId) {
        lastGroupMsg.put(groupOpenid, new LastMsg(msgId, msgId, System.currentTimeMillis()));
    }

    /**
     * 登记一条新的入站单聊消息：同样重置该 openid 的被动回复上下文。
     */
    void trackC2cInbound(String openid, String msgId) {
        lastC2cMsg.put(openid, new LastMsg(msgId, msgId, System.currentTimeMillis()));
    }

    // ==================== 群 openid <-> 内部 Long 群号 ====================

    /**
     * 将群 openid 映射为内部 Long 群号（跨重启会变化，仅用于本次运行内路由）。
     */
    Long getGroupId(String groupOpenid) {
        Long id = groupIdByOpenid.get(groupOpenid);
        if (id == null) {
            id = nextGroupId.getAndIncrement();
            Long existing = groupIdByOpenid.putIfAbsent(groupOpenid, id);
            if (existing != null) id = existing;
            openidByGroupId.put(id, groupOpenid);
        }
        return id;
    }

    // ==================== 消息发送（QQ REST API） ====================

    void replyToSource(OneBotSession session, String selfId, String senderQq, Long groupId, String message) {
        if (groupId != null) {
            sendGroupMsg(session, selfId, groupId, message);
        } else {
            sendPrivateMsg(session, selfId, senderQq, message);
        }
    }

    void sendPrivateMsg(OneBotSession session, String selfId, String targetQq, String message) {
        sendC2cMessage(targetQq, message);
    }

    void sendGroupMsg(OneBotSession session, String selfId, Long groupId, String message) {
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
        String token = api.getAccessToken();
        if (token.isEmpty()) return;

        for (String chunk : splitMessage(message)) {
            JsonObject body = new JsonObject();
            body.addProperty("content", chunk);
            body.addProperty("msg_type", 0);
            enrichC2cPassive(body, openid);
            api.postApi("/v2/users/" + openid + "/messages", body);
            botLog.logSend(openid, gson.toJson(body));
        }
    }

    /**
     * 发送群聊消息。被动回复窗口（5 分钟）内携带 msg_id + msg_seq；无被动条件时明确告警。
     */
    private void sendGroupMessage(String groupOpenid, String message) {
        if (groupOpenid == null || groupOpenid.isEmpty()) return;
        String token = api.getAccessToken();
        if (token.isEmpty()) return;

        for (String chunk : splitMessage(message)) {
            JsonObject body = new JsonObject();
            body.addProperty("content", chunk);
            body.addProperty("msg_type", 0);
            enrichGroupPassive(body, groupOpenid);
            api.postApi("/v2/groups/" + groupOpenid + "/messages", body);
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
    void sendGroupMsgWithButtons(Long groupId, String text, List<QqButton> buttons) {
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
    void sendGroupMsgWithKeyboard(Long groupId, String text, List<List<QqButton>> rows) {
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
    void sendPrivateMsgWithButtons(String openid, String text, List<QqButton> buttons) {
        sendC2cMessageWithKeyboard(openid, text, java.util.Collections.singletonList(buttons));
    }

    /**
     * 单聊（C2C）发送带多行按钮的消息。
     */
    void sendPrivateMsgWithKeyboard(String openid, String text, List<List<QqButton>> rows) {
        sendC2cMessageWithKeyboard(openid, text, rows);
    }

    private void sendC2cMessageWithKeyboard(String openid, String text, List<List<QqButton>> rows) {
        if (openid == null || openid.isEmpty()) return;
        String token = api.getAccessToken();
        if (token.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("content", text == null ? "" : text);
        body.addProperty("msg_type", 0);
        body.add("keyboard", buildKeyboard(rows));
        enrichC2cPassive(body, openid);
        api.postApi("/v2/users/" + openid + "/messages", body);
        botLog.logSend("c2c:" + openid, gson.toJson(body));
    }

    private void sendGroupMessageWithKeyboard(String groupOpenid, String text, List<List<QqButton>> rows) {
        if (groupOpenid == null || groupOpenid.isEmpty()) return;
        String token = api.getAccessToken();
        if (token.isEmpty()) return;

        JsonObject body = new JsonObject();
        body.addProperty("content", text == null ? "" : text);
        body.addProperty("msg_type", 0);
        body.add("keyboard", buildKeyboard(rows));
        enrichGroupPassive(body, groupOpenid);
        api.postApi("/v2/groups/" + groupOpenid + "/messages", body);
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

    // ==================== OneBotSession.send ====================

    /**
     * 兼容 OneBot 动作 JSON：将 OneBot 动作转换为 QQ REST 调用。
     * 仅支持 send_private_msg / send_group_msg，其余动作忽略。
     */
    void handleOneBotAction(String json) {
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
}
