package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.itemService;
import static com.mtxgdn.rest.AdminSupport.playerService;

/**
 * 管理台 · 玩家数据接口（玩家列表、详情、改属性、发奖励、改名、灵根、称号、行为轨迹）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /players/**}
 * 与 {@code /player-traces} 是本项目里被前端用得最频繁的一组管理接口，
 * 单独成类后改玩家相关逻辑不用再在几屏无关代码之间找位置。</p>
 *
 * <p>拆分只按"路径前缀 + 依赖集合"切，不夹带任何行为改动 —— 路径、权限码、
 * 返回结构全部保持原样，前端 {@code /api/admin/players/*}、
 * {@code /api/admin/player-traces} 不受影响。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /players/{id}} 拼出来的地址与拆分前逐字一致
 * （Jersey 的路径拼接规则）。</p>
 *
 * <p>权限码沿用拆分前的归属：只读接口是 {@code admin.status}、写接口是
 * {@code admin.users.manage}、玩家称号相关的三个接口是 {@code admin.titles.manage}、
 * 行为轨迹是 {@code admin.logs.view} —— 拆分时顺手统一成新码会把现网管理员的权限悄悄改掉。</p>
 */
@Path("/admin")
public class AdminPlayersResource {

    @GET
    @Path("/players")
    @Produces(MediaType.APPLICATION_JSON)
    // 只读接口用 admin.status：这是本项目自己定下的划分（"读用 admin.status、
    // 写用 admin.users.manage"，见 docs/PROJECT_STRUCTURE.md 第 9 条）。
    // 之前这里误用了 admin.users.manage，而该权限码不在 ADMIN 的默认权限里
    // （只有 SUPER_ADMIN 持有），等于把版主/管理员看玩家列表的能力一并砍掉了。
    // 真正的写操作（/players/{id}/edit、/spiritual-root 等）仍然要 admin.users.manage。
    @RequirePermission("admin.status")
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
        result.addProperty("message", sb.length() > 0 ? I18n.t("admin.grant.issued", sb) : I18n.t("admin.grant.nothing"));
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
            err.addProperty("message", I18n.t("admin.invalid_root_name", rootName,
                    java.util.Arrays.stream(com.mtxgdn.game.entity.SpiritualRoot.values())
                            .map(r -> r.name())
                            .reduce((a, b) -> a + ", " + b).orElse("")));
            return Response.ok(gson.toJson(err)).build();
        }
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
}
