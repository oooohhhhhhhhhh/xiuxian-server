package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.service.OAuthService;
import com.mtxgdn.util.AppConfig;
import io.jsonwebtoken.Claims;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.mtxgdn.i18n.I18n;

/**
 * OAuth 2.0 REST 接口。
 *
 * <p>令牌端点 + 内省端点 + <b>受保护资源端点</b>：</p>
 * <ul>
 *   <li>POST /api/auth/oauth/token — 客户端凭据换 token</li>
 *   <li>POST /api/auth/oauth/introspect — token 内省</li>
 *   <li>GET  /api/auth/oauth/me — 受保护：查看当前 token 所属客户端与服务器概况</li>
 *   <li>GET  /api/auth/oauth/protected/status — 受保护（需 read scope）：服务器状态</li>
 *   <li>POST /api/auth/oauth/protected/echo — 受保护（需 write scope）：回显请求体</li>
 *   <li>GET  /api/auth/oauth/authorize — 授权码端点（未实现，属于"无用"的一部分）</li>
 * </ul>
 */
@Path("/auth/oauth")
public class OAuthResource {

    private static final Gson gson = new Gson();

    @Context
    private HttpHeaders httpHeaders;

    // ==================== 令牌端点 ====================

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(String body) {
        if (!OAuthService.isEnabled()) {
            return error(503, "oauth2_not_enabled", "OAuth 2.0 支持未启用（oauth2.enabled=false）");
        }

        JsonObject json;
        try {
            json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return error(400, "invalid_request", "请求体不是合法的 JSON");
        }

        String grantType = json.has("grant_type") ? json.get("grant_type").getAsString() : "";
        String clientId = json.has("client_id") ? json.get("client_id").getAsString() : "";
        String clientSecret = json.has("client_secret") ? json.get("client_secret").getAsString() : "";
        String scope = json.has("scope") ? json.get("scope").getAsString() : null;

        if (!"client_credentials".equals(grantType)) {
            return error(400, "unsupported_grant_type", "仅支持 client_credentials 授权模式");
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return error(400, "invalid_client", "缺少 client_id / client_secret");
        }
        if (!OAuthService.validateClient(clientId, clientSecret)) {
            return error(401, "invalid_client", "客户端凭据无效");
        }

        return Response.ok(gson.toJson(OAuthService.buildTokenResponse(clientId, scope))).build();
    }

    @POST
    @Path("/introspect")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response introspect(String body) {
        JsonObject json;
        try {
            json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return error(400, "invalid_request", "请求体不是合法的 JSON");
        }
        String token = json.has("token") ? json.get("token").getAsString() : "";
        return Response.ok(gson.toJson(OAuthService.introspect(token))).build();
    }

    // ==================== 受保护资源端点（OAuth token 真正可用之处）====================

    /** 查看当前 OAuth token 信息与服务器概况（需有效 Bearer token） */
    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response me() {
        Claims claims = requireOAuthToken("read");
        if (claims == null) {
            return unauthorized();
        }
        JsonObject data = new JsonObject();
        data.addProperty("client_id", claims.get("client_id", String.class));
        data.addProperty("scope", claims.get("scope", String.class));
        data.addProperty("token_type", "Bearer");
        data.addProperty("issuedAt", claims.getIssuedAt().getTime());
        data.addProperty("expiresAt", claims.getExpiration().getTime());
        data.addProperty("subject", claims.getSubject());
        data.add("server", serverInfo());
        return ok("OAuth token 验证通过", data);
    }

    /** 受保护资源示例：服务器状态（需 read scope） */
    @GET
    @Path("/protected/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response protectedStatus() {
        Claims claims = requireOAuthToken("read");
        if (claims == null) {
            return unauthorized();
        }
        JsonObject data = serverInfo();
        data.addProperty("client_id", claims.get("client_id", String.class));
        data.addProperty("message", I18n.t("oauth.accessing_protected_resource",
                claims.get("scope", String.class)));
        return ok(I18n.t("oauth.server_status_protected"), data);
    }

    /** 受保护资源示例：回显（需 write scope） */
    @POST
    @Path("/protected/echo")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response protectedEcho(String body) {
        Claims claims = requireOAuthToken("write");
        if (claims == null) {
            return unauthorized();
        }
        JsonObject data = new JsonObject();
        data.addProperty("client_id", claims.get("client_id", String.class));
        data.addProperty("scope", claims.get("scope", String.class));
        data.addProperty("echo", body == null || body.isBlank() ? "(空请求体)" : body);
        data.addProperty("receivedAt", System.currentTimeMillis());
        return ok("OAuth write 资源调用成功", data);
    }

    private Claims requireOAuthToken(String requiredScope) {
        String authorization = httpHeaders.getHeaderString("Authorization");
        String token = OAuthService.extractBearer(authorization);
        if (token == null) {
            return null;
        }
        Claims claims = OAuthService.parseToken(token);
        if (claims == null) {
            return null;
        }
        String scope = claims.get("scope", String.class);
        if (!OAuthService.hasScope(scope, requiredScope)) {
            return null;
        }
        return claims;
    }

    private JsonObject serverInfo() {
        JsonObject server = new JsonObject();
        server.addProperty("appName", AppConfig.get("app.name", "修仙世界"));
        server.addProperty("onlineCount", Main.gameWebSocketApp != null ? Main.gameWebSocketApp.getOnlineCount() : 0);
        server.addProperty("uptimeSeconds", (System.currentTimeMillis() - Main.serverStartTime) / 1000);
        server.addProperty("serverTime", System.currentTimeMillis());
        return server;
    }

    private Response unauthorized() {
        return error(401, "invalid_token", "缺少有效的 OAuth access_token（Authorization: Bearer <token>），或 scope 不足");
    }

    private Response ok(String message, JsonObject data) {
        JsonObject json = new JsonObject();
        json.addProperty("code", 0);
        json.addProperty("message", message);
        if (data != null) {
            json.add("data", data);
        }
        return Response.ok(json.toString()).build();
    }

    // ==================== 未实现的授权码端点 ====================

    /**
     * 授权码（authorization code）端点 —— 故意未实现（无用）。
     */
    @GET
    @Path("/authorize")
    @Produces(MediaType.APPLICATION_JSON)
    public Response authorize(@QueryParam("response_type") String responseType,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("scope") String scope) {
        JsonObject data = new JsonObject();
        data.addProperty("code", 501);
        data.addProperty("message", I18n.t("oauth.the_authorization_code_flow"));
        data.addProperty("note", "用法：POST /api/auth/oauth/token 拿 token → 携带 Authorization: Bearer <token> 访问 /api/auth/oauth/me 或 /api/auth/oauth/protected/*");
        data.addProperty("received", (responseType == null ? "" : responseType) + "|" +
                (clientId == null ? "" : clientId) + "|" +
                (redirectUri == null ? "" : redirectUri) + "|" +
                (scope == null ? "" : scope));
        return Response.status(501).entity(data.toString()).build();
    }

    private Response error(int status, String error, String description) {
        JsonObject err = new JsonObject();
        err.addProperty("error", error);
        err.addProperty("error_description", description);
        return Response.status(status).entity(err.toString()).build();
    }
}
