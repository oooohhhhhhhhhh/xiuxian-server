package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.adapter.AdapterConfig;
import com.mtxgdn.adapter.AdapterConfigManager;
import com.mtxgdn.adapter.AdapterManager;
import com.mtxgdn.adapter.AdaptersConfig;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.StatsCollector;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.playerService;

/**
 * 管理台 · 系统运维接口（状态、日志、统计、公告、关机、适配器、OneBot 群配置、授权）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：这些端点既不碰玩家数据、
 * 也不碰库表结构，而是围绕进程本身（{@code Main} 的在线人数与启动时间、
 * 日志环形缓冲、{@code StatsCollector}、{@code AdapterManager}、OneBot 群配置），
 * 因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码、返回结构全部保持原样，
 * 前端 {@code /api/admin/status|logs|stats/*|system/info|announce|authority|
 * onebot/*|adapters} 不受影响。{@code /adapters} 的 GET / PUT / POST 三个方法
 * 与 {@code consumes}/{@code produces} 也逐字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /stats/messages} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminSystemResource {

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

    @GET
    @Path("/logs")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.logs.view")
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
        result.addProperty("message", I18n.t("admin.server_is_shutting_down"));
        return Response.ok(gson.toJson(result)).build();
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
            err.addProperty("message", I18n.t("admin.announcement_content_cannot_be_empty"));
            return Response.ok(gson.toJson(err)).build();
        }

        if (Main.gameWebSocketApp != null) {
            JsonObject data = new JsonObject();
            data.addProperty("announcement", text);
            Main.gameWebSocketApp.broadcast(new com.mtxgdn.common.GameMessage(0, "announcement", 200, "公告", data));
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.announcement_sent"));
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
            err.addProperty("message", I18n.t("admin.qq_number_and_permission_level"));
            return Response.ok(gson.toJson(err)).build();
        }

        long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.invalid_qq_number"));
            return Response.ok(gson.toJson(err)).build();
        }

        try {
            PermissionService.assignRole(userId, level);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.permission_set_successfully"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.set_failed", e.getMessage()));
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
        result.addProperty("message", enabled ? I18n.t("admin.auto_mute.enabled") : I18n.t("admin.auto_mute.disabled"));
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
            err.addProperty("message", I18n.t("admin.mute_duration_must_be_between"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        com.mtxgdn.onebot.OneBotGroupConfigService configService = new com.mtxgdn.onebot.OneBotGroupConfigService();
        configService.setMuteDuration(groupId, days);

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.mute_duration_set_to_day", days));
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
            result.addProperty("message", I18n.t("admin.group_configuration_deleted"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
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
                err.addProperty("message", I18n.t("admin.invalid_adapter_configuration"));
                return Response.ok(gson.toJson(err)).build();
            }

            // 关键：在更新前先捕获旧配置，否则 reloadAll 无法对比差异
            AdaptersConfig oldConfig = AdapterConfigManager.getConfig().copy();
            AdapterConfigManager.updateConfig(newConfig);
            AdapterManager.reloadAll(oldConfig);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.adapter_configuration_updated_and_reloaded"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.update_failed", e.getMessage()));
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
            result.addProperty("message", I18n.t("admin.adapter_configuration_reloaded"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.reload_failed", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }
}
