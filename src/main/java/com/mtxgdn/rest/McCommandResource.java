package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.minecraft.adapter.McCommandResult;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.RateLimiter;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;

/**
 * MC 辅助插件转发指令的 REST 入口。
 * <p>
 * MC 端的 XiuxianBridgePlugin 将 /xiuxian &lt;子命令&gt; 通过 HTTP POST 转发到此端点，
 * 此端点通过 {@link MinecraftAdapter#handleMcCommand} 执行逻辑后，将回复文本返回给插件。
 *
 * <h2>鉴权</h2>
 * 这是机器对机器端点，没有玩家 JWT 可用，因此<b>不使用</b> {@code JwtAuthFilter}，
 * 而是用共享令牌 {@code minecraft.api_token} 校验通道身份：
 * <ul>
 *   <li>请求头 {@code X-Api-Token: <token>}，或</li>
 *   <li>请求头 {@code Authorization: Bearer <token>}，或</li>
 *   <li>查询参数 {@code ?token=<token>}</li>
 * </ul>
 * 未配置 {@code minecraft.api_token} 时<b>直接拒绝</b>——早期版本完全无鉴权，
 * 任何能访问 8080 端口的人都可以冒充任意已绑定玩家执行指令。
 * 令牌只证明"请求来自受信任的桥接插件"，玩家身份仍由插件上报的 mcUuid 决定，
 * 与 OneBot 通道"信任 OneBot 客户端上报的 QQ 号"是同一套信任模型。
 */
@Path("/mc-command")
public class McCommandResource {

    private static final Gson gson = new Gson();
    private static final GameLogger log = GameLogger.getLogger("McCommandApi");

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleCommand(String body,
                                  @HeaderParam("X-Api-Token") String apiTokenHeader,
                                  @HeaderParam("Authorization") String authorizationHeader,
                                  @QueryParam("token") String queryToken) {
        String expected = AppConfig.get("minecraft.api_token", "");
        if (expected == null || expected.isBlank()) {
            log.warn("[MC-API] 拒绝请求：未配置 minecraft.api_token，MC 指令接口处于关闭状态");
            return error("MC 指令接口未启用：请在 config/application.yml 设置 minecraft.api_token，"
                    + "并同步配置到 MC 桥接插件");
        }
        String provided = firstNonBlank(queryToken, apiTokenHeader, bearer(authorizationHeader));
        if (provided == null || !constantTimeEquals(expected.trim(), provided.trim())) {
            log.warn("[MC-API] 拒绝请求：api_token 缺失或不匹配");
            return error("鉴权失败");
        }

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

    private static String bearer(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String v = authorizationHeader.trim();
        if (v.regionMatches(true, 0, "Bearer ", 0, 7)) {
            v = v.substring(7).trim();
        }
        return v.isEmpty() ? null : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 定长比较，避免通过响应时间差推断令牌。 */
    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
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
