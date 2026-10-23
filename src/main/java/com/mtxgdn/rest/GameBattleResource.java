package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.actionLog;
import static com.mtxgdn.rest.GameSupport.playerService;
import static com.mtxgdn.rest.GameSupport.getPlayerName;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 战斗、探索、秘境与副本接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：秘境区域列表与进入、野外探索、
 * PVP 挑战、副本列表与进入。这些端点都会产生战斗结算并写行为日志，依赖集合
 * （秘境服务、探索服务、战斗服务）高度重合，属于同一个"打一场"的域。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /secret_realm/areas}、{@code /secret_realm/enter}、{@code /exploration}、
 * {@code /pvp/challenge}、{@code /raid/realms}、{@code /raid/enter}）逐字照搬；
 * 权限码 {@code game.secret_realm} / {@code game.explore} / {@code game.pvp.challenge} /
 * {@code game.secretrealm.enter} 全部保持原样，返回结构未动。</p>
 *
 * <p>{@code getRealmName} 只被副本列表用来把境界 id 翻译成中文名，跟着本域搬进来。</p>
 */
@Path("/game")
public class GameBattleResource {

    private static final SecretRealmService secretRealmService = ServiceRegistry.getSecretRealmService();

    private static final ExplorationService explorationService = ServiceRegistry.getExplorationService();

    private static final CombatService combatService = ServiceRegistry.getCombatService();

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        Object userId = requestContext.getProperty("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        throw new WebApplicationException("未登录", 401);
    }

