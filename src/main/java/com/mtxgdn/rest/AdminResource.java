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
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.GameLogger;

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
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String username = req.has("username") ? req.get("username").getAsString() : "";
        String password = req.has("password") ? req.get("password").getAsString() : "";

        if (!AdminAuthFilter.validateCredentials(username, password)) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 401);
            err.addProperty("message", "用户名或密码错误");
            return Response.status(401).entity(gson.toJson(err)).build();
        }

        String token = AdminAuthFilter.generateAdminToken(username);
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("token", token);
        result.addProperty("username", username);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
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
    public Response shutdown() {
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            System.exit(0);
        }).start();

        JsonObject result = new JsonObject();
        result.addProperty("message", "服务器正在关闭...");
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/roles")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRoles() {
        JsonArray roles = new JsonArray();
        for (Map.Entry<String, Integer> entry : PermissionService.getRoleHierarchy().entrySet()) {
            JsonObject role = new JsonObject();
            role.addProperty("name", entry.getKey());
            role.addProperty("level", entry.getValue());

            JsonArray perms = new JsonArray();
            for (PermissionCode pc : PermissionService.getRoleDefaultPermissions(entry.getKey())) {
                JsonObject perm = new JsonObject();
                perm.addProperty("code", pc.getCode());
                perm.addProperty("name", pc.getName());
                perm.addProperty("category", pc.getCategory());
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
    public Response getPermissions() {
        JsonArray perms = new JsonArray();
        for (PermissionCode pc : PermissionCode.values()) {
            JsonObject perm = new JsonObject();
            perm.addProperty("code", pc.getCode());
            perm.addProperty("name", pc.getName());
            perm.addProperty("category", pc.getCategory());
            perms.add(perm);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("permissions", perms);
        return Response.ok(gson.toJson(result)).build();
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
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

    @POST
    @Path("/database/clear_players")
    @Produces(MediaType.APPLICATION_JSON)
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

    @POST
    @Path("/players/{id}/spiritual-root")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
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

    // ========== 兑换码管理 API ==========

    private static final com.mtxgdn.game.service.RedeemCodeService redeemCodeService =
            new com.mtxgdn.game.service.RedeemCodeService();

    @GET
    @Path("/redeem-codes")
    @Produces(MediaType.APPLICATION_JSON)
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
