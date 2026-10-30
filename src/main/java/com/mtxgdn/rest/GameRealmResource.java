package com.mtxgdn.rest;

import com.mtxgdn.game.cultivation.BeginCultivationResult;
import com.mtxgdn.game.cultivation.FinishCultivationResult;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.RateLimiter;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.actionLog;
import static com.mtxgdn.rest.GameSupport.playerService;
import static com.mtxgdn.rest.GameSupport.getPlayerName;

/**
 * 玩家侧 · 境界与修炼接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：突破预览/突破、境界巩固、
 * 境界配置表、开始/结束打坐修炼。这几个端点共享同一批依赖（境界服务、心魔服务、
 * 动作限流），彼此之间的调用关系也比其它域更紧（突破与巩固都要读同一份境界配置），
 * 因此整块搬出来单独成类。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /realm/breakthrough}、{@code /realm/breakthrough/preview}、
 * {@code /realm/consolidate}、{@code /realm/config}、{@code /cultivate/start}、
 * {@code /cultivate/stop}）逐字照搬；权限码 {@code game.realm.breakthrough} 与
 * {@code game.realm.config}、{@code game.cultivate} 保持原样，返回结构未动。</p>
 *
 * <p>修炼动作限流（{@code checkActionRateLimit}）在拆分前是全类共用的一份，本类与
 * {@link GamePlayerResource}、{@link GameSocialResource} 都要用；它需要请求上下文，
 * 无法做成静态方法，因此各处保留一份内容逐字相同的副本。</p>
 */
@Path("/game")
public class GameRealmResource {

    private static final RealmService realmService = ServiceRegistry.getRealmService();

    private static final HeartDemonService heartDemonService = ServiceRegistry.getHeartDemonService();

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
    }

    private void checkActionRateLimit(String action) {
        Long userId = getCurrentUserId();
        String key = "rst:" + userId + ":" + action;
        int limit = switch (action) {
            case "chat" -> 10;
            case "breakthrough", "cultivate", "consolidate" -> 5;
            case "explore", "secret" -> 6;
            case "heal", "friend" -> 15;
            default -> 30;
        };
        if (!RateLimiter.allow(key, limit, 60)) {
            throw new WebApplicationException("操作太频繁，请稍后再试", 429);
        }
    }

    @POST
    @Path("/realm/breakthrough")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.realm.breakthrough")
    public Response breakthrough() {
        checkActionRateLimit("breakthrough");
        Long userId = getCurrentUserId();
        RealmBreakthroughResult btResult = realmService.tryBreakthrough(userId);

        actionLog.logBreakthrough(userId, getPlayerName(userId), btResult.isSuccess(), btResult.getMessage());

        JsonObject data = gson.toJsonTree(btResult).getAsJsonObject();

        if (btResult.isSuccess()) {
            return Response.ok(GameMessage.restOk(btResult.getMessage(), data).toString()).build();
        } else {
            return Response.ok(GameMessage.restOk(btResult.getMessage(), data).toString()).build();
        }
    }

    @GET
    @Path("/realm/breakthrough/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.realm.breakthrough")
    public Response previewBreakthrough() {
        Long userId = getCurrentUserId();
        RealmBreakthroughResult btResult = realmService.previewBreakthrough(userId);
        JsonObject data = gson.toJsonTree(btResult).getAsJsonObject();
        return Response.ok(GameMessage.restOk(btResult.getMessage(), data).toString()).build();
    }

    @POST
    @Path("/realm/consolidate")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.realm.breakthrough")
    public Response consolidate() {
        checkActionRateLimit("consolidate");
        Long userId = getCurrentUserId();
        RealmBreakthroughResult result = realmService.consolidate(userId);
        JsonObject data = gson.toJsonTree(result).getAsJsonObject();
        return Response.ok(GameMessage.restOk(result.getMessage(), data).toString()).build();
    }

    @GET
    @Path("/realm/config")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.realm.config")
    public Response getRealmConfig() {
        JsonObject data = gson.toJsonTree(GameConfigLoader.getRealmConfigs()).getAsJsonObject();
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/cultivate/start")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.cultivate")
    public Response startCultivation() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        BeginCultivationResult result = playerService.beginCultivation(player.getId());
        if (!result.success()) {
            return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
        }

        actionLog.logCultivateStart(userId, player.getName(), player.getRealm());

        JsonObject data = new JsonObject();
        data.addProperty("cultivating", true);
        data.addProperty("cultivationPerSecond", result.cultivationPerSecond());
        data.addProperty("startTime", result.startTime());
        data.addProperty("season", result.season());
        data.addProperty("seasonMultiplier", result.seasonMultiplier());
        data.addProperty("locationId", result.locationId());
        data.addProperty("locationName", result.locationName());
        data.addProperty("locationMultiplier", result.locationMultiplier());

        return Response.ok(GameMessage.restOk(
                I18n.t("cultivate.start_full",
                        result.locationName(),
                        Math.round(result.seasonMultiplier() * 100 - 100)),
                data).toString()).build();
    }

    @POST
    @Path("/cultivate/stop")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.cultivate")
    public Response stopCultivation() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        FinishCultivationResult result = playerService.finishCultivation(player.getId());
        if (!result.success()) {
            return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
        }

        int elapsedSeconds = result.elapsedSeconds().intValue();
        long expGained = result.rawExpGained();

        HeartDemonService.HeartDemonResult hdResult = heartDemonService.processCultivation(
                player.getId(), expGained, elapsedSeconds);
        long netExp = hdResult.netExpChange;
        if (netExp != 0) {
            playerService.addExperience(player.getId(), netExp);
        }

        actionLog.logCultivateStop(userId, player.getName(), netExp, elapsedSeconds);

        PlayerInfo updated = playerService.getPlayerByUserId(userId);

        JsonObject data = gson.toJsonTree(updated).getAsJsonObject();
        data.addProperty("experienceGained", netExp);
        data.addProperty("elapsedSeconds", elapsedSeconds);
        data.addProperty("season", result.season());
        if (hdResult.triggered) {
            data.addProperty("heartDemonTriggered", true);
            data.addProperty("heartDemonSeverity", hdResult.severity);
            data.addProperty("heartDemonNarrative", hdResult.narrative);
            data.addProperty("heartDemonExpLost", hdResult.expLost);
        }

        String message;
        if (hdResult.triggered) {
            message = "修炼结束，" + hdResult.narrative;
        } else {
            message = "停止修炼，获得了 " + netExp + " 经验";
        }
        return Response.ok(GameMessage.restOk(message, data).toString()).build();
    }
}
