package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.game.title.TitleRegistry;
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

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Path("/admin")
public class AdminResource {

    private static final Gson gson = new Gson();
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();
    private static final ItemService itemService = ServiceRegistry.getItemService();

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(String body) {
        // 管理后台登录频率限制：每分钟最多10次
        if (!RateLimiter.allow("admin_login_global", 10, 60)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 429);
            err.addProperty("message", "登录尝试过于频繁，请稍后再试");
            return Response.status(429).entity(gson.toJson(err)).build();
        }
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";

        if (username.isBlank() || password.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", "用户名和密码不能为空");
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        // 优先使用 application.yml 中配置的管理员账号（与玩家账号完全分离）
        if (AdminAuthFilter.validateCredentials(username, password)) {
            String token = AdminAuthFilter.generateAdminToken(username);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("token", token);
            result.addProperty("username", username);
            result.addProperty("userId", 0);
            result.addProperty("highestRole", "admin");
            result.add("permissions", new JsonArray());
            return Response.ok(gson.toJson(result)).build();
        }

        // 非 yml 管理员，尝试玩家账号（需有 admin.login 权限）
        UserService userService = new UserService();
        User user = userService.authenticate(username, password);

        if (user == null) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", "用户名或密码错误");
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.hasPermission(user.getId(), PermissionCode.ADMIN_LOGIN.getCode())) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 403);
            err.addProperty("message", "无管理后台权限，请联系超级管理员分配角色");
            return Response.status(403).entity(gson.toJson(err)).build();
        }

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());
        String highestRole = PermissionService.getHighestRole(user.getId());
        JsonArray perms = new JsonArray();
        for (String code : PermissionService.getAllUserPermissionCodes(user.getId())) {
            perms.add(code);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("token", token);
        result.addProperty("username", user.getUsername());
        result.addProperty("userId", user.getId());
        result.addProperty("highestRole", highestRole != null ? highestRole : "");
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
        List<Map<String, Object>> statuses = com.mtxgdn.plugin.PluginManager.getInstance().getPluginStatuses();
        JsonArray arr = new JsonArray();
        for (Map<String, Object> s : statuses) {
            JsonObject obj = new JsonObject();
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
    public Response enablePlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().enablePlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? "插件已启用: " + name : "启用失败: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/disable/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response disablePlugin(@PathParam("name") String name) {
        boolean ok = com.mtxgdn.plugin.PluginManager.getInstance().disablePlugin(name);
        JsonObject result = new JsonObject();
        result.addProperty("code", ok ? 200 : 400);
        result.addProperty("message", ok ? "插件已停用: " + name : "停用失败: " + name);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/plugins/load/{jarName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.plugins.manage")
    public Response loadPlugin(@PathParam("jarName") String jarName) {
        File pluginsDir = new File("plugins");
        File jarFile = new File(pluginsDir, jarName);
        if (!jarFile.exists()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 404);
            err.addProperty("message", "插件文件不存在: " + jarName);
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
        result.addProperty("message", "文件监听已启动");
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
        result.addProperty("message", "文件监听已停止");
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
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            if (Main.oneBotWebSocketServer != null) Main.oneBotWebSocketServer.shutdown();
            if (Main.screenshotBot != null) Main.screenshotBot.stop();
            if (Main.minecraftAdapter != null) Main.minecraftAdapter.stop();
            if (Main.gameWebSocketApp != null) {
                try { Main.gameWebSocketApp.shutdownGracefully(); } catch (Exception ignored) {}
            }
            if (Main.oneBotServer != null) Main.oneBotServer.shutdownNow();
            if (Main.mainServer != null) Main.mainServer.shutdownNow();
            System.exit(0);
        }).start();

        JsonObject result = new JsonObject();
        result.addProperty("message", "服务器正在关闭...");
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
            err.addProperty("message", "权限组名称不能为空");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.createGroup(name, displayName, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "权限组创建成功");
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
            result.addProperty("message", "权限组更新成功");
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
            result.addProperty("message", "权限组已删除");
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
            err.addProperty("message", "权限码不能为空");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.addGroupPermission(groupName, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "权限已添加到权限组");
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
            result.addProperty("message", "权限已从权限组移除");
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
            result.addProperty("message", "权限组权限已更新");
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
            err.addProperty("message", "无效的角色名");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        PermissionService.assignRole(userId, roleName);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", "角色分配成功");
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
            err.addProperty("message", "无效的角色名");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        int currentLevel = PermissionService.getHighestRoleLevel(userId);
        Integer roleLevel = PermissionService.getRoleHierarchy().get(roleName);
        if (roleLevel != null && currentLevel <= roleLevel) {
            PermissionService.removeRole(userId, roleName);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", "角色移除成功");
        return Response.ok(gson.toJson(result)).build();
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
            err.addProperty("message", "权限码不能为空");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        if (!PermissionService.isValidPermissionCode(permissionCode)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "无效的权限码: " + permissionCode);
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        try {
            PermissionService.assignPermission(userId, permissionCode);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "权限分配成功");
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "分配失败: " + e.getMessage());
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
            result.addProperty("message", "权限移除成功");
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "移除失败: " + e.getMessage());
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
        result.addProperty("message", "玩家数据已清除");
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
        result.addProperty("message", "全部数据已重置，默认数据已重新初始化");
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
            err.addProperty("message", "玩家不存在");
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("id", p.getId());
        obj.addProperty("userId", p.getUserId());
        obj.addProperty("name", p.getName());
        obj.addProperty("level", p.getLevel());
        obj.addProperty("experience", p.getExperience());
        obj.addProperty("realm", p.getRealm());
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
            err.addProperty("message", "玩家不存在");
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
            err.addProperty("message", "玩家不存在");
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

        playerService.updatePlayer(playerId, p);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", "编辑成功");
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
            err.addProperty("message", "玩家不存在");
            return Response.ok(gson.toJson(err)).build();
        }

        boolean success = playerService.deletePlayer(playerId);
        if (success) {
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "删除成功");
            return Response.ok(gson.toJson(result)).build();
        } else {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "删除失败");
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
            err.addProperty("message", "玩家不存在");
            return Response.ok(gson.toJson(err)).build();
        }

        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String rootName = req.has("spiritualRoot") ? req.get("spiritualRoot").getAsString() : null;
        if (rootName == null || rootName.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "请提供灵根名称");
            return Response.ok(gson.toJson(err)).build();
        }

        try {
            com.mtxgdn.game.entity.SpiritualRoot root =
                    com.mtxgdn.game.entity.SpiritualRoot.valueOf(rootName.toUpperCase());
            playerService.updateSpiritualRoot(playerId, root);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "灵根已修改为 " + root.getDisplayName() + "（" + root.getTier().getDisplayName() + "）");
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
    @RequirePermission("admin.database.reset_all")
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
            err.addProperty("message", "获取表列表失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
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
            err.addProperty("message", "查询失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response getTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            Map<String, Object> row = DatabaseManager.getRowById(tableName, id);
            if (row == null) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 404);
                err.addProperty("message", "记录不存在");
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
            err.addProperty("message", "查询失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
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

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", "新增成功");
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
            err.addProperty("message", "新增失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
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

            JsonObject result = new JsonObject();
            if (updated > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", "更新成功");
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", "记录不存在或未变更");
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
            err.addProperty("message", "更新失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response deleteTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            int deleted = DatabaseManager.deleteRow(tableName, id);

            JsonObject result = new JsonObject();
            if (deleted > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", "删除成功");
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", "记录不存在");
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
            err.addProperty("message", "删除失败: " + e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ========== 数据库备份与导入 ==========

    @GET
    @Path("/backup")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response downloadBackup() {
        try {
            String json = DatabaseManager.exportAllData();
            return Response.ok(json, MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=\"xiuxian_backup_" +
                            java.time.LocalDate.now() + ".json\"")
                    .build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "备份失败: " + e.getMessage());
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
            result.addProperty("message", "数据导入成功");

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
            err.addProperty("message", "导入失败: " + e.getMessage());
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
                err.addProperty("message", "兑换码不能为空");
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
            result.addProperty("message", "兑换码创建成功");
            result.add("codeInfo", toRedeemCodeJson(rc));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "创建失败: " + e.getMessage());
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
                err.addProperty("message", "已兑换或已过期的兑换码不允许编辑，只能删除");
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
            result.addProperty("message", "兑换码更新成功");
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "更新失败: " + e.getMessage());
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
            result.addProperty("message", "兑换码已删除");
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "删除失败: " + e.getMessage());
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
            err.addProperty("message", "qqNumber 和 userId 必须填写其中一个");
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (hasQq && hasUid) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", "qqNumber 和 userId 只能二选一");
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
            result.addProperty("message", "已从黑名单移除");
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
            result.addProperty("message", "已从黑名单移除");
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
            err.addProperty("message", "禁言天数必须在1-30之间");
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        configService.setMuteDuration(groupId, days);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", "禁言天数已设置为 " + days + " 天");
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
            result.addProperty("message", "群组配置已删除");
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
            err.addProperty("message", "请提供称号键(titleKey)");
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
            result.addProperty("message", "新人奖励配置已保存");
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", "保存失败: " + e.getMessage());
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
}
