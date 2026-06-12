package com.mtxgdn.common.command;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * 路由定义。存放于 Command 中，由 Command.addRoute() 统管。
 * <p>
 * 三种注册模式：
 * <ul>
 *   <li>{@code onebotOnly("info", handler)} — 仅 OneBot 子命令，无 HTTP 路由</li>
 *   <li>{@code get("path", handler)} / {@code httpOnlyGet("path", handler)} — 仅 HTTP 路由</li>
 *   <li>同时调用 {@code registerSub} + {@code addRoute} — 双端注册</li>
 * </ul>
 */
public class RouteDefinition {

    private final String method;      // GET / POST（onebotOnly 为空串）
    private final String path;        // HTTP 路径，e.g. "sect/list"
    private final String subCommand;  // OneBot 子命令名，e.g. "info"（onebotOnly 时有效）
    private final String permission;  // 权限码，null 表示无需权限
    private final boolean httpOnly;   // true = 仅 HTTP，不出现在 OneBot 帮助
    private final boolean onebotOnly; // true = 仅 OneBot，无 HTTP 路由
    private final RouteHandler httpHandler;       // HTTP handler
    private final Command.SubHandler botHandler;  // OneBot handler

    private RouteDefinition(String method, String path, String subCommand,
                            String permission,
                            boolean httpOnly, boolean onebotOnly,
                            RouteHandler httpHandler, Command.SubHandler botHandler) {
        this.method = method;
        this.path = path;
        this.subCommand = subCommand;
        this.permission = permission;
        this.httpOnly = httpOnly;
        this.onebotOnly = onebotOnly;
        this.httpHandler = httpHandler;
        this.botHandler = botHandler;
    }

    // ==================== 仅 HTTP ====================

    /** GET 路由（仅 HTTP，不出现在 OneBot 帮助） */
    public static RouteDefinition get(String path, RouteHandler handler) {
        return new RouteDefinition("GET", path, null, null, true, false, handler, null);
    }
    public static RouteDefinition get(String path, String permission, RouteHandler handler) {
        return new RouteDefinition("GET", path, null, permission, true, false, handler, null);
    }

    /** POST 路由（仅 HTTP） */
    public static RouteDefinition post(String path, RouteHandler handler) {
        return new RouteDefinition("POST", path, null, null, true, false, handler, null);
    }
    public static RouteDefinition post(String path, String permission, RouteHandler handler) {
        return new RouteDefinition("POST", path, null, permission, true, false, handler, null);
    }

    /** @deprecated 改用 {@link #get(String, RouteHandler)}，语义相同 */
    @Deprecated
    public static RouteDefinition httpOnlyGet(String path, RouteHandler handler) {
        return get(path, handler);
    }
    /** @deprecated 改用 {@link #get(String, String, RouteHandler)} */
    @Deprecated
    public static RouteDefinition httpOnlyGet(String path, String permission, RouteHandler handler) {
        return get(path, permission, handler);
    }
    /** @deprecated 改用 {@link #post(String, String, RouteHandler)} */
    @Deprecated
    public static RouteDefinition httpOnlyPost(String path, String permission, RouteHandler handler) {
        return post(path, permission, handler);
    }

    // ==================== 仅 OneBot ====================

    /** 仅 OneBot 子命令，不出现在 HTTP 路由中 */
    public static RouteDefinition onebotOnly(String subCommand, Command.SubHandler handler) {
        return new RouteDefinition("", "", subCommand, null, false, true, null, handler);
    }

    // ==================== Getters ====================

    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getSubCommand() { return subCommand; }
    public String getPermission() { return permission; }
    public boolean isHttpOnly() { return httpOnly; }
    public boolean isOnebotOnly() { return onebotOnly; }
    public RouteHandler getHttpHandler() { return httpHandler; }
    public Command.SubHandler getBotHandler() { return botHandler; }

    /**
     * REST 路由处理器。接收上下文，返回 JsonObject。
     */
    @FunctionalInterface
    public interface RouteHandler {
        JsonObject handle(RestContext ctx) throws Exception;
    }

    /**
     * REST 请求上下文，由 UnifiedRestResource 在调用 handler 前填充。
     */
    public static class RestContext {
        private final String body;
        private final Map<String, String> pathParams;
        private final Map<String, String> queryParams;
        private final long userId;
        private final int playerId;

        public RestContext(String body, Map<String, String> pathParams,
                           Map<String, String> queryParams, long userId, int playerId) {
            this.body = body;
            this.pathParams = pathParams;
            this.queryParams = queryParams;
            this.userId = userId;
            this.playerId = playerId;
        }

        public String body() { return body; }
        public long userId() { return userId; }
        public int playerId() { return playerId; }
        public String pathParam(String name) { return pathParams.get(name); }
        public String queryParam(String name) { return queryParams.get(name); }
        public long pathParamLong(String name) { return Long.parseLong(pathParams.get(name)); }

        public com.google.gson.JsonObject bodyJson() {
            return com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /**
     * 把已注册的 URL 模式与传入路径匹配，提取路径参数。
     * e.g. pattern="sect/info/{sectId}" vs actual="sect/info/5" → {sectId: "5"}
     * 返回 null 表示不匹配。
     */
    public static Map<String, String> matchPath(String pattern, String actualPath) {
        String[] patParts = pattern.split("/");
        String[] actParts = actualPath.split("/");

        if (patParts.length != actParts.length) return null;

        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
        for (int i = 0; i < patParts.length; i++) {
            if (patParts[i].startsWith("{") && patParts[i].endsWith("}")) {
                params.put(patParts[i].substring(1, patParts[i].length() - 1), actParts[i]);
            } else if (!patParts[i].equals(actParts[i])) {
                return null;
            }
        }
        return params;
    }
}
