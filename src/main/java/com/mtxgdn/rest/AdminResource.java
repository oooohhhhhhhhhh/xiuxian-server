package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.adapter.AdapterConfigManager;
import com.mtxgdn.adapter.AdapterManager;
import com.mtxgdn.adapter.AdaptersConfig;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.map.WildernessTerrain;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.entity.User;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.plugin.PluginWebManager;
import com.mtxgdn.service.UserService;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.StatsCollector;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mtxgdn.i18n.I18n;

@Path("/admin")
public class AdminResource {

    private static final Gson gson = new Gson();
    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(AdminResource.class);
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();
    private static final ItemService itemService = ServiceRegistry.getItemService();

    /**
     * 通用表管理改动地图表后热重载地图缓存。
     *
     * <p>此前后台改完 map_locations 必须重启进程才生效（MapService 只缓存内存，
     * 没有失效入口），管理员改完看不到变化会以为改错了。</p>
     */
    private static void reloadMapCacheIfNeeded(String tableName) {
        if (tableName == null) return;
        String table = tableName.toLowerCase();
        if (!table.equals("map_locations")
                && !table.equals("map_connections")
                && !table.equals("map_location_event_bias")
                && !table.equals("map_wilderness_resources")) {
            return;
        }
        try {
            ServiceRegistry.getMapService().reload();
        } catch (Exception e) {
            LOG.warn("地图表 " + table + " 已改动，但地图缓存重载失败: " + e.getMessage());
        }
    }
    
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
            err.addProperty("message", I18n.t("admin.h9c54f628"));
            return Response.status(429).entity(gson.toJson(err)).build();
        }
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        String loginType = req.has("loginType") ? req.get("loginType").getAsString() : "admin";

        if (username.isBlank() || password.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", I18n.t("admin.h34f00dc0"));
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        // 按用户名限流，防止对单一账号的暴力枚举
        if (!RateLimiter.allow("admin_login:" + username, 5, 60)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", I18n.t("admin.h90e20fd8"));
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
            err.addProperty("message", I18n.t("admin.hda558f5c"));
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.hasPermission(user.getId(), PermissionCode.ADMIN_LOGIN.getCode())) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 403);
            err.addProperty("message", I18n.t("admin.h00567a14"));
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
            err.addProperty("message", I18n.t("admin.hda558f5c"));
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
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getStatus() {
        long uptime = System.currentTimeMillis() - Main.serverStartTime;

        int onlineCount = 0;
        if (Main.gameWebSocketApp != null) {
            onlineCount = Main.gameWebSocketApp.getOnlineCount();
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);

        JsonObject data = new JsonObject();
        data.addProperty("uptime", uptime);
        data.addProperty("uptimeFormatted", formatUptime(uptime));
        data.addProperty("onlineCount", onlineCount);
        data.addProperty("usedMemoryMB", usedMB);
        data.addProperty("maxMemoryMB", maxMB);

        return Response.ok(gson.toJson(data)).build();
    }

    // ========== 插件管理 API ==========

    @GET
    @Path("/plugins")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPluginPages() {
        com.google.gson.JsonArray plugins = PluginWebManager.getInstance().getPluginPagesJson();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("plugins", plugins);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/plugins/list")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response getPluginList() {
        JsonArray arr = new JsonArray();
        
        // 添加内置插件
        List<Map<String, Object>> builtInStatuses = com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().getStatuses();
        for (Map<String, Object> s : builtInStatuses) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "builtin");
            for (Map.Entry<String, Object> entry : s.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    obj.add(entry.getKey(), null);
                } else if (val instanceof Boolean) {
                    obj.addProperty(entry.getKey(), (Boolean) val);
                } else if (val instanceof Number) {
                    obj.addProperty(entry.getKey(), (Number) val);
                } else {
                    obj.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
            arr.add(obj);
        }
        
        // 添加外部插件
        List<Map<String, Object>> externalStatuses = com.mtxgdn.plugin.PluginManager.getInstance().getPluginStatuses();
        for (Map<String, Object> s : externalStatuses) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "external");
            for (Map.Entry<String, Object> entry : s.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    obj.add(entry.getKey(), null);
                } else if (val instanceof Boolean) {
                    obj.addProperty(entry.getKey(), (Boolean) val);
                } else if (val instanceof Number) {
                    obj.addProperty(entry.getKey(), (Number) val);
                } else {
                    obj.addProperty(entry.getKey(), String.valueOf(val));
                }
            }
            arr.add(obj);
        }
        
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("plugins", arr);
        result.addProperty("watcherRunning", com.mtxgdn.plugin.PluginManager.getInstance().isFileWatcherRunning());
        // 内置插件开关落盘位置（WebUI 提示用户去哪里改配置）
        result.addProperty("pluginConfigFile", com.mtxgdn.plugin.PluginSwitches.getConfigPath().toString());
        result.addProperty("pluginConfigKeyPrefix", com.mtxgdn.plugin.PluginSwitches.KEY_PREFIX);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/reload/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response reloadPlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().reloadPlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 404);
        result.addProperty("message", ok ? "插件已重载: " + name : "插件不存在: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/unload/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response unloadPlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().unloadPlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 404);
        result.addProperty("message", ok ? "插件已卸载: " + name : "插件不存在: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/enable/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response enablePlugin(@PathParam("name") String name, @QueryParam("type") String type) {
        boolean ok = enableDisablePlugin(name, type, true);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? "插件已启用: " + name : "启用失败: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/disable/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response disablePlugin(@PathParam("name") String name, @QueryParam("type") String type) {
        boolean ok = enableDisablePlugin(name, type, false);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? "插件已停用: " + name : "停用失败: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    /**
     * 启用/停用插件并按类型路由：
     * 内置插件（编译在主 jar 中，如 farm/sect）由 BuiltInPluginRegistry 管理，
     * 外部插件（plugins/*.jar）由 PluginManager 管理。
     * type 未指定时按名字自动路由（内置插件不在 PluginManager 中，直接调用会返回失败）。
     */
    private boolean enableDisablePlugin(String name, String type, boolean enable) {
        boolean isBuiltIn = "builtin".equalsIgnoreCase(type)
                || (type == null && com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().getRegisteredNames().contains(name));
        if (isBuiltIn) {
            return enable
                    ? com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().enablePlugin(name)
                    : com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().disablePlugin(name);
        }
        return enable
                ? com.mtxgdn.plugin.PluginManager.getInstance().enablePlugin(name)
                : com.mtxgdn.plugin.PluginManager.getInstance().disablePlugin(name);
    }

    @POST
    @Path("/plugins/load/{jarName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response loadPlugin(@PathParam("jarName") String jarName) {
        // S4 修复：路径校验 — 只允许 plugins/ 目录下的 .jar 文件，禁止路径穿越与绝对路径
        if (jarName == null || jarName.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h460e03c8"));
            return Response.ok(gson.toJson(err)).build();
        }
        if (jarName.contains("..") || jarName.contains("/") || jarName.contains("\\")
                || jarName.contains("\0") || !jarName.toLowerCase().endsWith(".jar")) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hf651e4d3"));
            return Response.ok(gson.toJson(err)).build();
        }
        File pluginsDir = new File("plugins").getAbsoluteFile();
        File jarFile = new File(pluginsDir, jarName).getAbsoluteFile();
        // 再次校验：解析后的真实路径必须在 pluginsDir 下
        try {
            if (!jarFile.getCanonicalPath().startsWith(pluginsDir.getCanonicalPath() + File.separator)
                    && !jarFile.getCanonicalPath().equals(pluginsDir.getCanonicalPath())) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.hf05cf8ec"));
                return Response.ok(gson.toJson(err)).build();
            }
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hbe2acdf0"));
            return Response.ok(gson.toJson(err)).build();
        }
        if (!jarFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h6ae3c6ea", jarName));
            return Response.ok(gson.toJson(err)).build();
        }
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().loadPlugin(jarFile);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 500);
        result.addProperty("message", ok ? "插件加载成功: " + jarName : "插件加载失败: " + jarName);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/watcher/start")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response startFileWatcher() {
        com.mtxgdn.plugin.PluginManager.getInstance().startFileWatcher();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h1237ba08"));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/watcher/stop")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response stopFileWatcher() {
        com.mtxgdn.plugin.PluginManager.getInstance().stopFileWatcher();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.hc44dbf28"));
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLogs(@QueryParam("since") long since) {
        List<GameLogger.LogEntry> entries = GameLogger.getRecentLogs(since);

        JsonArray arr = new JsonArray();
        for (GameLogger.LogEntry entry : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("seq", entry.seq);
            obj.addProperty("timestamp", entry.timestamp);
            obj.addProperty("level", entry.level);
            obj.addProperty("logger", entry.loggerName);
            obj.addProperty("message", entry.message);
            obj.addProperty("hasThrowable", entry.hasThrowable);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("latestSeq", GameLogger.getLatestLogSeq());
        result.add("entries", arr);

        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/shutdown")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response shutdown() {
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            com.mtxgdn.adapter.AdapterManager.stopAll();
            if (Main.gameWebSocketApp != null) {
                try { Main.gameWebSocketApp.shutdownGracefully(); } catch (Exception ignored) {}
            }
            if (Main.mainServer != null) Main.mainServer.shutdownNow();
            System.exit(0);
        });
        shutdownThread.setDaemon(true);
        shutdownThread.start();

        JsonObject result = new JsonObject();
        result.addProperty("message", I18n.t("admin.h8cce6bb4"));
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/roles")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getRoles() {
        JsonArray roles = new JsonArray();
        for (Map.Entry<String, Integer> entry : PermissionService.getRoleHierarchy().entrySet()) {
            JsonObject role = new JsonObject();
            role.addProperty("name", entry.getKey());
            role.addProperty("level", entry.getValue());
            role.addProperty("displayName", PermissionService.getGroupDisplayName(entry.getKey()));

            JsonArray perms = new JsonArray();
            for (String permCode : PermissionService.getGroupPermissionCodes(entry.getKey())) {
                JsonObject perm = new JsonObject();
                perm.addProperty("code", permCode);
                PermissionCode pc = PermissionCode.fromCode(permCode);
                if (pc != null) {
                    perm.addProperty("name", pc.getName());
                    perm.addProperty("category", pc.getCategory());
                } else if (PermissionService.isPluginPermission(permCode)) {
                    PermissionService.PluginPermissionInfo info = PermissionService.getPluginPermissions().get(permCode);
                    perm.addProperty("name", info.name);
                    perm.addProperty("category", info.category);
                } else {
                    perm.addProperty("name", permCode);
                    perm.addProperty("category", "");
                }
                perms.add(perm);
            }
            role.add("permissions", perms);
            roles.add(role);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("roles", roles);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getPermissions() {
        JsonArray perms = new JsonArray();
        for (PermissionCode pc : PermissionCode.values()) {
            JsonObject perm = new JsonObject();
            perm.addProperty("code", pc.getCode());
            perm.addProperty("name", pc.getName());
            perm.addProperty("category", pc.getCategory());
            perms.add(perm);
        }
        // 也列出插件权限
        for (PermissionService.PluginPermissionInfo info : PermissionService.getPluginPermissions().values()) {
            JsonObject perm = new JsonObject();
            perm.addProperty("code", info.code);
            perm.addProperty("name", info.name);
            perm.addProperty("category", info.category);
            perms.add(perm);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("permissions", perms);
        return Response.ok(gson.toJson(result)).build();
    }

    // ========== 权限组管理 API ==========

    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response getGroups() {
        List<Map<String, Object>> groups = PermissionService.getAllGroups();
        JsonArray arr = new JsonArray();
        for (Map<String, Object> g : groups) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", (String) g.get("name"));
            obj.addProperty("displayName", (String) g.get("displayName"));
            obj.addProperty("level", (Integer) g.get("level"));
            obj.addProperty("permissionCount", (Integer) g.get("permissionCount"));
            obj.addProperty("system", (Boolean) g.get("system"));

            JsonArray permsArr = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> permList = (List<String>) g.get("permissions");
            if (permList != null) {
                for (String code : permList) {
                    JsonObject po = new JsonObject();
                    po.addProperty("code", code);
                    PermissionCode pc = PermissionCode.fromCode(code);
                    if (pc != null) {
                        po.addProperty("name", pc.getName());
                        po.addProperty("category", pc.getCategory());
                    } else if (PermissionService.isPluginPermission(code)) {
                        PermissionService.PluginPermissionInfo info = PermissionService.getPluginPermissions().get(code);
                        po.addProperty("name", info.name);
                        po.addProperty("category", info.category);
                    } else {
                        po.addProperty("name", code);
                        po.addProperty("category", "");
                    }
                    permsArr.add(po);
                }
            }
            obj.add("permissions", permsArr);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("groups", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/groups")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response createGroup(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String name = req.has("name") ? req.get("name").getAsString() : null;
        String displayName = req.has("displayName") ? req.get("displayName").getAsString() : null;
        int level = req.has("level") ? req.get("level").getAsInt() : 10;

        if (name == null || name.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hbd4d9090"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.createGroup(name, displayName, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0d43f684"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response updateGroup(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String displayName = req.has("displayName") ? req.get("displayName").getAsString() : null;
        int level = req.has("level") ? req.get("level").getAsInt() : -1;

        try {
            // 如果 level 没传，保持现有等级
            if (level < 0) {
                Integer existingLevel = PermissionService.getRoleHierarchy().get(groupName);
                if (existingLevel != null) level = existingLevel;
            }
            PermissionService.updateGroup(groupName, displayName, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h2df30215"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/groups/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response deleteGroup(@PathParam("name") String groupName) {
        try {
            PermissionService.deleteGroup(groupName);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h04a3a43c"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response addGroupPermission(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String permissionCode = req.has("permission") ? req.get("permission").getAsString() : null;

        if (permissionCode == null || permissionCode.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h49c7b7c8"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.addGroupPermission(groupName, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0ba3ecc8"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/groups/{name}/permissions/{permissionCode}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response removeGroupPermission(
            @PathParam("name") String groupName,
            @PathParam("permissionCode") String permissionCode) {
        try {
            PermissionService.removeGroupPermission(groupName, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h6a8f4654"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/groups/{name}/permissions/set")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.roles.manage")
    public Response setGroupPermissions(@PathParam("name") String groupName, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        JsonArray permArr = req.has("permissions") ? req.getAsJsonArray("permissions") : new JsonArray();

        Set<String> permissionCodes = new java.util.HashSet<>();
        for (int i = 0; i < permArr.size(); i++) {
            permissionCodes.add(permArr.get(i).getAsString());
        }

        try {
            PermissionService.setGroupPermissions(groupName, permissionCodes);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.he27be784"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response getUsersWithRoles() {
        List<Map<String, Object>> users = PermissionService.getAllUsersWithRoles();

        JsonArray arr = new JsonArray();
        for (Map<String, Object> user : users) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ((Number) user.get("id")).longValue());
            obj.addProperty("username", (String) user.get("username"));
            JsonArray roles = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> roleList = (List<String>) user.get("roles");
            if (roleList != null) {
                for (String role : roleList) {
                    roles.add(role);
                }
            }
            obj.add("roles", roles);
            // 单独分配的权限
            JsonArray directPerms = new JsonArray();
            @SuppressWarnings("unchecked")
            List<String> permList = (List<String>) user.get("directPermissions");
            if (permList != null) {
                for (String perm : permList) {
                    directPerms.add(perm);
                }
            }
            obj.add("directPermissions", directPerms);
            // 全部有效权限
            JsonArray allPerms = new JsonArray();
            for (String code : PermissionService.getAllUserPermissionCodes(((Number) user.get("id")).longValue())) {
                allPerms.add(code);
            }
            obj.add("allPermissions", allPerms);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("users", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/{userId}/role")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response assignRole(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String roleName = req.has("role") ? req.get("role").getAsString() : null;

        if (roleName == null || !PermissionService.getRoleNames().contains(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h0eba6300"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        PermissionService.assignRole(userId, roleName);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h514bf7e1"));
        return Response.ok(gson.toJson(result)).build();
    }

    @DELETE
    @Path("/user/{userId}/role/{roleName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response removeRole(@PathParam("userId") long userId, @PathParam("roleName") String roleName) {
        if (!PermissionService.getRoleNames().contains(roleName)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h0eba6300"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        int currentLevel = PermissionService.getHighestRoleLevel(userId);
        Integer roleLevel = PermissionService.getRoleHierarchy().get(roleName);
        if (roleLevel != null && currentLevel <= roleLevel) {
            PermissionService.removeRole(userId, roleName);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h89776548"));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/create-admin")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response createAdminUser(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";
        String role = req.has("role") ? req.get("role").getAsString() : "ADMIN";

        if (username.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h564bba40"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (password.length() < 6) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h5df85b3d"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (!PermissionService.getRoleNames().contains(role)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h0eba6300"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        String sql = "SELECT id FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JsonObject err = new JsonObject();
                    err.addProperty("code", 409);
                    err.addProperty("message", I18n.t("admin.hdd8fbc40"));
                    return Response.status(409).entity(gson.toJson(err)).build();
                }
            }

            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            String insertSql = "INSERT INTO users (username, password) VALUES (?, ?)";
            try (PreparedStatement ips = conn.prepareStatement(insertSql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ips.setString(1, username);
                ips.setString(2, hashedPassword);
                ips.executeUpdate();
                try (ResultSet keys = ips.getGeneratedKeys()) {
                    if (keys.next()) {
                        long userId = keys.getLong(1);
                        PermissionService.assignRole(userId, role);
                        PermissionService.assignRole(userId, "PLAYER");

                        JsonObject result = new JsonObject();
                        result.addProperty("code", 200);
                        result.addProperty("message", I18n.t("admin.h58665cac"));
                        result.addProperty("userId", userId);
                        result.addProperty("username", username);
                        result.addProperty("role", role);
                        return Response.ok(gson.toJson(result)).build();
                    }
                }
            }
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hacdab49b", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }

        JsonObject err = new JsonObject();
        err.addProperty("code", 500);
        err.addProperty("message", I18n.t("admin.h9b99bb43"));
        return Response.status(500).entity(gson.toJson(err)).build();
    }

    @POST
    @Path("/user/{userId}/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response setUserPassword(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String newPassword = req.has("password") ? req.get("password").getAsString() : "";

        if (newPassword.length() < 6) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h5df85b3d"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        String sql = "UPDATE users SET password = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hashedPassword);
            ps.setLong(2, userId);
            int affected = ps.executeUpdate();
            if (affected == 0) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 404);
                err.addProperty("message", I18n.t("admin.hab63e3d4"));
                return Response.status(404).entity(gson.toJson(err)).build();
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.hd0c6ad88"));
            return Response.ok(gson.toJson(result)).build();
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h8d21b010", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/user/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response deleteUser(@PathParam("userId") long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE user_id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_permissions WHERE user_id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
                String sql = "DELETE FROM users WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, userId);
                    int affected = ps.executeUpdate();
                    if (affected == 0) {
                        conn.rollback();
                        JsonObject err = new JsonObject();
                        err.addProperty("code", 404);
                        err.addProperty("message", I18n.t("admin.hab63e3d4"));
                        return Response.status(404).entity(gson.toJson(err)).build();
                    }
                }
                conn.commit();

                JsonObject result = new JsonObject();
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.h5f4782cc"));
                return Response.ok(gson.toJson(result)).build();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h4360d4cc", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    // ========== 用户单独权限管理 ==========

    @GET
    @Path("/user/{userId}/permissions")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response getUserDirectPermissions(@PathParam("userId") long userId) {
        JsonArray perms = new JsonArray();
        for (PermissionCode pc : PermissionService.getUserDirectPermissions(userId)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", pc.getCode());
            obj.addProperty("name", pc.getName());
            obj.addProperty("category", pc.getCategory());
            perms.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("directPermissions", perms);

        JsonArray effectivePerms = new JsonArray();
        for (String code : PermissionService.getAllUserPermissionCodes(userId)) {
            effectivePerms.add(code);
        }
        result.add("allPermissions", effectivePerms);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/user/{userId}/permissions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response assignUserPermission(@PathParam("userId") long userId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String permissionCode = req.has("permission") ? req.get("permission").getAsString() : null;

        if (permissionCode == null || permissionCode.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h49c7b7c8"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.isValidPermissionCode(permissionCode)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hfb044040", permissionCode));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.assignPermission(userId, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h9d7e3c90"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h4eb875d4", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/user/{userId}/permissions/{permissionCode}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response removeUserPermission(@PathParam("userId") long userId, @PathParam("permissionCode") String permissionCode) {
        try {
            PermissionService.removePermission(userId, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.he43121b0"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h9d0342fc", e.getMessage()));
            return Response.status(500).entity(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/database/clear_players")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.clear_players")
    public Response clearPlayerData() {
        System.out.println("[Admin] >>> POST /admin/database/clear_players");
        Map<String, Integer> counts = DatabaseManager.clearPlayerData();

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.hd6ef4b84"));
        result.addProperty("totalDeleted", total);

        JsonObject detail = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            detail.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("detail", detail);

        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/database/reset_all")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response resetAllData() {
        System.out.println("[Admin] >>> POST /admin/database/reset_all");
        Map<String, Integer> counts = DatabaseManager.resetAllData();

        new SkillService().insertDefaultSkills();
        new TechniqueService().insertDefaultTechniques();
        new CraftingService().insertDefaultRecipes();

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.he0c1aa7c"));
        result.addProperty("totalDeleted", total);

        JsonObject detail = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            detail.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("detail", detail);

        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/players")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPlayers(
            @QueryParam("name") @DefaultValue("") String name,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        List<PlayerInfo> players;
        int total;
        if (!name.isBlank()) {
            players = playerService.searchPlayersByName(name.trim(), limit, offset);
            total = players.size();
        } else {
            players = playerService.getAllPlayers(limit, offset);
            total = playerService.getPlayerCount();
        }

        JsonArray arr = new JsonArray();
        for (PlayerInfo p : players) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.getId());
            obj.addProperty("userId", p.getUserId());
            obj.addProperty("name", p.getName());
            obj.addProperty("level", p.getLevel());
            obj.addProperty("experience", p.getExperience());
            if (p.getRealmName() != null) obj.addProperty("realm", p.getRealmName());
            else obj.addProperty("realm", p.getRealm());
            obj.addProperty("hp", p.getHp());
            obj.addProperty("maxHp", p.getMaxHp());
            obj.addProperty("mp", p.getMp());
            obj.addProperty("maxMp", p.getMaxMp());
            obj.addProperty("attack", p.getAttack());
            obj.addProperty("defense", p.getDefense());
            obj.addProperty("speed", p.getSpeed());
            obj.addProperty("spirit", p.getSpirit());
            obj.addProperty("gold", p.getGold());
            obj.addProperty("cultivating", p.isCultivating());
            long ss = itemService.getSpiritStoneCount(p.getId());
            obj.addProperty("spiritStones", ss);
            if (p.getSpiritualRoot() != null) {
                obj.addProperty("spiritualRoot", p.getSpiritualRoot().getDisplayName());
                obj.addProperty("rootTier", p.getSpiritualRoot().getTier().getDisplayName());
            }
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("players", arr);
        result.addProperty("total", total);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/players/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getPlayerDetail(@PathParam("id") long playerId) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("id", p.getId());
        obj.addProperty("userId", p.getUserId());
        obj.addProperty("name", p.getName());
        obj.addProperty("level", p.getLevel());
        obj.addProperty("experience", p.getExperience());
        obj.addProperty("realm", p.getRealm());
        obj.addProperty("subRealm", p.getSubRealm());
        obj.addProperty("hp", p.getHp());
        obj.addProperty("maxHp", p.getMaxHp());
        obj.addProperty("mp", p.getMp());
        obj.addProperty("maxMp", p.getMaxMp());
        obj.addProperty("attack", p.getAttack());
        obj.addProperty("defense", p.getDefense());
        obj.addProperty("speed", p.getSpeed());
        obj.addProperty("spirit", p.getSpirit());
        obj.addProperty("gold", p.getGold());
        obj.addProperty("cultivating", p.isCultivating());
        long ss = itemService.getSpiritStoneCount(p.getId());
        obj.addProperty("spiritStones", ss);
        obj.addProperty("cultivationProgress", p.getCultivationProgress());
        if (p.getSpiritualRoot() != null) {
            obj.addProperty("spiritualRoot", p.getSpiritualRoot().getDisplayName());
            obj.addProperty("rootTier", p.getSpiritualRoot().getTier().getDisplayName());
        }

        JsonArray items = new JsonArray();
        for (var entry : itemService.getInventory(p.getId())) {
            JsonObject io = new JsonObject();
            io.addProperty("key", entry.getItem().getKey());
            io.addProperty("name", entry.getItem().getName());
            io.addProperty("qty", entry.getQuantity());
            items.add(io);
        }
        obj.add("items", items);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("player", obj);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/players/{id}/give")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response giveToPlayer(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        StringBuilder sb = new StringBuilder();

        if (req.has("gold") && req.get("gold").getAsLong() != 0) {
            long amt = req.get("gold").getAsLong();
            playerService.addGold(playerId, amt);
            sb.append("金币 ").append(amt > 0 ? "+" : "").append(amt).append("; ");
        }
        if (req.has("exp") && req.get("exp").getAsLong() != 0) {
            long amt = req.get("exp").getAsLong();
            playerService.addExperience(playerId, amt);
            sb.append("灵力 ").append(amt > 0 ? "+" : "").append(amt).append("; ");
        }
        if (req.has("spiritStones") && req.get("spiritStones").getAsLong() != 0) {
            long amt = req.get("spiritStones").getAsLong();
            if (amt > 0) itemService.addSpiritStones(playerId, amt);
            else itemService.removeSpiritStones(playerId, -amt);
            sb.append("灵石 ").append(amt > 0 ? "+" : "").append(amt).append("; ");
        }
        if (req.has("itemKey") && req.has("itemQty")) {
            String key = req.get("itemKey").getAsString();
            int qty = req.get("itemQty").getAsInt();
            if (qty > 0) {
                itemService.addItem(playerId, key, qty);
                Item item = ItemRegistry.get(key);
                sb.append(item != null ? item.getName() : key).append(" x").append(qty).append("; ");
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", sb.length() > 0 ? "已发放: " + sb : "没有可发放的内容");
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/players/{id}/edit")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response editPlayer(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        if (req.has("hp")) p.setHp(req.get("hp").getAsInt());
        if (req.has("maxHp")) p.setMaxHp(req.get("maxHp").getAsInt());
        if (req.has("mp")) p.setMp(req.get("mp").getAsInt());
        if (req.has("maxMp")) p.setMaxMp(req.get("maxMp").getAsInt());
        if (req.has("attack")) p.setAttack(req.get("attack").getAsInt());
        if (req.has("defense")) p.setDefense(req.get("defense").getAsInt());
        if (req.has("speed")) p.setSpeed(req.get("speed").getAsInt());
        if (req.has("spirit")) p.setSpirit(req.get("spirit").getAsInt());
        if (req.has("level")) p.setLevel(req.get("level").getAsInt());
        if (req.has("gold")) p.setGold(req.get("gold").getAsLong());
        if (req.has("experience")) p.setExperience(req.get("experience").getAsLong());
        if (req.has("realm")) p.setRealm(req.get("realm").getAsInt());
        if (req.has("subRealm")) p.setSubRealm(req.get("subRealm").getAsInt());

        playerService.updatePlayer(playerId, p);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h09fd94fc"));
        return Response.ok(gson.toJson(result)).build();
    }

    @DELETE
    @Path("/players/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response deletePlayer(@PathParam("id") long playerId) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        boolean success = playerService.deletePlayer(playerId);
        if (success) {
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h7e7ba6b0"));
            return Response.ok(gson.toJson(result)).build();
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h1a6a52b8"));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/players/{id}/rename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response renamePlayer(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String newName = req.has("name") ? req.get("name").getAsString() : null;

        if (newName == null || newName.trim().isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hbb03c877"));
            return Response.ok(gson.toJson(err)).build();
        }

        String oldName = p.getName();
        boolean success = playerService.updatePlayerName(playerId, newName);

        if (success) {
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h3cc04960"));
            result.addProperty("oldName", oldName);
            result.addProperty("newName", newName);
            return Response.ok(gson.toJson(result)).build();
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h8768d477"));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/players/{id}/spiritual-root")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response setSpiritualRoot(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String rootName = req.has("spiritualRoot") ? req.get("spiritualRoot").getAsString() : null;
        if (rootName == null || rootName.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h1a5ea364"));
            return Response.ok(gson.toJson(err)).build();
        }

        try {
            com.mtxgdn.game.entity.SpiritualRoot root =
                    com.mtxgdn.game.entity.SpiritualRoot.valueOf(rootName.toUpperCase());
            playerService.updateSpiritualRoot(playerId, root);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.hb6f60f7b", root.getDisplayName(), root.getTier().getDisplayName()));
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "无效的灵根名称: " + rootName + "，可用灵根: " +
                    java.util.Arrays.stream(com.mtxgdn.game.entity.SpiritualRoot.values())
                            .map(r -> r.name())
                            .reduce((a, b) -> a + ", " + b).orElse(""));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/spiritual-roots")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getSpiritualRoots() {
        JsonArray arr = new JsonArray();
        for (com.mtxgdn.game.entity.SpiritualRoot root : com.mtxgdn.game.entity.SpiritualRoot.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", root.name());
            obj.addProperty("displayName", root.getDisplayName());
            obj.addProperty("tier", root.getTier().getDisplayName());
            obj.addProperty("tierKey", root.getTier().name());
            obj.addProperty("description", root.getDescription());
            obj.addProperty("attackBonus", root.getAttackBonus());
            obj.addProperty("hpBonus", root.getHpBonus());
            obj.addProperty("mpBonus", root.getMpBonus());
            obj.addProperty("defenseBonus", root.getDefenseBonus());
            obj.addProperty("speedBonus", root.getSpeedBonus());
            obj.addProperty("spiritBonus", root.getSpiritBonus());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("roots", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/items")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getAllItems() {
        JsonArray arr = new JsonArray();
        for (Item item : ItemRegistry.getAll()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", item.getFullKey());
            obj.addProperty("name", item.getName());
            obj.addProperty("description", item.getDescription());
            obj.addProperty("type", item.getType().name());
            obj.addProperty("rarity", item.getRarity().name());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("items", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/realms")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getRealms() {
        JsonArray arr = new JsonArray();
        for (RealmConfig config : GameConfigLoader.getRealmConfigs()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", config.getId());
            obj.addProperty("subRealm", config.getSubRealm());
            obj.addProperty("name", config.getName());
            obj.addProperty("description", config.getDescription());
            obj.addProperty("requiredExp", config.getRequiredExp());
            obj.addProperty("requiredSpiritStones", config.getRequiredSpiritStones());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("realms", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/titles")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getTitles() {
        TitleRegistry.init();
        JsonArray arr = new JsonArray();
        for (Title title : TitleRegistry.getAll()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", title.getKey());
            obj.addProperty("name", title.getName());
            obj.addProperty("description", title.getDescription());
            obj.addProperty("rarity", title.getRarity().name());
            obj.addProperty("requiredRealm", title.getRequiredRealm());
            obj.addProperty("attackBonus", title.getAttackBonus());
            obj.addProperty("defenseBonus", title.getDefenseBonus());
            obj.addProperty("speedBonus", title.getSpeedBonus());
            obj.addProperty("spiritBonus", title.getSpiritBonus());
            obj.addProperty("hpBonus", title.getHpBonus());
            obj.addProperty("mpBonus", title.getMpBonus());
            obj.addProperty("experienceBonus", title.getExperienceBonus());
            obj.addProperty("dropRateBonus", title.getDropRateBonus());
            obj.addProperty("cultivationSpeedBonus", title.getCultivationSpeedBonus());
            obj.addProperty("damageBonus", title.getDamageBonus());
            obj.addProperty("damageReduction", title.getDamageReduction());
            arr.add(obj);
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("titles", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    

    @POST
    @Path("/players/{id}/title")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response setPlayerTitle(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String titleKey = req.has("titleKey") ? req.get("titleKey").getAsString() : null;

        var ts = ServiceRegistry.getTitleService();

        if (titleKey == null || titleKey.isBlank()) {
            var result = ts.unequipTitle(playerId);
            JsonObject resp = new JsonObject();
            resp.addProperty("code", (boolean) result.get("success") ? 200 : 400);
            resp.addProperty("message", result.get("message").toString());
            return Response.ok(gson.toJson(resp)).build();
        }

        var result = ts.equipTitle(playerId, titleKey);
        JsonObject resp = new JsonObject();
        resp.addProperty("code", (boolean) result.get("success") ? 200 : 400);
        resp.addProperty("message", result.get("message").toString());
        return Response.ok(gson.toJson(resp)).build();
    }

    @POST
    @Path("/players/{id}/title/add")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response addPlayerTitle(@PathParam("id") long playerId, String body) {
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", I18n.t("admin.h796f4b70"));
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String titleKey = req.has("titleKey") ? req.get("titleKey").getAsString() : null;

        if (titleKey == null || titleKey.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.he8f4ef00"));
            return Response.ok(gson.toJson(err)).build();
        }

        var ts = ServiceRegistry.getTitleService();
        var result = ts.grantTitle(playerId, titleKey);

        JsonObject resp = new JsonObject();
        resp.addProperty("code", (boolean) result.get("success") ? 200 : 400);
        resp.addProperty("message", result.get("message").toString());
        return Response.ok(gson.toJson(resp)).build();
    }

    @GET
    @Path("/player-traces")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.logs.view")
    public Response getPlayerTraces(
            @QueryParam("userId") Long userId,
            @QueryParam("playerName") @DefaultValue("") String playerName,
            @QueryParam("action") @DefaultValue("") String action,
            @QueryParam("qqNumber") @DefaultValue("") String qqNumber,
            @QueryParam("startTime") @DefaultValue("") String startTime,
            @QueryParam("endTime") @DefaultValue("") String endTime,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {

        List<Map<String, Object>> logs = DatabaseManager.queryPlayerActionLogs(
                userId,
                playerName.isBlank() ? null : playerName,
                action.isBlank() ? null : action,
                qqNumber.isBlank() ? null : qqNumber,
                startTime.isBlank() ? null : startTime,
                endTime.isBlank() ? null : endTime,
                Math.min(limit, 200),
                offset);

        int total = DatabaseManager.countPlayerActionLogs(
                userId,
                playerName.isBlank() ? null : playerName,
                action.isBlank() ? null : action,
                qqNumber.isBlank() ? null : qqNumber,
                startTime.isBlank() ? null : startTime,
                endTime.isBlank() ? null : endTime);

        JsonArray arr = new JsonArray();
        for (Map<String, Object> log : logs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ((Number) log.get("id")).longValue());
            obj.addProperty("userId", ((Number) log.get("userId")).longValue());
            obj.addProperty("playerName", (String) log.get("playerName"));
            obj.addProperty("action", (String) log.get("action"));
            obj.addProperty("detail", (String) log.get("detail"));
            obj.addProperty("qqNumber", (String) log.get("qqNumber"));
            obj.addProperty("createdAt", (String) log.get("createdAt"));
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("traces", arr);
        result.addProperty("total", total);
        return Response.ok(gson.toJson(result)).build();
    }

    // ========== 数据库浏览 API ==========

    @GET
    @Path("/db/tables")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getDbTables() {
        try {
            List<String> tables = DatabaseManager.getAllTableNames();
            JsonArray arr = new JsonArray();
            for (String name : tables) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", name);
                try {
                    obj.addProperty("rowCount", DatabaseManager.countTableRows(name));
                } catch (Exception e) {
                    obj.addProperty("rowCount", -1);
                }
                arr.add(obj);
            }
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("tables", arr);
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hc24ff258", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getTableData(
            @PathParam("tableName") String tableName,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            List<Map<String, Object>> columns = DatabaseManager.getTableColumns(tableName);
            List<Map<String, Object>> rows = DatabaseManager.queryTableData(tableName, limit, offset);
            int total = DatabaseManager.countTableRows(tableName);

            JsonArray colsArr = new JsonArray();
            for (Map<String, Object> col : columns) {
                JsonObject co = new JsonObject();
                co.addProperty("name", String.valueOf(col.get("name")));
                co.addProperty("type", String.valueOf(col.get("type")));
                co.addProperty("notnull", (Boolean) col.get("notnull"));
                co.addProperty("pk", (Boolean) col.get("pk"));
                colsArr.add(co);
            }

            JsonArray rowsArr = new JsonArray();
            for (Map<String, Object> row : rows) {
                JsonObject ro = new JsonObject();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    Object val = entry.getValue();
                    if (val == null) {
                        ro.add(entry.getKey(), null);
                    } else if (val instanceof Number) {
                        ro.addProperty(entry.getKey(), (Number) val);
                    } else if (val instanceof Boolean) {
                        ro.addProperty(entry.getKey(), (Boolean) val);
                    } else {
                        ro.addProperty(entry.getKey(), String.valueOf(val));
                    }
                }
                rowsArr.add(ro);
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("columns", colsArr);
            result.add("rows", rowsArr);
            result.addProperty("total", total);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h12619b1f", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            Map<String, Object> row = DatabaseManager.getRowById(tableName, id);
            if (row == null) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 404);
                err.addProperty("message", I18n.t("admin.h2d6880bc"));
                return Response.ok(gson.toJson(err)).build();
            }

            JsonObject ro = new JsonObject();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    ro.add(entry.getKey(), null);
                } else if (val instanceof Number) {
                    ro.addProperty(entry.getKey(), (Number) val);
                } else if (val instanceof Boolean) {
                    ro.addProperty(entry.getKey(), (Boolean) val);
                } else {
                    ro.addProperty(entry.getKey(), String.valueOf(val));
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("row", ro);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h12619b1f", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response insertTableRow(
            @PathParam("tableName") String tableName,
            String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key : req.keySet()) {
                var elem = req.get(key);
                if (elem.isJsonNull()) {
                    data.put(key, null);
                } else if (elem.getAsJsonPrimitive().isNumber()) {
                    data.put(key, elem.getAsNumber());
                } else if (elem.getAsJsonPrimitive().isBoolean()) {
                    data.put(key, elem.getAsBoolean());
                } else {
                    data.put(key, elem.getAsString());
                }
            }

            long newId = DatabaseManager.insertRow(tableName, data);
            reloadMapCacheIfNeeded(tableName);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h34edd3d4"));
            result.addProperty("id", newId);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h00199ba0", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response updateTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id,
            String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key : req.keySet()) {
                var elem = req.get(key);
                if (elem.isJsonNull()) {
                    data.put(key, null);
                } else if (elem.getAsJsonPrimitive().isNumber()) {
                    data.put(key, elem.getAsNumber());
                } else if (elem.getAsJsonPrimitive().isBoolean()) {
                    data.put(key, elem.getAsBoolean());
                } else {
                    data.put(key, elem.getAsString());
                }
            }

            int updated = DatabaseManager.updateRow(tableName, id, data);
            if (updated > 0) {
                reloadMapCacheIfNeeded(tableName);
            }

            JsonObject result = new JsonObject();
            if (updated > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.h49113c80"));
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.hd2524528"));
            }
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hae754cdb", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            int deleted = DatabaseManager.deleteRow(tableName, id);
            if (deleted > 0) {
                reloadMapCacheIfNeeded(tableName);
            }

            JsonObject result = new JsonObject();
            if (deleted > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.h7e7ba6b0"));
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.h2d6880bc"));
            }
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h4360d4cc", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ========== 数据库备份与导入 ==========

    @GET
    @Path("/backup")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response backupDatabase() {
        try {
            String json = DatabaseManager.exportAllData();
            return Response.ok(json, MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=\"xiuxian_backup_" +
                            java.time.LocalDate.now() + ".json\"")
                    .build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h04e20937", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/backup/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response importBackup(String body) {
        try {
            Map<String, Integer> counts = DatabaseManager.importData(body);

            // 重新初始化默认数据
            new SkillService().insertDefaultSkills();
            new TechniqueService().insertDefaultTechniques();
            new CraftingService().insertDefaultRecipes();

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h7503790c"));

            int total = 0;
            JsonObject detail = new JsonObject();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                detail.addProperty(entry.getKey(), entry.getValue());
                if (!entry.getKey().endsWith("_deleted") && !entry.getKey().endsWith("_errors") && !entry.getKey().endsWith("_delete_error")) {
                    total += entry.getValue();
                }
            }
            result.addProperty("totalImported", total);
            result.add("detail", detail);

            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h6f8a2d18", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ========== 兑换码管理 API ==========

    private static final com.mtxgdn.game.service.RedeemCodeService redeemCodeService =
            new com.mtxgdn.game.service.RedeemCodeService();

    @GET
    @Path("/redeem-codes")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response getRedeemCodes() {
        List<com.mtxgdn.game.entity.RedeemCode> codes = redeemCodeService.listAll();
        JsonArray arr = new JsonArray();
        for (com.mtxgdn.game.entity.RedeemCode rc : codes) {
            arr.add(toRedeemCodeJson(rc));
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("codes", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/redeem-codes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response createRedeemCode(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String code = req.has("code") ? req.get("code").getAsString() : "";
            String name = req.has("name") ? req.get("name").getAsString() : "";
            long gold = req.has("gold") ? req.get("gold").getAsLong() : 0;
            long spiritStones = req.has("spiritStones") ? req.get("spiritStones").getAsLong() : 0;
            long exp = req.has("exp") ? req.get("exp").getAsLong() : 0;
            int maxUses = req.has("maxUses") ? req.get("maxUses").getAsInt() : 1;
            String expiresAt = req.has("expiresAt") && !req.get("expiresAt").isJsonNull()
                    ? req.get("expiresAt").getAsString() : null;
            String createdBy = req.has("createdBy") ? req.get("createdBy").getAsString() : "admin";

            if (code.isBlank()) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.h6c0eef00"));
                return Response.ok(gson.toJson(err)).build();
            }

            Map<String, Integer> items = new LinkedHashMap<>();
            if (req.has("items") && req.get("items").isJsonObject()) {
                JsonObject itemsObj = req.getAsJsonObject("items");
                for (String key : itemsObj.keySet()) {
                    items.put(key, itemsObj.get(key).getAsInt());
                }
            }

            com.mtxgdn.game.entity.RedeemCode rc = redeemCodeService.createCode(
                    code, name, items, gold, spiritStones, exp, maxUses, expiresAt, createdBy);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h46172edd"));
            result.add("codeInfo", toRedeemCodeJson(rc));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hacdab49b", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/redeem-codes/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response updateRedeemCode(@PathParam("id") long id, String body) {
        try {
            // 已兑换或已过期的码不允许编辑
            com.mtxgdn.game.entity.RedeemCode existing = redeemCodeService.findById(id);
            if (existing != null && (existing.isRedeemed() || existing.isExpired())) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.hd589e27c"));
                return Response.ok(gson.toJson(err)).build();
            }

            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String name = req.has("name") ? req.get("name").getAsString() : "";
            long gold = req.has("gold") ? req.get("gold").getAsLong() : 0;
            long spiritStones = req.has("spiritStones") ? req.get("spiritStones").getAsLong() : 0;
            long exp = req.has("exp") ? req.get("exp").getAsLong() : 0;
            int maxUses = req.has("maxUses") ? req.get("maxUses").getAsInt() : 1;
            String expiresAt = req.has("expiresAt") && !req.get("expiresAt").isJsonNull()
                    ? req.get("expiresAt").getAsString() : null;

            Map<String, Integer> items = new LinkedHashMap<>();
            if (req.has("items") && req.get("items").isJsonObject()) {
                JsonObject itemsObj = req.getAsJsonObject("items");
                for (String key : itemsObj.keySet()) {
                    items.put(key, itemsObj.get(key).getAsInt());
                }
            }

            redeemCodeService.updateCode(id, name, items, gold, spiritStones, exp, maxUses, expiresAt);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h4d604ddc"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hae754cdb", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/redeem-codes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response deleteRedeemCode(@PathParam("id") long id) {
        try {
            redeemCodeService.deleteCode(id);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h2bff2074"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h4360d4cc", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    private JsonObject toRedeemCodeJson(com.mtxgdn.game.entity.RedeemCode rc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", rc.getId());
        obj.addProperty("code", rc.getCode());
        obj.addProperty("name", rc.getName());
        obj.addProperty("gold", rc.getGold());
        obj.addProperty("spiritStones", rc.getSpiritStones());
        obj.addProperty("exp", rc.getExp());
        obj.addProperty("maxUses", rc.getMaxUses());
        obj.addProperty("currentUses", rc.getCurrentUses());
        obj.addProperty("status", rc.getStatus());
        obj.addProperty("isActive", rc.isActive());
        obj.addProperty("expiresAt", rc.getExpiresAt());
        obj.addProperty("createdBy", rc.getCreatedBy());
        obj.addProperty("createdAt", rc.getCreatedAt());

        Map<String, Integer> items = rc.getItems();
        if (items != null && !items.isEmpty()) {
            JsonObject itemsObj = new JsonObject();
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                itemsObj.addProperty(e.getKey(), e.getValue());
            }
            obj.add("items", itemsObj);
        }
        return obj;
    }

    // ========== 消息与指令统计 API ==========

    @GET
    @Path("/stats/messages")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getMessageStats() {
        JsonObject stats = StatsCollector.getInstance().getMessageStats();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("stats", stats);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/stats/commands")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getCommandStats() {
        JsonObject stats = StatsCollector.getInstance().getCommandStats();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("stats", stats);
        return Response.ok(gson.toJson(result)).build();
    }

    // ========== 黑名单管理 API ==========

    @GET
    @Path("/blacklist")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response getBlacklist() {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        java.util.List<com.mtxgdn.onebot.Blacklist> list = blacklistService.getAllBlacklist();

        JsonArray arr = new JsonArray();
        for (com.mtxgdn.onebot.Blacklist b : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", b.getId());
            obj.addProperty("qqNumber", b.getQqNumber());
            obj.addProperty("userId", b.getUserId());
            obj.addProperty("reason", b.getReason());
            obj.addProperty("bannedBy", b.getBannedBy());
            obj.addProperty("createdAt", b.getCreatedAt());
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("blacklist", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/blacklist")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response addToBlacklist(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        // qqNumber 和 userId 二选一
        String qqNumber = req.has("qqNumber") && !req.get("qqNumber").isJsonNull() ? req.get("qqNumber").getAsString() : null;
        Long userId = req.has("userId") && !req.get("userId").isJsonNull() ? req.get("userId").getAsLong() : null;
        String reason = req.has("reason") ? req.get("reason").getAsString() : "";

        boolean hasQq = qqNumber != null && !qqNumber.isBlank();
        boolean hasUid = userId != null;

        if (!hasQq && !hasUid) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h7443b43e"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (hasQq && hasUid) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hacbe32d4"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();

        try {
            blacklistService.addToBlacklist(qqNumber, userId, reason, null);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", hasUid ? "已通过用户ID添加到黑名单" : "已通过QQ号添加到黑名单");
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/blacklist/qq/{qqNumber}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response removeFromBlacklistByQq(@PathParam("qqNumber") String qqNumber) {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        try {
            blacklistService.removeFromBlacklist(qqNumber);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0ae42a94"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/blacklist/user/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response removeFromBlacklistByUserId(@PathParam("userId") long userId) {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        try {
            blacklistService.removeFromBlacklistByUserId(userId);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0ae42a94"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    // ========== OneBot群组配置 API ==========

    @GET
    @Path("/onebot/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.onebot.group.config")
    public Response getOneBotGroupConfigs() {
        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        java.util.List<com.mtxgdn.onebot.OneBotGroupConfig> list = configService.getAllConfigs();

        JsonArray arr = new JsonArray();
        for (com.mtxgdn.onebot.OneBotGroupConfig c : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", c.getId());
            obj.addProperty("groupId", c.getGroupId());
            obj.addProperty("autoMuteEnabled", c.isAutoMuteEnabled());
            obj.addProperty("muteDurationDays", c.getMuteDurationDays());
            obj.addProperty("createdAt", c.getCreatedAt());
            obj.addProperty("updatedAt", c.getUpdatedAt());
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("groups", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/onebot/groups/{groupId}/autoMute")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.onebot.group.config")
    public Response setAutoMute(@PathParam("groupId") long groupId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        boolean enabled = req.has("enabled") ? req.get("enabled").getAsBoolean() : false;

        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        configService.setAutoMute(groupId, enabled);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", enabled ? "已启用自动禁言" : "已关闭自动禁言");
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/onebot/groups/{groupId}/muteDuration")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.onebot.group.config")
    public Response setMuteDuration(@PathParam("groupId") long groupId, String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        int days = req.has("days") ? req.get("days").getAsInt() : 29;

        if (days < 1 || days > 30) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hc5532e10"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        configService.setMuteDuration(groupId, days);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h4ae1db23", days));
        return Response.ok(gson.toJson(result)).build();
    }

    @DELETE
    @Path("/onebot/groups/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.onebot.group.config")
    public Response deleteGroupConfig(@PathParam("groupId") long groupId) {
        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        try {
            configService.deleteConfig(groupId);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.hd952c78c"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    // ==================== 称号管理 ====================

    @POST
    @Path("/players/{playerId}/titles")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.titles.manage")
    public Response grantTitle(@PathParam("playerId") long playerId, String body) {
        JsonObject req = gson.fromJson(body, JsonObject.class);
        String titleKey = req.has("titleKey") ? req.get("titleKey").getAsString() : "";
        if (titleKey.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hd7921d08"));
            return Response.ok(gson.toJson(err)).build();
        }
        var ts = ServiceRegistry.getTitleService();
        var result = ts.grantTitle(playerId, titleKey);
        return Response.ok(gson.toJson(result)).build();
    }

    @DELETE
    @Path("/players/{playerId}/titles/{titleKey}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.titles.manage")
    public Response revokeTitle(@PathParam("playerId") long playerId, @PathParam("titleKey") String titleKey) {
        var ts = ServiceRegistry.getTitleService();
        var result = ts.revokeTitle(playerId, titleKey);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/players/{playerId}/titles")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.titles.manage")
    public Response getPlayerTitles(@PathParam("playerId") long playerId) {
        var ts = ServiceRegistry.getTitleService();
        var titles = ts.getPlayerTitles(playerId);
        var eq = ts.getEquippedTitle(playerId);
        JsonArray arr = new JsonArray();
        for (var t : titles) {
            JsonObject o = new JsonObject();
            for (var entry : t.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String) o.addProperty(entry.getKey(), (String) val);
                else if (val instanceof Number) o.addProperty(entry.getKey(), (Number) val);
                else if (val instanceof Boolean) o.addProperty(entry.getKey(), (Boolean) val);
            }
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("titles", arr);
        if (eq != null) {
            JsonObject eo = new JsonObject();
            eo.addProperty("key", eq.getKey());
            eo.addProperty("name", eq.getName());
            data.add("equipped", eo);
        }
        return Response.ok(data.toString()).build();
    }

    @GET
    @Path("/titles/catalog")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.titles.manage")
    public Response getTitleCatalog() {
        TitleRegistry.init();
        JsonArray arr = new JsonArray();
        for (var t : TitleRegistry.getAll()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", t.getKey());
            o.addProperty("name", t.getName());
            o.addProperty("description", t.getDescription());
            o.addProperty("rarity", t.getRarity().name());
            o.addProperty("rarityLabel", t.getRarityLabel());
            o.addProperty("requiredRealm", t.getRequiredRealm());
            o.addProperty("attackBonus", t.getAttackBonus());
            o.addProperty("hpBonus", t.getHpBonus());
            o.addProperty("mpBonus", t.getMpBonus());
            o.addProperty("expBonus", t.getExpBonus());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("titles", arr);
        return Response.ok(data.toString()).build();
    }

    // ========== 新人奖励配置 API ==========

    @GET
    @Path("/newbie-reward/config")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.config.manage")
    public Response getNewbieRewardConfig() {
        com.mtxgdn.game.config.NewbieRewardConfig config = com.mtxgdn.game.config.GameConfigLoader.getNewbieRewardConfig();
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("enabled", config.isEnabled());
        result.addProperty("goldReward", config.getGoldReward());
        result.addProperty("spiritStoneReward", config.getSpiritStoneReward());
        result.addProperty("spiritStoneGrade", config.getSpiritStoneGrade());
        JsonArray itemsArr = new JsonArray();
        if (config.getItems() != null) {
            for (com.mtxgdn.game.config.NewbieRewardConfig.RewardItem item : config.getItems()) {
                JsonObject itemObj = new JsonObject();
                itemObj.addProperty("itemKey", item.getItemKey());
                itemObj.addProperty("quantity", item.getQuantity());
                itemsArr.add(itemObj);
            }
        }
        result.add("items", itemsArr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/newbie-reward/config")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.config.manage")
    public Response updateNewbieRewardConfig(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            com.mtxgdn.game.config.NewbieRewardConfig config = new com.mtxgdn.game.config.NewbieRewardConfig();

            config.setEnabled(req.has("enabled") ? req.get("enabled").getAsBoolean() : false);
            config.setGoldReward(req.has("goldReward") ? req.get("goldReward").getAsLong() : 0);
            config.setSpiritStoneReward(req.has("spiritStoneReward") ? req.get("spiritStoneReward").getAsLong() : 0);
            config.setSpiritStoneGrade(req.has("spiritStoneGrade") ? req.get("spiritStoneGrade").getAsInt() : 0);

            java.util.List<com.mtxgdn.game.config.NewbieRewardConfig.RewardItem> items = new java.util.ArrayList<>();
            if (req.has("items") && req.get("items").isJsonArray()) {
                JsonArray itemsArr = req.getAsJsonArray("items");
                for (int i = 0; i < itemsArr.size(); i++) {
                    JsonObject itemObj = itemsArr.get(i).getAsJsonObject();
                    String itemKey = itemObj.has("itemKey") ? itemObj.get("itemKey").getAsString() : null;
                    long quantity = itemObj.has("quantity") ? itemObj.get("quantity").getAsLong() : 0;
                    if (itemKey != null && !itemKey.isBlank() && quantity > 0) {
                        items.add(new com.mtxgdn.game.config.NewbieRewardConfig.RewardItem(itemKey, quantity));
                    }
                }
            }
            config.setItems(items);

            com.mtxgdn.game.config.GameConfigLoader.saveNewbieRewardConfig(config);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.ha9c62e68"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hb3585ce8", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/system/info")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.status")
    public Response getSystemInfo() {
        JsonObject data = new JsonObject();
        data.addProperty("serverVersion", com.mtxgdn.util.BuildInfo.getDisplayVersion());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty("osName", System.getProperty("os.name"));
        data.addProperty("osVersion", System.getProperty("os.version"));
        data.addProperty("architecture", System.getProperty("os.arch"));
        
        Runtime runtime = Runtime.getRuntime();
        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);
        long totalMB = runtime.totalMemory() / (1024 * 1024);
        data.addProperty("usedMemoryMB", usedMB);
        data.addProperty("maxMemoryMB", maxMB);
        data.addProperty("totalMemoryMB", totalMB);
        data.addProperty("freeMemoryMB", (runtime.freeMemory() / (1024 * 1024)));
        
        long uptime = System.currentTimeMillis() - Main.serverStartTime;
        data.addProperty("uptime", uptime);
        data.addProperty("uptimeFormatted", formatUptime(uptime));
        
        data.addProperty("cpuCores", runtime.availableProcessors());
        
        int onlineCount = 0;
        if (Main.gameWebSocketApp != null) {
            onlineCount = Main.gameWebSocketApp.getOnlineCount();
        }
        data.addProperty("onlinePlayers", onlineCount);
        data.addProperty("totalPlayers", playerService.getPlayerCount());
        
        return Response.ok(gson.toJson(data)).build();
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
                result.addProperty("message", I18n.t("admin.hbaa88444"));
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
            result.addProperty("message", I18n.t("admin.h6e816fc4"));
            return Response.ok(gson.toJson(result)).build();
        }
        
        result.addProperty("code", 401);
        result.addProperty("message", I18n.t("admin.hbaa88444"));
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
                result.addProperty("message", I18n.t("admin.hbaa88444"));
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
                result.addProperty("message", I18n.t("admin.hbaa88444"));
                return Response.ok(gson.toJson(result)).build();
            }
            
            var player = playerService.getPlayerByUserId(userId);
            if (player == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.hb05a7090"));
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
            result.addProperty("message", I18n.t("admin.h883fd7a8", e.getMessage()));
            return Response.ok(gson.toJson(result)).build();
        }
    }

    @POST
    @Path("/announce")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.announce")
    public Response sendAnnounce(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String text = req.has("text") ? req.get("text").getAsString() : "";
        
        if (text.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h3ab62198"));
            return Response.ok(gson.toJson(err)).build();
        }
        
        if (Main.gameWebSocketApp != null) {
            JsonObject data = new JsonObject();
            data.addProperty("announcement", text);
            Main.gameWebSocketApp.broadcast(new com.mtxgdn.common.GameMessage(0, "announcement", 200, "公告", data));
        }
        
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.h5f4d6aa0"));
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/authority")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.users.manage")
    public Response setAuthority(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String userIdStr = req.has("userId") ? req.get("userId").getAsString() : "";
        String level = req.has("level") ? req.get("level").getAsString() : "";
        
        if (userIdStr.isBlank() || level.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h7c8dde64"));
            return Response.ok(gson.toJson(err)).build();
        }
        
        long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.ha80419fc"));
            return Response.ok(gson.toJson(err)).build();
        }
        
        try {
            PermissionService.assignRole(userId, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h7d2e5a9c"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.had48208c", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    private static String formatUptime(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return String.format("%dd %02d:%02d:%02d", days, hours, minutes, secs);
        }
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    // ==================== 适配器管理 API ====================

    @GET
    @Path("/adapters")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.adapters")
    public Response getAdapters() {
        JsonArray arr = new JsonArray();
        for (AdapterConfig cfg : AdapterConfigManager.getAdapters()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", cfg.getId());
            obj.addProperty("type", cfg.getType());
            obj.addProperty("enabled", cfg.isEnabled());
            obj.addProperty("running", AdapterManager.isRunning(cfg.getId()));

            JsonObject config = new JsonObject();
            for (var entry : cfg.getConfig().entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Number n) {
                    config.addProperty(entry.getKey(), n);
                } else if (val instanceof Boolean b) {
                    config.addProperty(entry.getKey(), b);
                } else {
                    config.addProperty(entry.getKey(), val != null ? val.toString() : "");
                }
            }
            obj.add("config", config);
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("data", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @PUT
    @Path("/adapters")
    @Produces("application/json;charset=UTF-8")
    @Consumes("application/json;charset=UTF-8")
    @RequirePermission("admin.adapters")
    public Response updateAdapters(String body) {
        try {
            AdaptersConfig newConfig = gson.fromJson(body, AdaptersConfig.class);
            if (newConfig == null || newConfig.getAdapters() == null) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.h0865fea7"));
                return Response.ok(gson.toJson(err)).build();
            }

            // 关键：在更新前先捕获旧配置，否则 reloadAll 无法对比差异
            AdaptersConfig oldConfig = AdapterConfigManager.getConfig().copy();
            AdapterConfigManager.updateConfig(newConfig);
            AdapterManager.reloadAll(oldConfig);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.hed9893a3"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hae754cdb", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/adapters/reload")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.adapters")
    public Response reloadAdapters() {
        try {
            AdapterManager.reloadAll();
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.hf7bba770"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h67beaa9c", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ==================== 多语言（i18n）词条管理 ====================

    /**
     * 各语言的词条数与缺失数，用于管理后台的"翻译完整度"面板。
     *
     * <p>{@code missingCount} 的口径是"zh_cn 有、该语言没有"——zh_cn 是唯一
     * 保证完整的基线语言，其它语言的缺失都以它为参照。</p>
     */
    @GET
    @Path("/i18n/status")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nStatus() {
        JsonObject data = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Map.Entry<String, Object> e : com.mtxgdn.i18n.I18nManager.status().entrySet()) {
            arr.add(gson.toJsonTree(e.getValue()).getAsJsonObject());
        }
        data.add("languages", arr);
        data.addProperty("default", com.mtxgdn.i18n.I18nManager.serverDefault().code());
        // 回退次数已经按语言放在 languages 各项的 fallbackCount 里，
        // 它是"有多少次取值靠 zh_cn 顶上了"，也就是翻译缺口最直观的指标。
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    /**
     * 导出某语言当前生效的完整词条表（含插件合并与外部覆写的结果）。
     *
     * <p>用途：运维把导出的 JSON 落到 {@code config/lang/<code>.json}，
     * 就形成了一份可持久化的覆写配置——这样在管理后台改的译文不会
     * 因为重启而丢失。</p>
     */
    @POST
    @Path("/i18n/export")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nExport(String body) {
        String requested = null;
        try {
            JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            if (json.has("lang") && !json.get("lang").isJsonNull()) {
                requested = json.get("lang").getAsString();
            }
        } catch (Exception ignored) {
            // 没传 body 或不是 JSON：导出服务端默认语言
        }

        com.mtxgdn.i18n.GameLanguage lang =
                requested == null || requested.isBlank()
                        ? com.mtxgdn.i18n.I18nManager.serverDefault()
                        : com.mtxgdn.i18n.GameLanguage.parse(requested);
        if (lang == null) {
            return Response.ok(GameMessage.restError(
                    GameErrorCode.PARAM_INVALID.getCode(),
                    "error.i18n.unsupported_language", "不支持的语言").toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("language", lang.code());
        data.addProperty("count", com.mtxgdn.i18n.I18nManager.bundle(lang).size());
        // 直接给 JSON 文本而不是嵌套对象：运维要的是能原样存盘的文件内容
        data.addProperty("content", com.mtxgdn.i18n.I18nManager.exportJson(lang));
        return Response.ok(GameMessage.restOk("导出成功", data).toString()).build();
    }

    /**
     * 重载全部语言包。
     *
     * <p>会丢掉运行期通过插件合并进来的词条，重新从 classpath 与
     * {@code config/lang/} 读——所以改完外部文件后调它即可生效，
     * 不需要重启服务。插件会在下次加载时重新合并自己的词条。</p>
     */
    @POST
    @Path("/i18n/reload")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.i18n.manage")
    public Response i18nReload() {
        try {
            com.mtxgdn.i18n.I18nManager.reload();
            JsonObject data = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Map.Entry<String, Object> e : com.mtxgdn.i18n.I18nManager.status().entrySet()) {
                arr.add(gson.toJsonTree(e.getValue()).getAsJsonObject());
            }
            data.add("languages", arr);
            return Response.ok(GameMessage.restOk("语言包已重载", data).toString()).build();
        } catch (Exception e) {
            return Response.ok(GameMessage.restError(
                    GameErrorCode.INTERNAL_ERROR.getCode(),
                    "error.i18n.reload_failed", "重载语言包失败: " + e.getMessage()).toString()).build();
        }
    }

    // ==================== 玩家数据文件（一用户一个文件）====================

    @POST
    @Path("/player-data/export/{playerId}")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response exportPlayerData(@PathParam("playerId") long playerId) {
        com.mtxgdn.playerdata.PlayerDataService dataService = new com.mtxgdn.playerdata.PlayerDataService();
        Map<String, Object> result = dataService.exportPlayer(playerId);
        JsonObject out = new JsonObject();
        out.addProperty("code", Boolean.TRUE.equals(result.get("success")) ? 200 : 400);
        out.addProperty("message", (String) result.get("message"));
        return Response.ok(gson.toJson(out)).build();
    }

    @POST
    @Path("/player-data/export-all")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response exportAllPlayerData() {
        com.mtxgdn.playerdata.PlayerDataService dataService = new com.mtxgdn.playerdata.PlayerDataService();
        Map<String, Object> result = dataService.exportAllPlayers();
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("message", "已导出 " + result.get("exported") + " 名玩家数据，失败 " + result.get("failed") + " 个");
        out.addProperty("dir", (String) result.get("dir"));
        return Response.ok(gson.toJson(out)).build();
    }

    @GET
    @Path("/player-data/list")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response listPlayerDataFiles() {
        com.mtxgdn.playerdata.PlayerDataService dataService = new com.mtxgdn.playerdata.PlayerDataService();
        List<String> files = dataService.listPlayerFiles();
        JsonArray arr = new JsonArray();
        for (String f : files) {
            arr.add(f);
        }
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("count", files.size());
        out.add("files", arr);
        return Response.ok(gson.toJson(out)).build();
    }

    // ==================== OAuth2 配置管理 ====================

    @GET
    @Path("/oauth/config")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.oauth.manage")
    public Response getOAuthConfig() {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        JsonObject data = new JsonObject();
        for (Map.Entry<String, Object> entry : com.mtxgdn.service.OAuthService.getConfig(false).entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Boolean b) {
                data.addProperty(entry.getKey(), b);
            } else if (v instanceof Number n) {
                data.addProperty(entry.getKey(), n);
            } else {
                data.addProperty(entry.getKey(), v != null ? v.toString() : "");
            }
        }
        out.add("data", data);
        return Response.ok(gson.toJson(out)).build();
    }

    @POST
    @Path("/oauth/config")
    @Produces("application/json;charset=UTF-8")
    @Consumes("application/json;charset=UTF-8")
    @RequirePermission("admin.oauth.manage")
    public Response updateOAuthConfig(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> update = new LinkedHashMap<>();
            if (req.has("enabled")) update.put("enabled", req.get("enabled").getAsBoolean());
            if (req.has("client_id")) update.put("client_id", req.get("client_id").getAsString());
            if (req.has("client_secret")) update.put("client_secret", req.get("client_secret").getAsString());
            if (req.has("scopes")) update.put("scopes", req.get("scopes").getAsString());
            if (req.has("token_ttl_seconds")) update.put("token_ttl_seconds", req.get("token_ttl_seconds").getAsLong());

            Map<String, Object> result = com.mtxgdn.service.OAuthService.updateConfig(update);
            JsonObject out = new JsonObject();
            out.addProperty("code", 200);
            out.addProperty("message", (String) result.get("message"));
            JsonObject data = new JsonObject();
            for (Map.Entry<String, Object> entry : com.mtxgdn.service.OAuthService.getConfig(false).entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean b) {
                    data.addProperty(entry.getKey(), b);
                } else if (v instanceof Number n) {
                    data.addProperty(entry.getKey(), n);
                } else {
                    data.addProperty(entry.getKey(), v != null ? v.toString() : "");
                }
            }
            out.add("data", data);
            return Response.ok(gson.toJson(out)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hdb599e78", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ==================== 地图管理 ====================

    /**
     * 地图总览：全部地点（含境界名、灵气/地界倍率、安全区、邻居与路程、事件偏置）+ 区域分组。
     *
     * <p>通用表管理只能看到 map_locations 的裸行：境界是个数字、邻接关系在另一张表里、
     * 加成是两列 DOUBLE、事件偏置又在第三张表。地图页把它们拼成"一张看得懂的地图"，
     * 否则管理员想确认"问道峰到底要什么境界"得自己联三张表。</p>
     */
    @GET
    @Path("/map/locations")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getMapLocations() {
        JsonObject out = new JsonObject();
        try {
            var mapService = ServiceRegistry.getMapService();
            mapService.ensureInitialized();

            // 已知世界与野外同在一张表，但后台必须分开计数：
            // 已知世界是固定的 15 个地点 / 17 条边（历史不变量，改坏了要能立刻看出来），
            // 野外是随机生成、会刷新的临时节点，数量与连接每次刷新都不同。
            // 混在一起统计，"地点数/连接数"就成了一个每次都变、无法判断是否正常的数字。
            Set<Long> knownIds = new java.util.HashSet<>();
            for (var loc : mapService.getAllLocations()) {
                knownIds.add(loc.getId());
            }

            JsonArray arr = new JsonArray();
            Map<String, Integer> regionCounts = new LinkedHashMap<>();
            int directedEdges = 0;
            int directedWildernessEdges = 0;
            // 野外不在库中，因此这里列的只有已知世界；野外分片另有 /admin/map/wilderness 视图
            for (var loc : mapService.getAllLocations()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", loc.getId());
                o.addProperty("name", loc.getName());
                o.addProperty("region", loc.getRegion());
                o.addProperty("description", loc.getDescription());
                o.addProperty("minRealm", loc.getMinRealm());
                o.addProperty("minRealmName", mapService.getRealmName(loc.getMinRealm()));
                o.addProperty("safeZone", loc.isSafeZone());
                o.addProperty("cultivationBonus", loc.getCultivationBonus());
                o.addProperty("explorationBonus", loc.getExplorationBonus());
                o.addProperty("dangerLevel", loc.getDangerLevel());
                o.addProperty("dangerName", mapService.getDangerName(loc.getDangerLevel()));
                o.addProperty("wilderness", loc.isWilderness());

                JsonArray neighbors = new JsonArray();
                for (var nb : loc.getConnections()) {
                    JsonObject n = new JsonObject();
                    n.addProperty("id", nb.getId());
                    n.addProperty("name", nb.getName());
                    n.addProperty("region", nb.getRegion());
                    n.addProperty("travelSeconds", mapService.getTravelTimeSeconds(loc.getId(), nb.getId()));
                    n.addProperty("wilderness", nb.isWilderness());
                    neighbors.add(n);

                    if (knownIds.contains(loc.getId()) && knownIds.contains(nb.getId())) {
                        directedEdges++;
                    } else {
                        directedWildernessEdges++;
                    }
                }
                o.add("neighbors", neighbors);

                JsonObject bias = new JsonObject();
                for (Map.Entry<String, Double> entry : mapService.getEventBias(loc.getId()).entrySet()) {
                    bias.addProperty(entry.getKey(), entry.getValue());
                }
                o.add("eventBias", bias);

                arr.add(o);
                regionCounts.merge(loc.getRegion(), 1, Integer::sum);
            }

            JsonObject data = new JsonObject();
            data.add("locations", arr);
            data.addProperty("locationCount", knownIds.size());
            data.addProperty("wildernessCount",
                    ((Number) mapService.getWildernessStats().get("chunkCount")).longValue());
            // 连接在库里是双向写入的，按无向边计数要除以 2
            data.addProperty("connectionCount", directedEdges / 2);
            data.addProperty("wildernessConnectionCount", directedWildernessEdges / 2);
            data.addProperty("wildernessEnabled", mapService.isWildernessEnabled());
            data.addProperty("wildernessRefreshAt", 0);
            JsonArray regions = new JsonArray();
            for (Map.Entry<String, Integer> entry : regionCounts.entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("name", entry.getKey());
                r.addProperty("count", entry.getValue());
                regions.add(r);
            }
            data.add("regions", regions);

            out.addProperty("code", 200);
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h85569e18", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 更新地点参数（名称/描述/区域/境界门槛/安全区/灵气倍率/地界倍率），改完立即热重载。
     *
     * <p>只更新请求里出现的字段 —— "没传"不等于"清空"，避免前端漏字段就把数据抹掉。</p>
     */
    @POST
    @Path("/map/locations/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response updateMapLocation(@PathParam("id") long id, String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

            // SET 子句的列名全部来自下面这些字面量（不是用户输入），因此不存在注入面
            Map<String, Object> update = new LinkedHashMap<>();
            if (hasJsonValue(req, "name")) {
                String name = req.get("name").getAsString().trim();
                if (name.isEmpty()) throw new IllegalArgumentException("地点名不能为空");
                update.put("name", name);
            }
            if (hasJsonValue(req, "description")) {
                update.put("description", req.get("description").getAsString());
            }
            if (hasJsonValue(req, "region")) {
                String region = req.get("region").getAsString().trim();
                if (region.isEmpty()) throw new IllegalArgumentException("区域名不能为空");
                update.put("region", region);
            }
            if (hasJsonValue(req, "minRealm")) {
                int realm = req.get("minRealm").getAsInt();
                int maxRealm = GameConfigLoader.getRealmTotal() - 1;
                if (realm < 0 || realm > maxRealm) {
                    throw new IllegalArgumentException("境界门槛应在 0~" + maxRealm + " 之间");
                }
                update.put("min_realm", realm);
            }
            if (hasJsonValue(req, "safeZone")) {
                update.put("is_safe_zone", req.get("safeZone").getAsBoolean());
            }
            if (hasJsonValue(req, "cultivationBonus")) {
                update.put("cultivation_bonus", parseMapBonus(req.get("cultivationBonus"), "灵气倍率"));
            }
            if (hasJsonValue(req, "explorationBonus")) {
                update.put("exploration_bonus", parseMapBonus(req.get("explorationBonus"), "地界倍率"));
            }
            if (hasJsonValue(req, "dangerLevel")) {
                int danger = req.get("dangerLevel").getAsInt();
                if (danger < 0 || danger > MAX_DANGER_LEVEL) {
                    throw new IllegalArgumentException("危险度应在 0~" + MAX_DANGER_LEVEL
                            + " 之间（0=安全 1=低危 2=中危 3=高危 4=极危）");
                }
                update.put("danger_level", danger);
            }
            if (update.isEmpty()) {
                throw new IllegalArgumentException("没有需要更新的字段");
            }

            // 不变式：安全区不能同时是险地。这里把"库里的现值"和"本次请求"合并后再校验 ——
            // 只改其中一个字段同样能造出矛盾数据（例如给已是安全区的地点填危险度 3）。
            checkDangerConsistencyOnUpdate(id, update);
            // 地点名是 /地图 go <地名> 的唯一解析依据，重名会让其中一个永远走不到
            if (update.containsKey("name")) {
                checkMapLocationNameAvailable((String) update.get("name"), id);
            }

            String sql = "UPDATE map_locations SET "
                    + String.join(", ", update.keySet().stream().map(col -> col + " = ?").toList())
                    + " WHERE id = ?";
            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                for (Object value : update.values()) {
                    if (value instanceof Boolean b) {
                        ps.setBoolean(index++, b);
                    } else if (value instanceof Integer i) {
                        ps.setInt(index++, i);
                    } else if (value instanceof Double d) {
                        ps.setDouble(index++, d);
                    } else {
                        ps.setString(index++, (String) value);
                    }
                }
                ps.setLong(index, id);
                affected = ps.executeUpdate();
            }

            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.ha4a58c18", id));
                return Response.ok(gson.toJson(out)).build();
            }

            // 改完立即热重载：否则地图缓存还是旧值，管理员会以为没改上
            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("updated", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h8889c2a8"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.hc8779714", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 倍率校验：限定 0.1~100，挡住 0、负数与手滑多打的 0。 */
    private static double parseMapBonus(com.google.gson.JsonElement element, String label) {
        double value = element.getAsDouble();
        if (Double.isNaN(value) || value < 0.1 || value > 100) {
            throw new IllegalArgumentException(label + "应在 0.1~100 之间");
        }
        return value;
    }

    // ==================== 地图增删与连接/偏置编辑 ====================

    /** 危险度上限（0=安全 1=低危 2=中危 3=高危 4=极危）。 */
    private static final int MAX_DANGER_LEVEL = 4;

    /**
     * 新建地点。
     *
     * <p>与 {@code GET /map/locations} 同路径不同方法：GET 是总览、POST 是新增，
     * 符合集合资源的常规语义。</p>
     *
     * <p>id 可省略（自动取已知世界范围内的下一个空位）。<b>不允许手工指定野外保留区
     * （≥ 野外 idBase）的 id</b>：那一段是程序按槽位循环复用的，手填进去的地点在下次
     * 野外刷新时会被当成过期节点删掉。</p>
     */
    @POST
    @Path("/map/locations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response createMapLocation(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

            String name = hasJsonValue(req, "name") ? req.get("name").getAsString().trim() : "";
            if (name.isEmpty()) throw new IllegalArgumentException("地点名不能为空");
            String region = hasJsonValue(req, "region") ? req.get("region").getAsString().trim() : "";
            if (region.isEmpty()) throw new IllegalArgumentException("区域名不能为空");

            int minRealm = hasJsonValue(req, "minRealm") ? req.get("minRealm").getAsInt() : 0;
            int maxRealm = GameConfigLoader.getRealmTotal() - 1;
            if (minRealm < 0 || minRealm > maxRealm) {
                throw new IllegalArgumentException("境界门槛应在 0~" + maxRealm + " 之间");
            }
            boolean safeZone = hasJsonValue(req, "safeZone") && req.get("safeZone").getAsBoolean();
            int dangerLevel = hasJsonValue(req, "dangerLevel") ? req.get("dangerLevel").getAsInt() : 0;
            if (dangerLevel < 0 || dangerLevel > MAX_DANGER_LEVEL) {
                throw new IllegalArgumentException("危险度应在 0~" + MAX_DANGER_LEVEL
                        + " 之间（0=安全 1=低危 2=中危 3=高危 4=极危）");
            }
            requireDangerConsistent(safeZone, dangerLevel);

            double cultivation = hasJsonValue(req, "cultivationBonus")
                    ? parseMapBonus(req.get("cultivationBonus"), "灵气倍率") : 1.0;
            double exploration = hasJsonValue(req, "explorationBonus")
                    ? parseMapBonus(req.get("explorationBonus"), "地界倍率") : 1.0;
            String description = hasJsonValue(req, "description") ? req.get("description").getAsString() : "";

            // 野外的 id 不再是"保留段"，而是按坐标算出来的极大值；管理员新建地点时
            // 只需避开这一段，防止 id 撞上某个真实存在的野外节点
            long wildernessFloor = WildernessTerrain.ID_BASE;
            long createdId;
            try (Connection conn = DatabaseManager.getConnection()) {
                if (hasJsonValue(req, "id")) {
                    createdId = req.get("id").getAsLong();
                    if (createdId <= 0) throw new IllegalArgumentException("地点 id 必须为正数");
                    if (WildernessTerrain.isWildernessId(createdId)) {
                        throw new IllegalArgumentException("id " + createdId + " 落在野外 id 段（≥ "
                                + WildernessTerrain.ID_BASE + "），该段由坐标推导、不属于已知世界；"
                                + "请改用 1~" + (WildernessTerrain.ID_BASE - 1) + " 或省略 id 由系统分配");
                    }
                    if (mapLocationExists(conn, createdId)) {
                        throw new IllegalArgumentException("地点 id " + createdId + " 已存在");
                    }
                } else {
                    createdId = nextKnownWorldLocationId(conn, wildernessFloor);
                }
                checkMapLocationNameAvailable(name, 0);

                String sql = "INSERT INTO map_locations "
                        + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus,"
                        + " exploration_bonus, danger_level, is_wilderness) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, createdId);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.setString(4, region);
                    ps.setInt(5, minRealm);
                    ps.setBoolean(6, safeZone);
                    ps.setDouble(7, cultivation);
                    ps.setDouble(8, exploration);
                    ps.setInt(9, dangerLevel);
                    ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("id", createdId);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.hd47b31cc", name, createdId));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h4073d7c0", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 删除地点。
     *
     * <p>连带删除该地点的全部连接、事件偏置与野外资源，并把仍站在这里的玩家送回
     * 出生点 —— 否则会留下一批指向不存在地点的连接，以及"人在虚空里"的存档。</p>
     */
    @DELETE
    @Path("/map/locations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapLocation(@PathParam("id") long id) {
        JsonObject out = new JsonObject();
        try {
            if (id <= 0) throw new IllegalArgumentException("地点 id 必须为正数");

            int affected;
            try (Connection conn = DatabaseManager.getConnection()) {
                String name = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM map_locations WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) name = rs.getString(1);
                    }
                }
                if (name == null) {
                    out.addProperty("code", 404);
                    out.addProperty("message", I18n.t("admin.ha4a58c18", id));
                    return Response.ok(gson.toJson(out)).build();
                }

                // 玩家先安置：留在已删除地点上的存档会在下次读取时被判为异常位置
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET current_location_id = 1, last_travel_time = 0 "
                                + "WHERE current_location_id = ?")) {
                    ps.setLong(1, id);
                    int moved = ps.executeUpdate();
                    if (moved > 0) {
                        LOG.warn("删除地点「" + name + "」(id=" + id + ") 时把 " + moved
                                + " 名玩家送回了出生点");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_connections WHERE from_location_id = ? OR to_location_id = ?")) {
                    ps.setLong(1, id);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_location_event_bias WHERE location_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM player_map_exploration WHERE location_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM map_locations WHERE id = ?")) {
                    ps.setLong(1, id);
                    affected = ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.hbd2c7468"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h4b1fa990", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 新增或更新一条<b>有向</b>连接（from → to）。
     *
     * <p>默认同时写入反向边：地图的邻接是双向可达的，只加单向会让玩家"去了回不来"，
     * 而库里历史上就出现过这种数据（{@code repairReverseConnections} 一直在兜）。
     * 确实需要单向路（例如传送阵式的单行道）时显式传 {@code oneWay: true}。</p>
     */
    @POST
    @Path("/map/connections")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response upsertMapConnection(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            long from = req.get("from").getAsLong();
            long to = req.get("to").getAsLong();
            if (from == to) throw new IllegalArgumentException("不能把地点连到它自己");
            int travelSeconds = hasJsonValue(req, "travelSeconds") ? req.get("travelSeconds").getAsInt() : 5;
            if (travelSeconds < 1 || travelSeconds > 86400) {
                throw new IllegalArgumentException("路程秒数应在 1~86400 之间");
            }
            boolean oneWay = hasJsonValue(req, "oneWay") && req.get("oneWay").getAsBoolean();

            try (Connection conn = DatabaseManager.getConnection()) {
                if (!mapLocationExists(conn, from)) throw new IllegalArgumentException("起点不存在: " + from);
                if (!mapLocationExists(conn, to)) throw new IllegalArgumentException("终点不存在: " + to);
                upsertConnection(conn, from, to, travelSeconds);
                if (!oneWay) {
                    upsertConnection(conn, to, from, travelSeconds);
                }
            }

            reloadMapCacheIfNeeded("map_connections");

            JsonObject data = new JsonObject();
            data.addProperty("from", from);
            data.addProperty("to", to);
            data.addProperty("travelSeconds", travelSeconds);
            data.addProperty("oneWay", oneWay);
            out.addProperty("code", 200);
            out.addProperty("message", oneWay
                    ? "已建立单向连接（注意：玩家去了可能回不来）"
                    : "已建立双向连接，地图缓存已热重载");
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h711068e8", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 删除连接；{@code bothWays=true}（默认）时连反向边一起删，避免留下"只能来不能回"的路。 */
    @DELETE
    @Path("/map/connections")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapConnection(@QueryParam("from") long from,
                                        @QueryParam("to") long to,
                                        @QueryParam("bothWays") @DefaultValue("true") boolean bothWays) {
        JsonObject out = new JsonObject();
        try {
            if (from <= 0 || to <= 0) throw new IllegalArgumentException("from 与 to 都必须为正数");
            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(bothWays
                         ? "DELETE FROM map_connections WHERE (from_location_id = ? AND to_location_id = ?)"
                         + " OR (from_location_id = ? AND to_location_id = ?)"
                         : "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                if (bothWays) {
                    ps.setLong(3, to);
                    ps.setLong(4, from);
                }
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.hdc76425c", from, to));
                return Response.ok(gson.toJson(out)).build();
            }

            reloadMapCacheIfNeeded("map_connections");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h673c972e", affected));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.ha9c91554", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 新增或更新某地点的事件权重偏置（事件 key → 倍率）。 */
    @POST
    @Path("/map/event-bias")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response upsertMapEventBias(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            long locationId = req.get("locationId").getAsLong();
            String eventKey = hasJsonValue(req, "eventKey") ? req.get("eventKey").getAsString().trim() : "";
            if (eventKey.isEmpty()) throw new IllegalArgumentException("事件 key 不能为空");
            if (eventKey.length() > 64) throw new IllegalArgumentException("事件 key 过长（上限 64 字符）");
            double multiplier = req.get("multiplier").getAsDouble();
            if (Double.isNaN(multiplier) || multiplier <= 0 || multiplier > 100) {
                throw new IllegalArgumentException("权重倍率应在 0~100 之间且大于 0");
            }

            try (Connection conn = DatabaseManager.getConnection()) {
                if (!mapLocationExists(conn, locationId)) {
                    throw new IllegalArgumentException("地点不存在: " + locationId);
                }
                // 先删后插而不是依赖 UPSERT 语法：SQLite 与 MySQL 的 upsert 写法不同，
                // 而这里对并发不敏感（管理台手工操作），换来一份两端通用的 SQL。
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
                    ps.setLong(1, locationId);
                    ps.setString(2, eventKey);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO map_location_event_bias (location_id, event_key, weight_multiplier) "
                                + "VALUES (?, ?, ?)")) {
                    ps.setLong(1, locationId);
                    ps.setString(2, eventKey);
                    ps.setDouble(3, multiplier);
                    ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_location_event_bias");

            JsonObject data = new JsonObject();
            data.addProperty("locationId", locationId);
            data.addProperty("eventKey", eventKey);
            data.addProperty("multiplier", multiplier);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h77706204"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.hd7073d40", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 删除某地点的一条事件偏置。 */
    @DELETE
    @Path("/map/event-bias")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapEventBias(@QueryParam("locationId") long locationId,
                                       @QueryParam("eventKey") String eventKey) {
        JsonObject out = new JsonObject();
        try {
            if (locationId <= 0) throw new IllegalArgumentException("locationId 必须为正数");
            if (eventKey == null || eventKey.isBlank()) throw new IllegalArgumentException("eventKey 不能为空");

            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
                ps.setLong(1, locationId);
                ps.setString(2, eventKey.trim());
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.hfc3d7aa4"));
                return Response.ok(gson.toJson(out)).build();
            }

            reloadMapCacheIfNeeded("map_location_event_bias");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h05227a8c"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h76c11ee3", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 手动给野外补货：把已采空的资源补回上限。
     *
     * <p>只补资源，<b>不换地形、不删任何东西</b>：野外是无限且永久的，
     * 已探索的内容不会被刷新掉，这里做的只是"矿重新长出来"。</p>
     */
    @POST
    @Path("/map/wilderness/restock")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response restockWilderness() {
        JsonObject out = new JsonObject();
        try {
            int chunks = ServiceRegistry.getMapService().restockWilderness();
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h684c40fb", chunks));
            JsonObject data = new JsonObject();
            data.addProperty("restockedChunks", chunks);
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h2dc0de88", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 野外分片总览。
     *
     * <p>野外不进数据库，所以这里读的是分片文件（{@code data/wilderness/...}）。
     * 只给只读视图：分片一旦被探索就永久固化，管理台不提供删除入口。</p>
     */
    @GET
    @Path("/map/wilderness")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response wildernessOverview(@QueryParam("cx") String cxParam,
                                       @QueryParam("cy") String cyParam) {
        JsonObject out = new JsonObject();
        try {
            var mapService = ServiceRegistry.getMapService();
            JsonObject data = new JsonObject();
            Map<String, Object> stats = mapService.getWildernessStats();
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                data.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }

            JsonArray chunks = new JsonArray();
            Integer cx = parseCoordinate(cxParam);
            Integer cy = parseCoordinate(cyParam);
            if (cx != null && cy != null) {
                chunks.add(chunkJson(mapService, cx, cy));
            } else {
                // 不给坐标时以原点为中心展示一圈，便于一眼看到"世界铺到哪了"
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        JsonObject c = chunkJson(mapService, x, y);
                        if (c.get("exists").getAsBoolean()) {
                            chunks.add(c);
                        }
                    }
                }
            }
            data.add("chunks", chunks);
            out.addProperty("code", 200);
            out.addProperty("message", "ok");
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.he469f508", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 一个分片的只读快照；{@code exists=false} 表示这片从未有人涉足、磁盘上没有文件。 */
    private static JsonObject chunkJson(com.mtxgdn.game.service.MapService mapService, int cx, int cy) {
        JsonObject c = new JsonObject();
        var nodes = mapService.getChunkNodes(cx, cy);
        var chunk = mapService.getChunk(cx, cy);
        boolean exists = chunk.hasAnyExploration() || chunk.resourcesRolled;
        c.addProperty("chunkX", cx);
        c.addProperty("chunkY", cy);
        c.addProperty("exists", exists);
        c.addProperty("pinned", chunk.pinned);
        c.addProperty("gateway", chunk.gateway);
        c.addProperty("exploredNodes", chunk.exploredNodeCount());
        c.addProperty("explorers", chunk.explorerCount());
        c.addProperty("restockAt", chunk.restockAt);
        c.addProperty("resourceEntries", countResources(chunk));
        if (!nodes.isEmpty()) {
            c.addProperty("terrain", nodes.get(0).getRegion());
            c.addProperty("dangerLevel", nodes.get(0).getDangerLevel());
            c.addProperty("dangerName", mapService.getDangerName(nodes.get(0).getDangerLevel()));
            c.addProperty("nodeCount", nodes.size());
        }
        return c;
    }

    private static int countResources(com.mtxgdn.game.map.WildernessChunk chunk) {
        if (chunk.resources == null) {
            return 0;
        }
        int count = 0;
        for (Map<String, com.mtxgdn.game.map.WildernessChunk.Resource> slot : chunk.resources.values()) {
            count += slot.size();
        }
        return count;
    }

    private static Integer parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 安全区与危险度不能同时成立（安全区意味着禁止 PK，而危险度是 PK 收益的依据）。 */
    private static void requireDangerConsistent(boolean safeZone, int dangerLevel) {
        if (safeZone && dangerLevel > 0) {
            throw new IllegalArgumentException("安全区的危险度必须为 0（安全区禁止 PK，"
                    + "危险度是 PK 掠夺的依据，两者不能同时成立）");
        }
    }

    /**
     * 更新地点时校验"改完之后"的安全区/危险度组合是否自洽。
     * <p>行不存在时静默返回，由 UPDATE 的 affected==0 统一给出 404。</p>
     */
    private static void checkDangerConsistencyOnUpdate(long id, Map<String, Object> update) {
        boolean nextSafe;
        int nextDanger;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_safe_zone, COALESCE(danger_level, 0) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                nextSafe = rs.getBoolean(1);
                nextDanger = rs.getInt(2);
            }
        } catch (SQLException e) {
            return; // 列缺失等异常交给后续 UPDATE 报错，不在这里掩盖
        }
        if (update.containsKey("is_safe_zone")) nextSafe = (Boolean) update.get("is_safe_zone");
        if (update.containsKey("danger_level")) nextDanger = (Integer) update.get("danger_level");
        requireDangerConsistent(nextSafe, nextDanger);
    }

    /**
     * 校验地点名未被占用。
     * <p>地点名是 {@code /地图 go <地名>} 与 {@code /地图 传送 <地名>} 的唯一解析依据，
     * 重名会让其中一个地点永远无法通过指令抵达（{@code getLocationByName} 只返回第一个）。</p>
     *
     * @param excludeId 更新场景下排除自身；新建传 0
     */
    private static void checkMapLocationNameAvailable(String name, long excludeId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM map_locations WHERE name = ? AND id <> ?")) {
            ps.setString(1, name);
            ps.setLong(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new IllegalArgumentException("已存在同名地点「" + name
                            + "」，重名会导致其中一个无法通过 /地图 go 抵达");
                }
            }
        }
    }

    private static boolean mapLocationExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 已知世界范围内（id &lt; 野外 idBase）的下一个空闲 id。 */
    private static long nextKnownWorldLocationId(Connection conn, long wildernessFloor) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM map_locations WHERE id < ?")) {
            ps.setLong(1, wildernessFloor);
            try (ResultSet rs = ps.executeQuery()) {
                long next = rs.next() ? rs.getLong(1) : 1L;
                return Math.max(1L, Math.min(next, wildernessFloor - 1));
            }
        }
    }

    private static void upsertConnection(Connection conn, long from, long to, int travelSeconds)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) "
                        + "VALUES (?, ?, ?)")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setInt(3, travelSeconds);
            ps.executeUpdate();
        }
    }

    /** 字段存在且不是 JSON null（区分"没传"与"传了 null"）。 */
    private static boolean hasJsonValue(JsonObject req, String key) {
        return req.has(key) && !req.get(key).isJsonNull();
    }

    // ==================== 坊市管理（挂单 / 成交行情） ====================

    /**
     * 坊市挂单总览。
     * <p>
     * 与 {@code GET /game/market} 的区别：本接口是<b>只读管理视图</b>，用管理台权限
     * （admin.economy）而非玩家权限，且不受「交易」玩法插件门控影响 ——
     * 即使交易插件已停用，管理员仍能查看存量挂单与成交历史。
     */
    @GET
    @Path("/market/listings")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.economy")
    public Response getMarketListings() {
        JsonObject out = new JsonObject();
        try {
            var listings = ServiceRegistry.getTradeService().getActiveListings();
            // 一次性查出卖家名，避免逐行查库（挂单可能有上百条）
            Map<Long, String> sellerNames = loadPlayerNames(
                    listings.stream().map(l -> l.sellerPlayerId).distinct().toList());

            JsonArray arr = new JsonArray();
            for (var l : listings) {
                Item item = ItemRegistry.get(l.itemKey);
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id);
                o.addProperty("sellerPlayerId", l.sellerPlayerId);
                o.addProperty("sellerName", sellerNames.get(l.sellerPlayerId));
                o.addProperty("itemKey", l.itemKey);
                o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
                o.addProperty("quantity", l.quantity);
                o.addProperty("priceSpiritStones", l.priceSpiritStones);
                o.addProperty("unitPrice", l.unitPrice());
                o.addProperty("fee", l.fee);
                o.addProperty("createdAt", l.createdAt);
                o.addProperty("expiresAt", l.expiresAt);
                long remaining = l.remainingMs();
                o.addProperty("remainingMs", remaining);
                // expiresAt=0（历史遗留挂单）时 remainingMs 返回 -1，语义是"永不过期"
                o.addProperty("remainingText", remaining < 0 ? "永不过期" : formatDuration(remaining));
                arr.add(o);
            }
            out.addProperty("code", 200);
            JsonObject data = new JsonObject();
            data.add("listings", arr);
            data.addProperty("count", arr.size());
            data.addProperty("listingDurationHours", TradeService.getListingDurationHours());
            data.addProperty("priceHistoryKeepDays", TradeService.getPriceHistoryKeepDays());
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.hcf0f4f60", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 某物品的成交行情（均价/最低/最高 + 当前在售最低价），供管理台做定价参考。
     *
     * @param itemKey 物品 key（支持短 key）
     * @param days    统计窗口天数；空或非法值走服务端默认 7 天，上限 90 天
     */
    @GET
    @Path("/market/price")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.economy")
    public Response getMarketPrice(@QueryParam("itemKey") String itemKey,
                                   @QueryParam("item") String item,
                                   @QueryParam("days") String daysParam) {
        JsonObject out = new JsonObject();
        String key = (itemKey != null && !itemKey.isBlank()) ? itemKey : item;
        if (key == null || key.isBlank()) {
            out.addProperty("code", 400);
            out.addProperty("message", I18n.t("admin.h4011186c"));
            return Response.ok(gson.toJson(out)).build();
        }
        int days = 0;
        if (daysParam != null && !daysParam.isBlank()) {
            try {
                days = Integer.parseInt(daysParam.trim());
            } catch (NumberFormatException ignored) {
                // 非法天数走服务端默认窗口
            }
        }
        try {
            Map<String, Object> quote = ServiceRegistry.getTradeService().getPriceHistory(key, days);
            boolean ok = Boolean.TRUE.equals(quote.get("success"));
            out.addProperty("code", ok ? 200 : 400);
            out.addProperty("message", String.valueOf(quote.getOrDefault("message", "")));
            JsonObject data = new JsonObject();
            for (Map.Entry<String, Object> e : quote.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Number n) data.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) data.addProperty(e.getKey(), b);
                else data.addProperty(e.getKey(), String.valueOf(v));
            }
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.hba8bd75f", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 批量查询玩家名（playerId → name）；查不到的玩家不入 map，避免逐行查库。 */
    private static Map<Long, String> loadPlayerNames(List<Long> playerIds) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (playerIds == null || playerIds.isEmpty()) return names;
        String placeholders = String.join(",", java.util.Collections.nCopies(playerIds.size(), "?"));
        String sql = "SELECT id, name FROM players WHERE id IN (" + placeholders + ")";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < playerIds.size(); i++) {
                ps.setLong(i + 1, playerIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            // 玩家名查不到不影响挂单列表本身，管理员仍能看到 id 与物品
            GameLogger.getLogger(AdminResource.class).warn("批量查询玩家名失败: " + e.getMessage());
        }
        return names;
    }

    /** 剩余毫秒 → "3天2小时" 这类可读文本。 */
    private static String formatDuration(long ms) {
        if (ms <= 0) return "已到期";
        long totalMinutes = ms / 60000;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) return days + "天" + hours + "小时";
        if (hours > 0) return hours + "小时" + minutes + "分钟";
        return Math.max(1, minutes) + "分钟";
    }

}
