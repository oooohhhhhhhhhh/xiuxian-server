package com.mtxgdn.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.i18n.I18n;

public class GameMessage {

    private static final Gson gson = new Gson();

    private long msgId;
    private String type;
    private int code;

    /**
     * i18n key（可空）。
     *
     * <p>有 key 时新客户端可自行本地化；老客户端忽略此字段即可，
     * 因为它们读的 {@link #message} 仍然是当前语言下的译文。</p>
     */
    private String key;

    private String message;
    private JsonObject data;

    public GameMessage() {
        this.code = 0;
        this.message = "ok";
    }

    public GameMessage(long msgId, String type, int code, String message, JsonObject data) {
        this.msgId = msgId;
        this.type = type;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 带 key 的完整构造器。
     *
     * <p>{@code message} 仍是**当前请求语言下已译好的文案**，
     * 这样老客户端无需任何改动就能继续工作。</p>
     */
    public GameMessage(long msgId, String type, int code, String key, String message, JsonObject data) {
        this.msgId = msgId;
        this.type = type;
        this.code = code;
        this.key = key;
        this.message = message;
        this.data = data;
    }

    public long getMsgId() {
        return msgId;
    }

    public void setMsgId(long msgId) {
        this.msgId = msgId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    /** i18n key，可为 null（纯动态拼接的消息没有对应 key）。 */
    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
    }

    public boolean isOk() {
        return code == 0;
    }

    public static GameMessage fromJson(String json) {
        return gson.fromJson(json, GameMessage.class);
    }

    public String toJson() {
        return gson.toJson(this);
    }

    public static GameMessage create(String type, JsonObject data) {
        return new GameMessage(0, type, 0, "ok", data);
    }

    public static GameMessage create(long msgId, String type, JsonObject data) {
        return new GameMessage(msgId, type, 0, "ok", data);
    }

    public static GameMessage ok(long msgId, String type, JsonObject data) {
        return new GameMessage(msgId, type, 0, "ok", data);
    }

    public static GameMessage ok(long msgId, String type, String message, JsonObject data) {
        return new GameMessage(msgId, type, 0, message, data);
    }

    /**
     * 用预置错误码构造错误响应。
     *
     * <p>WS 出口的推荐用法：自动带上 key 与本地化文案，调用方不必逐个改。
     * code 仍取数字码，老客户端行为不变。</p>
     */
    public static GameMessage error(long msgId, String type, GameErrorCode errorCode) {
        return new GameMessage(msgId, type, errorCode.getCode(),
                errorCode.getKey(), errorCode.getLocalized(), null);
    }

    public static GameMessage error(long msgId, String type, int code, String message) {
        return new GameMessage(msgId, type, code, message, null);
    }

    /**
     * 带 key 的错误响应。
     *
     * <p>{@code message} 取当前语言译文；语言包缺 key 时回落到 {@code key} 本身，
     * 便于在测试与联调中立刻发现漏翻译。生产环境应通过
     * {@code GET /admin/i18n/status} 提前补齐。</p>
     */
    public static GameMessage errorKeyed(long msgId, String type, String key, JsonObject data) {
        return new GameMessage(msgId, type, 0, key, I18n.t(key), data);
    }

    /** 带 key 的成功响应。 */
    public static GameMessage okKeyed(long msgId, String type, String key, JsonObject data) {
        return new GameMessage(msgId, type, 0, key, I18n.t(key), data);
    }

    public static JsonObject restOk(String message, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("code", 0);
        json.addProperty("key", "common.ok");
        json.addProperty("message", message != null ? message : "ok");
        if (data != null) {
            json.add("data", data);
        }
        return json;
    }

    public static JsonObject restOk(JsonObject data) {
        return restOk("ok", data);
    }

    /**
     * 成功响应，带 key。
     *
     * <p>{@code message} 仍是当前语言译文；{@code key} 让新客户端可自行本地化。</p>
     */
    public static JsonObject restOk(String key, String message, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("code", 0);
        json.addProperty("key", key);
        json.addProperty("message", message);
        if (data != null) {
            json.add("data", data);
        }
        return json;
    }

    public static JsonObject restError(GameErrorCode errorCode) {
        JsonObject json = new JsonObject();
        json.addProperty("code", errorCode.getCode());
        json.addProperty("key", errorCode.getKey());
        json.addProperty("message", errorCode.getLocalized());
        return json;
    }

    public static JsonObject restError(int code, String message) {
        return restError(code, null, message);
    }

    /** 带 key 的错误响应，供各端点把"错误码 + 可本地化文案"一起下发。 */
    public static JsonObject restError(int code, String key, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("code", code);
        if (key != null) {
            json.addProperty("key", key);
        }
        json.addProperty("message", message);
        return json;
    }
}
