package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.service.TitleService;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.playerService;

/**
 * 玩家侧 · 称号接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：称号总表、我的称号、
 * 当前佩戴称号、佩戴/卸下。称号数据走注册表（{@code TitleRegistry}）而不是普通表，
 * 与其它域的依赖集合完全不同，因此整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /title/all}、{@code /title/my}、{@code /title/active}、{@code /title/equip}、
 * {@code /title/unequip}）逐字照搬；权限码 {@code game.title.view} /
 * {@code game.title.equip} 保持原样，返回结构未动。</p>
 *
 * <p>{@code titleService()} 与 {@code toTitleJson} 只被本域用到，跟着搬进来。</p>
 */
@Path("/game")
public class GameTitleResource {

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        Object userId = requestContext.getProperty("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        throw new WebApplicationException("未登录", 401);
    }

    // ==================== 称号系统 ====================

    private TitleService titleService() { return ServiceRegistry.getTitleService(); }

    @GET
    @Path("/title/all")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.title.view")
    public Response getAllTitles() {
        TitleRegistry.init();
        JsonArray arr = new JsonArray();
        for (Title t : TitleRegistry.getAll()) {
            arr.add(toTitleJson(t));
        }
        JsonObject data = new JsonObject();
        data.add("titles", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/title/my")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.title.view")
    public Response getMyTitles() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        var titles = titleService().getPlayerTitles(player.getId());
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/title/active")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.title.view")
    public Response getActiveTitle() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        Title title = titleService().getEquippedTitle(player.getId());
        JsonObject data = new JsonObject();
        if (title != null) {
            data.add("title", toTitleJson(title));
        }
        data.addProperty("hasTitle", title != null);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/title/equip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.title.equip")
    public Response equipTitle(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        String titleKey = req.has("titleKey") ? req.get("titleKey").getAsString() : "";
        var result = titleService().equipTitle(player.getId(), titleKey);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/title/unequip")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.title.equip")
    public Response unequipTitle() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        var result = titleService().unequipTitle(player.getId());
        return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    private JsonObject toTitleJson(Title t) {
        JsonObject o = new JsonObject();
        o.addProperty("key", t.getKey());
        o.addProperty("name", t.getName());
        o.addProperty("description", t.getDescription());
        o.addProperty("rarity", t.getRarity().name());
        o.addProperty("rarityLabel", t.getRarityLabel());
        o.addProperty("requiredRealm", t.getRequiredRealm());
        o.addProperty("attackBonus", t.getAttackBonus());
        o.addProperty("defenseBonus", t.getDefenseBonus());
        o.addProperty("hpBonus", t.getHpBonus());
        o.addProperty("mpBonus", t.getMpBonus());
        o.addProperty("speedBonus", t.getSpeedBonus());
        o.addProperty("spiritBonus", t.getSpiritBonus());
        o.addProperty("cultivationSpeedBonus", t.getCultivationSpeedBonus());
        o.addProperty("expBonus", t.getExpBonus());
        o.addProperty("dropRateBonus", t.getDropRateBonus());
        return o;
    }
}