    @GET
    @Path("/secret_realm/areas")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.secret_realm")
    public Response getSecretRealmAreas() {
        Long userId = getCurrentUserId();
        List<SecretRealm> areas = secretRealmService.getAvailableAreas(userId);

        if (areas.isEmpty()) {
            JsonObject data = new JsonObject();
            data.addProperty("message", I18n.t("game.hde5e9ffe"));
            return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
        }

        JsonArray areasArray = new JsonArray();
        for (SecretRealm area : areas) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", area.getName());
            obj.addProperty("requiredRealm", area.getRequiredRealm());
            obj.addProperty("cooldownSeconds", area.getCooldownMs() / 1000);
            obj.addProperty("description", area.getDescription());
            areasArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("areas", areasArray);
        data.addProperty("count", areasArray.size());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/secret_realm/enter")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.secret_realm")
    public Response enterSecretRealm(String body) {
        Long userId = getCurrentUserId();
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String area = json.has("area") ? json.get("area").getAsString() : "";

        if (area.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        com.mtxgdn.game.entity.SecretRealmResult result = secretRealmService.enterSecretRealm(userId, area);

        actionLog.logSecretRealmEnter(userId, getPlayerName(userId), area, result.isSuccess(), result.getMessage());

        if (!result.isSuccess()) {
            return Response.ok(GameMessage.restError(GameErrorCode.SECRET_REALM_NOT_FOUND.getCode(), result.getMessage()).toString()).build();
        }

        JsonObject data = gson.toJsonTree(result).getAsJsonObject();
        return Response.ok(GameMessage.restOk(result.getMessage(), data).toString()).build();
    }

    @POST
    @Path("/exploration")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.explore")
    public Response doExploration() {
        Long userId = getCurrentUserId();
        com.mtxgdn.game.entity.ExplorationResult expResult = explorationService.explore(userId);

        actionLog.logExploration(userId, getPlayerName(userId),
                expResult.getEventType() != null ? expResult.getEventType() : "未知事件", expResult.getMessage());

        if (!expResult.isSuccess()) {
            return Response.ok(GameMessage.restError(GameErrorCode.EXPLORATION_COOLDOWN.getCode(), expResult.getMessage()).toString()).build();
        }

        JsonObject data = gson.toJsonTree(expResult).getAsJsonObject();
        return Response.ok(GameMessage.restOk(expResult.getMessage(), data).toString()).build();
    }

    @POST
    @Path("/pvp/challenge")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.pvp.challenge")
    public Response pvpChallenge(String body) {
        Long userId = getCurrentUserId();
        int challengerPlayerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long targetPlayerId = json.has("targetPlayerId") ? json.get("targetPlayerId").getAsLong() : 0;

        if (targetPlayerId <= 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        CombatService.CombatResult combatResult = combatService.pvpChallenge(challengerPlayerId, targetPlayerId);

        actionLog.logCombat(userId, getPlayerName(userId), "PVP",
                combatResult.getTargetName(), combatResult.isChallengerWon(), combatResult.getMessage());

        if (!combatResult.isSuccess()) {
            return Response.ok(GameMessage.restError(400, combatResult.getMessage()).toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("winner", combatResult.getWinner());
        data.addProperty("challengerWon", combatResult.isChallengerWon());
        data.addProperty("challengerName", combatResult.getChallengerName());
        data.addProperty("targetName", combatResult.getTargetName());
        data.addProperty("challengerRemainingHp", combatResult.getChallengerRemainingHp());
        data.addProperty("targetRemainingHp", combatResult.getTargetRemainingHp());
        data.addProperty("totalRounds", combatResult.getTotalRounds());
        data.addProperty("expReward", combatResult.getExpReward());
        data.addProperty("goldReward", combatResult.getGoldReward());

        JsonArray logArray = new JsonArray();
        for (String log : combatResult.getBattleLog()) {
            logArray.add(log);
        }
        data.add("battleLog", logArray);

        String message = "战斗结束，" + combatResult.getWinner() + " 获胜！";
        return Response.ok(GameMessage.restOk(message, data).toString()).build();
    }

    // ==================== 副本系统 ====================

    @GET
    @Path("/raid/realms")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.secretrealm.enter")
    public Response getRaidRealms() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        List<SecretRealm> realms = com.mtxgdn.game.secretrealm.SecretRealmRegistry.getRaidRealms(player.getRealm());
        JsonArray arr = new JsonArray();
        for (SecretRealm r : realms) {
            JsonObject o = new JsonObject();
            o.addProperty("fullKey", r.getFullKey());
            o.addProperty("name", r.getName());
            o.addProperty("description", r.getDescription());
            o.addProperty("requiredRealm", r.getRequiredRealm());
            o.addProperty("realmName", getRealmName(r.getRequiredRealm()));
            o.addProperty("cooldownMs", r.getCooldownMs());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("realms", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/raid/enter")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.secretrealm.enter")
    public Response enterRaid(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        JsonObject json = gson.fromJson(body, JsonObject.class);
        String areaName = json.has("areaName") ? json.get("areaName").getAsString() : "";

        if (areaName.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        com.mtxgdn.game.entity.SecretRealmResult result = secretRealmService.enterRaid(player.getId(), areaName);
        JsonObject data = new JsonObject();
        data.addProperty("success", result.isSuccess());
        data.addProperty("message", result.getMessage());
        data.addProperty("area", result.getArea());
        data.addProperty("eventType", result.getEventType());
        data.addProperty("expGained", result.getExpGained());
        data.addProperty("goldGained", result.getGoldGained());
        data.addProperty("spiritStonesGained", result.getSpiritStonesGained());
        data.addProperty("monsterDefeated", result.isMonsterDefeated());
        data.addProperty("monsterName", result.getMonsterName() != null ? result.getMonsterName() : "");
        data.addProperty("itemGained", result.getItemGained() != null ? result.getItemGained() : "");
        data.addProperty("itemQuantity", result.getItemQuantity());

        JsonArray logArr = new JsonArray();
        if (result.getLog() != null) {
            for (String log : result.getLog()) {
                logArr.add(log);
            }
        }
        data.add("log", logArr);

        if (result.isSuccess()) {
            return Response.ok(GameMessage.restOk(result.getMessage(), data).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.getMessage()).toString()).build();
    }

    private String getRealmName(int realmId) {
        switch (realmId) {
            case 0: return "凡人";
            case 1: return "炼气期";
            case 2: return "筑基期";
            case 3: return "金丹期";
            case 4: return "元婴期";
            case 5: return "化神期";
            default: return "未知境界";
        }
    }
}
