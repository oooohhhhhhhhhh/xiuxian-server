package com.mtxgdn.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class GameMessage {

    private static final Gson gson = new Gson();

    private long msgId;
    private String type;
    private int code;
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

    public static GameMessage error(long msgId, String type, GameErrorCode errorCode) {
        return new GameMessage(msgId, type, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static GameMessage error(long msgId, String type, int code, String message) {
        return new GameMessage(msgId, type, code, message, null);
    }

    public static JsonObject restOk(String message, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("code", 0);
        json.addProperty("message", message != null ? message : "ok");
        if (data != null) {
            json.add("data", data);
        }
        return json;
    }

    public static JsonObject restOk(JsonObject data) {
        return restOk("ok", data);
    }

    public static JsonObject restError(GameErrorCode errorCode) {
        JsonObject json = new JsonObject();
        json.addProperty("code", errorCode.getCode());
        json.addProperty("message", errorCode.getMessage());
        return json;
    }

    public static JsonObject restError(int code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("code", code);
        json.addProperty("message", message);
        return json;
    }
}
