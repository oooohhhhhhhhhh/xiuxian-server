package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.entity.User;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.RateLimiter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.itemService;
import static com.mtxgdn.rest.AdminSupport.playerService;

/**
 * 管理台 · 身份与会话接口（登录、当前身份、我的角色）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：登录/身份这三个端点
 * 是<b>未登录也要能调</b>的入口，权限模型与其余管理端点完全不同
 * （{@code /login} 无权限码，{@code /whoami}、{@code /me} 只回调用者自己的身份），
 * 单独成类后"哪些端点不需要权限"一眼可辨。</p>
 *
 * <p>拆分只按"路径前缀 + 依赖集合"切，不夹带任何行为改动 —— 路径、权限码、
 * 返回结构全部保持原样，前端 {@code /api/admin/login|whoami|me} 不受影响。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /login} 拼出来的地址与拆分前逐字一致
 * （Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminAuthResource {

    @Context
    private HttpHeaders httpHeaders;

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(String body) {
        if (!RateLimiter.allow("admin_login_global", 20, 60)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", I18n.t("admin.too_many_login_attempts_2"));
            return Response.status(429).entity(gson.toJson(err)).build();
        }
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        String loginType = req.has("loginType") ? req.get("loginType").getAsString() : "admin";

        if (username.isBlank() || password.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("admin.username_and_password_cannot"));
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        // 按用户名限流，防止对单一账号的暴力枚举
        if (!RateLimiter.allow("admin_login:" + username, 5, 60)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", I18n.t("admin.too_many_login_attempts"));
            return Response.status(429).entity(gson.toJson(err)).build();
        }

        if ("player".equals(loginType)) {
            return playerLogin(username, password);
        }

        return adminLogin(username, password);
    }

    private Response adminLogin(String username, String password) {
        if (AdminAuthFilter.validateCredentials(username, password)) {
            AdminAuthFilter.resetLoginFailures(username);
            Long userId = AdminAuthFilter.getUserIdByUsername(username);
            String role = AdminAuthFilter.getAdminRole(userId);
            String token = AdminAuthFilter.generateAdminToken(userId != null ? userId : 0L, username, role);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("token", token);
            result.addProperty("username", username);
            result.addProperty("userId", userId != null ? userId : 0);
            result.addProperty("highestRole", role);
            result.addProperty("loginType", "admin");

            JsonArray perms = new JsonArray();
            if (userId != null) {
                for (String code : PermissionService.getAllUserPermissionCodes(userId)) {
                    perms.add(code);
                }
            } else {
                for (com.mtxgdn.permission.PermissionCode pc : com.mtxgdn.permission.PermissionCode.values()) {
                    perms.add(pc.getCode());
                }
            }
            result.add("permissions", perms);
            return Response.ok(gson.toJson(result)).build();
        }

        UserService userService = new UserService();
        User user = userService.authenticate(username, password);

        if (user == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("admin.incorrect_username_or_password"));
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.hasPermission(user.getId(), PermissionCode.ADMIN_LOGIN.getCode())) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 403);
            err.addProperty("message", I18n.t("admin.no_admin_backend_permission"));
            return Response.status(403).entity(gson.toJson(err)).build();
        }

        String role = PermissionService.getHighestRole(user.getId());
        // role 可能为 null（仅持有 ADMIN_LOGIN 权限但未分配角色），generateAdminToken 内部会 toLowerCase，兜底为 admin
        String token = AdminAuthFilter.generateAdminToken(user.getId(), user.getUsername(), role != null ? role : "admin");
        JsonArray perms = new JsonArray();
        for (String code : PermissionService.getAllUserPermissionCodes(user.getId())) {
            perms.add(code);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("token", token);
        result.addProperty("username", user.getUsername());
        result.addProperty("userId", user.getId());
        result.addProperty("highestRole", role != null ? role : "");
        result.addProperty("loginType", "admin");
        result.add("permissions", perms);
        return Response.ok(gson.toJson(result)).build();
    }

    private Response playerLogin(String username, String password) {
        UserService userService = new UserService();
        User user = userService.authenticate(username, password);

        if (user == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("admin.incorrect_username_or_password"));
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());
        String highestRole = PermissionService.getHighestRole(user.getId());

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("token", token);
        result.addProperty("username", user.getUsername());
        result.addProperty("userId", user.getId());
        result.addProperty("highestRole", highestRole != null ? highestRole : "PLAYER");
        result.addProperty("loginType", "player");

        JsonArray perms = new JsonArray();
        for (String code : PermissionService.getAllUserPermissionCodes(user.getId())) {
            perms.add(code);
        }
        result.add("permissions", perms);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/whoami")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCurrentUser() {
        JsonObject result = new JsonObject();
        try {
            List<String> authHeaders = httpHeaders.getRequestHeader("Authorization");
            String authHeader = authHeaders != null && !authHeaders.isEmpty() ? authHeaders.get(0) : null;
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                result.addProperty("code", 401);
                result.addProperty("message", I18n.t("admin.not_logged"));
                return Response.ok(gson.toJson(result)).build();
            }
            String token = authHeader.substring("Bearer ".length()).trim();

            try {
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(AdminAuthFilter.ADMIN_KEY)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();

                String role = claims.get("role", String.class);
                if (role != null && ("admin".equals(role) || "super_admin".equals(role) || "moderator".equals(role))) {
                    String username = claims.get("username", String.class);
                    Long userId = claims.get("userId", Long.class);

                    result.addProperty("code", 200);
                    result.addProperty("username", username);
                    result.addProperty("userId", userId);
                    result.addProperty("role", role);
                    result.addProperty("loginType", "admin");
                    return Response.ok(gson.toJson(result)).build();
                }
            } catch (Exception e) {
                // 非 admin 密钥 token，走下方玩家 JWT 校验
            }

            // 玩家 token：与 admin 同密钥时上面能解析成功但无 role claim，同样落在这里
            if (JwtUtil.validateToken(token)) {
                Long userId = JwtUtil.extractUserId(token);
                String username = JwtUtil.extractUsername(token);
                String highestRole = PermissionService.getHighestRole(userId);

                result.addProperty("code", 200);
                result.addProperty("username", username);
                result.addProperty("userId", userId);
                result.addProperty("role", highestRole != null ? highestRole : "PLAYER");
                result.addProperty("loginType", "player");
                return Response.ok(gson.toJson(result)).build();
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.failed_to_fetch_user_info"));
            return Response.ok(gson.toJson(result)).build();
        }

        result.addProperty("code", 401);
        result.addProperty("message", I18n.t("admin.not_logged"));
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMyPlayerInfo() {
        JsonObject result = new JsonObject();
        try {
            List<String> authHeaders = httpHeaders.getRequestHeader("Authorization");
            String authHeader = authHeaders != null && !authHeaders.isEmpty() ? authHeaders.get(0) : null;
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                result.addProperty("code", 401);
                result.addProperty("message", I18n.t("admin.not_logged"));
                return Response.ok(gson.toJson(result)).build();
            }
            String token = authHeader.substring("Bearer ".length()).trim();

            Long userId = null;
            try {
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(AdminAuthFilter.ADMIN_KEY)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();
                userId = claims.get("userId", Long.class);
            } catch (Exception e) {
                // 与 admin 密钥不同的玩家 token：解析失败，继续走下方游戏 JWT 校验
            }
            // 玩家 token 的 userId 位于 subject（而非 userId claim）：即使与 admin 同密钥、
            // 能被 admin 密钥解析成功（userId 仍为 null），也必须再按游戏 JWT 提取。
            if (userId == null && JwtUtil.validateToken(token)) {
                userId = JwtUtil.extractUserId(token);
            }

            if (userId == null) {
                result.addProperty("code", 401);
                result.addProperty("message", I18n.t("admin.not_logged"));
                return Response.ok(gson.toJson(result)).build();
            }

            var player = playerService.getPlayerByUserId(userId);
            if (player == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.player_character_does_not_exist"));
                return Response.ok(gson.toJson(result)).build();
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", player.getId());
            obj.addProperty("userId", player.getUserId());
            obj.addProperty("name", player.getName());
            obj.addProperty("level", player.getLevel());
            obj.addProperty("experience", player.getExperience());
            obj.addProperty("realm", player.getRealmName());
            obj.addProperty("hp", player.getHp());
            obj.addProperty("maxHp", player.getMaxHp());
            obj.addProperty("mp", player.getMp());
            obj.addProperty("maxMp", player.getMaxMp());
            obj.addProperty("attack", player.getAttack());
            obj.addProperty("defense", player.getDefense());
            obj.addProperty("speed", player.getSpeed());
            obj.addProperty("spirit", player.getSpirit());
            obj.addProperty("gold", player.getGold());
            obj.addProperty("cultivating", player.isCultivating());
            obj.addProperty("cultivationProgress", player.getCultivationProgress());
            long ss = itemService.getSpiritStoneCount(player.getId());
            obj.addProperty("spiritStones", ss);
            if (player.getSpiritualRoot() != null) {
                obj.addProperty("spiritualRoot", player.getSpiritualRoot().getDisplayName());
                obj.addProperty("rootTier", player.getSpiritualRoot().getTier().getDisplayName());
            }

            JsonArray items = new JsonArray();
            for (var entry : itemService.getInventory(player.getId())) {
                JsonObject io = new JsonObject();
                io.addProperty("key", entry.getItem().getKey());
                io.addProperty("name", entry.getItem().getName());
                io.addProperty("qty", entry.getQuantity());
                items.add(io);
            }
            obj.add("items", items);

            result.addProperty("code", 200);
            result.add("player", obj);
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("admin.failed_to_fetch_player_info", e.getMessage()));
            return Response.ok(gson.toJson(result)).build();
        }
    }
}
