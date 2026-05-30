package com.mtxgdn.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ApiResponse {

    private static final Gson gson = new Gson();

    private int code;
    private String message;
    private JsonObject data;

    public ApiResponse() {
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

    public String getToken() {
        if (data != null && data.has("token")) {
            return data.get("token").getAsString();
        }
        return null;
    }

    public String getDataString(String key) {
        if (data != null && data.has(key)) {
            return data.get(key).getAsString();
        }
        return null;
    }

    public long getDataLong(String key) {
        if (data != null && data.has(key)) {
            return data.get(key).getAsLong();
        }
        return 0;
    }

    public int getDataInt(String key) {
        if (data != null && data.has(key)) {
            return data.get(key).getAsInt();
        }
        return 0;
    }

    public boolean isSuccess() {
        return code == 0;
    }

    public static ApiResponse fromJson(String json) {
        return gson.fromJson(json, ApiResponse.class);
    }

    public String toJson() {
        return gson.toJson(this);
    }
}
