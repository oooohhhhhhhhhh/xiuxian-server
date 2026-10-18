package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.permission.PermissionService;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.*;
import com.mtxgdn.i18n.I18n;

/**
 * 指令统一 REST 分发器。
 * <p>
 * 放在 {@code @Path("/game")} 下，和 GameResource 共享路径空间。
 * Jersey 会优先匹配 GameResource 的显式路径，此处只处理 Command 注册的路由。
 * <p>
 * 新模块只需要在 Command 子类中覆写 {@code getRestEndpoints()} 即可同时获得 HTTP API。
 */
@Path("/game")
public class UnifiedRestResource {

    private static final Gson gson = new Gson();
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();

    // 按 HTTP 方法分组：method -> path -> RouteDefinition
    private static final Map<String, List<RouteDefEntry>> routesByMethod = new HashMap<>();
    private static volatile boolean routesLoaded = false;

    @Context
    private ContainerRequestContext requestContext;

    @Context
    private UriInfo uriInfo;

    public UnifiedRestResource() {
        ensureRoutesLoaded();
    }

    private static synchronized void ensureRoutesLoaded() {
        if (routesLoaded) return;
        routesLoaded = true;

        for (Command cmd : CommandRegistry.getAllUnique()) {
            List<RouteDefinition> defs = cmd.getRestEndpoints();
            if (defs == null || defs.isEmpty()) continue;

            for (RouteDefinition def : defs) {
                routesByMethod.computeIfAbsent(def.getMethod(), k -> new ArrayList<>())
                        .add(new RouteDefEntry(def));
            }
        }

        // 按路径中的字面量段数降序排列，确保 /sect/info/{id} 优先于 /sect/{action}
        for (List<RouteDefEntry> entries : routesByMethod.values()) {
            entries.sort(Comparator.comparingInt(RouteDefEntry::literalSegmentCount).reversed());
        }
    }

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleGet(@PathParam("path") String path) {
        return dispatch("GET", path, null);
    }

    @POST
    @Path("{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handlePost(@PathParam("path") String path, String body) {
        return dispatch("POST", path, body);
    }

    private Response dispatch(String httpMethod, String path, String body) {
        List<RouteDefEntry> candidates = routesByMethod.getOrDefault(httpMethod, List.of());
        if (path == null) path = "";

        boolean isAdminPath = path.startsWith("admin/");

        for (RouteDefEntry entry : candidates) {
            Map<String, String> params = RouteDefinition.matchPath(entry.def.getPath(), path);
            if (params == null) continue;

            // 权限检查
            if (entry.def.getPermission() != null) {
                Long permUserId = getCurrentUserIdQuietly();
                // V8 修复：原实现在 userId 为 null 时<b>直接跳过权限检查</b>
                // （注释称"admin 路径已由 AdminAuthFilter 鉴权"）。但 admin JWT
                // 只证明"是管理员"，并不等于它拥有该路由要求的细粒度权限，
                // 跳过检查等于给所有 admin 隐式授予全部权限。
                // 现在 admin 身份统一用 adminUserId 做权限校验。
                if (permUserId == null && isAdminPath && isAdminAuthenticated()) {
                    permUserId = getAdminUserIdQuietly();
                }
                if (permUserId == null) {
                    return Response.status(401)
                            .entity("{\"code\":401,\"message\":\"未登录\"}")
                            .build();
                }
                if (!PermissionService.hasPermission(permUserId, entry.def.getPermission())) {
                    return Response.status(403)
                            .entity("{\"code\":403,\"message\":\"无权限\"}")
                            .build();
                }
            }

            Long userId = getCurrentUserIdQuietly();
            boolean adminContext = false;
            if (userId == null) {
                // admin 路径：检查是否已通过 AdminAuthFilter 的 JWT 鉴权
                if (isAdminPath && isAdminAuthenticated()) {
                    adminContext = true;
                } else {
                    return Response.status(401)
                            .entity("{\"code\":401,\"message\":\"未登录\"}")
                            .build();
                }
            }

            // V8 修复：原实现给 admin 置哨兵 userId=0L，随后仍调用
            // getPlayerIdByUserId(0L) —— 该玩家不存在，于是抛
            // WebApplicationException("请先创建游戏角色", 400)。
            // 结果是"管理员通过鉴权后永远调不通任何需要玩家上下文的路由"，
            // 且报错信息完全是误导性的。现在 admin 上下文明确置 playerId=0，
            // 由各路由自行判断是否需要玩家上下文。
            int playerId;
            long contextUserId = adminContext ? getAdminUserIdQuietly() : userId;
            if (adminContext) {
                playerId = 0; // admin JWT 无玩家上下文
            } else {
                playerId = getPlayerIdByUserId(userId);
            }

            // 获取 query 参数
            Map<String, String> queryParams = new LinkedHashMap<>();
            uriInfo.getQueryParameters().forEach((k, v) -> {
                if (!v.isEmpty()) queryParams.put(k, v.get(0));
            });

            RouteDefinition.RestContext ctx = new RouteDefinition.RestContext(
                    body != null ? body : "{}", params, queryParams, contextUserId, playerId);

            try {
                JsonObject result = entry.def.getHttpHandler().handle(ctx);
                return Response.ok(gson.toJson(result)).build();
            } catch (Exception e) {
                System.err.println("[UnifiedRest] 处理路由异常 " + httpMethod + " " + path + ": " + e);
                JsonObject err = new JsonObject();
                err.addProperty("code", 500);
                err.addProperty("message", I18n.t("unified_rest.h2177df77"));
                return Response.status(500).entity(gson.toJson(err)).build();
            }
        }

        // 没有路由匹配——返回 404 让 Jersey 尝试其他资源（如 GameResource）
        return null;
    }

    private boolean isAdminAuthenticated() {
        try {
            Object val = requestContext.getProperty("adminAuthenticated");
            return val instanceof Boolean && (Boolean) val;
        } catch (Exception ignored) {}
        return false;
    }

    private Long getCurrentUserIdQuietly() {
        try {
            Object uid = requestContext.getProperty("userId");
            if (uid instanceof Long) return (Long) uid;
        } catch (Exception ignored) {}
        return null;
    }

    /** V8：读取 AdminAuthFilter 写入的管理员 userId（用于权限校验与审计）。 */
    private Long getAdminUserIdQuietly() {
        try {
            Object uid = requestContext.getProperty("adminUserId");
            if (uid instanceof Long) return (Long) uid;
        } catch (Exception ignored) {}
        return null;
    }

    private int getPlayerIdByUserId(long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            throw new WebApplicationException("请先创建游戏角色", 400);
        }
        return (int) player.getId();
    }

    /** 内部条目，用于排序和匹配 */
    private static class RouteDefEntry {
        final RouteDefinition def;
        final int literalCount;

        RouteDefEntry(RouteDefinition def) {
            this.def = def;
            int count = 0;
            for (String part : def.getPath().split("/")) {
                if (!part.startsWith("{")) count++;
            }
            this.literalCount = count;
        }

        int literalSegmentCount() { return literalCount; }
    }
}
