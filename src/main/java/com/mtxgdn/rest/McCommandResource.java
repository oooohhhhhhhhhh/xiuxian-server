package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.minecraft.adapter.McCommandResult;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.RateLimiter;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * MC 辅助插件转发指令的 REST 入口。
 * <p>
 * MC 端的 XiuxianBridgePlugin 将 /xiuxian &lt;子命令&gt; 通过 HTTP POST 转发到此端点，
 * 此端点通过 {@link MinecraftAdapter#handleMcCommand} 执行逻辑后，将回复文本返回给插件。
 */
@Path("/mc-command")
public class McCommandResource {

    private static final Gson gson = new Gson();
    private static final GameLogger log = GameLogger.getLogger("McCommandApi");

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleCommand(String body) {
        JsonObject json;
        try {
            json = gson.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            return error("无效的 JSON 请求");
        }

        String mcName = stringOrNull(json, "mcName");
        String mcUuid = stringOrNull(json, "mcUuid");
        String cmd = stringOrNull(json, "command");
        String args = stringOrNull(json, "args");

        if (mcName == null || mcUuid == null || cmd == null) {
            return error("缺少必要参数: mcName, mcUuid, command");
        }

        cmd = cmd.toLowerCase();
        if (args == null) args = "";
        log.info("[MC-API] " + mcName + " → /" + cmd + " " + args);

        // 限流
        if (!RateLimiter.allow("mc-api:" + mcUuid, 10, 60)) {
            return error("操作太频繁，请稍后再试");
        }

        MinecraftAdapter adapter = MinecraftAdapter.getInstance();
        if (adapter == null || !adapter.isRunning()) {
            return error("Minecraft 适配器未启动");
        }

        McCommandResult result = adapter.handleMcCommand(mcName, mcUuid, cmd, args);
        if (result == null) {
            return ok("");
        }

        if (result.isOk()) {
            return ok(result.getText());
        } else {
            return error(result.getText());
        }
    }

    private static Response ok(String response) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("response", response);
        return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
    }

    private static Response error(String msg) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", msg);
        return Response.ok(gson.toJson(json), MediaType.APPLICATION_JSON).build();
    }

    private static String stringOrNull(JsonObject json, String key) {
        if (json == null || !json.has(key)) return null;
        return json.get(key).getAsString();
    }
}
