package com.mtxgdn.rest;

import com.mtxgdn.game.cultivation.HealResult;
import com.mtxgdn.game.buff.ActiveBuffView;
import com.mtxgdn.game.buff.ActiveBuffsResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.DailyService;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.RateLimiter;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.actionLog;
import static com.mtxgdn.rest.GameSupport.playerService;
import static com.mtxgdn.rest.GameSupport.itemService;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 角色与个人数据接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：角色查询/创建、玩家列表与搜索、
 * 灵根表、疗伤、每日任务、Buff 总览、玩家数据文件导出/恢复。这些端点全部围绕"当前登录
 * 玩家的角色"这一份状态，与战斗、坊市、宗门等域没有共享状态，因此按
 * "路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /player}、{@code /players/search}、{@code /daily/morning_cultivation}、
 * {@code /player/data/export} 等）逐字照搬，拼出来的地址与拆分前完全一致；
 * 每个端点原有的权限码（{@code game.player.info} / {@code game.player.create} /
 * {@code game.daily}）与返回结构也一个字节没动，前端 {@code /api/game/*} 不受影响。</p>
 *
 * <p>跨域共用的静态字段与辅助方法在 {@link GameSupport}；本类只额外持有每日任务服务。</p>
 */
@Path("/game")
public class GamePlayerResource {

    private static final DailyService dailyService = ServiceRegistry.getDailyService();

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        Object userId = requestContext.getProperty("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        throw new WebApplicationException("未登录", 401);
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

    @GET
    @Path("/player")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getPlayerInfo() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            JsonObject result = GameMessage.restOk(I18n.t("game.no_character"), null);
            return Response.ok(result.toString()).build();
        }

        OfflineRewardService.OfflineRewardResult offlineReward =
                ServiceRegistry.getOfflineRewardService().processOfflineRewards(userId);

        JsonObject data = gson.toJsonTree(player).getAsJsonObject();

        RealmConfig next = GameConfigLoader.getNextRealmConfig(player.getRealm(), player.getSubRealm());
        if (next != null) {
            data.addProperty("nextRealmName", next.getFullName());
            data.addProperty("requiredExp", next.getRequiredExp());
            data.addProperty("requiredSpiritStones", next.getRequiredSpiritStones());
        }

        long spiritStones = itemService.getSpiritStoneCount(player.getId());
        data.addProperty("spiritStones", spiritStones);

        if (player.getSpiritualRoot() != null) {
            JsonObject rootObj = new JsonObject();
            rootObj.addProperty("key", player.getSpiritualRoot().name());
            rootObj.addProperty("displayName", player.getSpiritualRoot().getDisplayName());
            rootObj.addProperty("description", player.getSpiritualRoot().getDescription());
            rootObj.addProperty("tier", player.getSpiritualRoot().getTier().getDisplayName());
            rootObj.addProperty("attackBonus", player.getSpiritualRoot().getAttackBonus());
            rootObj.addProperty("hpBonus", player.getSpiritualRoot().getHpBonus());
            rootObj.addProperty("mpBonus", player.getSpiritualRoot().getMpBonus());
            rootObj.addProperty("spiritBonus", player.getSpiritualRoot().getSpiritBonus());
            rootObj.addProperty("defenseBonus", player.getSpiritualRoot().getDefenseBonus());
            rootObj.addProperty("speedBonus", player.getSpiritualRoot().getSpeedBonus());
            rootObj.addProperty("effectName", player.getSpiritualRoot().getEffect().name());
            rootObj.addProperty("effectValue", player.getSpiritualRoot().getEffectValue());
            data.add("spiritualRoot", rootObj);
        }

        if (offlineReward.hasReward) {
            JsonObject offlineJson = new JsonObject();
            offlineJson.addProperty("offlineSeconds", offlineReward.offlineSeconds);
            offlineJson.addProperty("offlineMinutes", offlineReward.offlineMinutes);
            offlineJson.addProperty("offlineHours", offlineReward.offlineHours);
            offlineJson.addProperty("hpRecovered", offlineReward.hpRecovered);
            offlineJson.addProperty("mpRecovered", offlineReward.mpRecovered);
            if (offlineReward.wasCultivating) {
                offlineJson.addProperty("expGained", offlineReward.expGained);
                offlineJson.addProperty("rawExpGained", offlineReward.rawExpGained);
                if (offlineReward.heartDemonTriggered) {
                    offlineJson.addProperty("heartDemonTriggered", true);
                    offlineJson.addProperty("heartDemonSeverity", offlineReward.heartDemonSeverity);
                    offlineJson.addProperty("heartDemonNarrative", offlineReward.heartDemonNarrative);
                    offlineJson.addProperty("heartDemonExpLost", offlineReward.heartDemonExpLost);
                }
            }
            data.add("offlineReward", offlineJson);
        }

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/player/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.create")
    public Response createPlayer(String body) {
        System.out.println("[Game] >>> POST /player/create");
        Long userId = getCurrentUserId();

        if (playerService.existsByUserId(userId)) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_ALREADY_EXISTS).toString()).build();
        }

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String name = json.has("name") ? json.get("name").getAsString() : "无名修士";

        // V10：在进入 service 前做显式校验，返回可读的 400 而不是让
        // IllegalArgumentException/SQLException 冒泡成 500。
        if (name == null || name.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        if (name.trim().length() > com.mtxgdn.game.service.PlayerService.MAX_PLAYER_NAME_LENGTH) {
            return Response.ok(GameMessage.restError(400,
                    I18n.t("game.player_name_too_long",
                            com.mtxgdn.game.service.PlayerService.MAX_PLAYER_NAME_LENGTH)).toString()).build();
        }

        PlayerInfo player;
        try {
            player = playerService.createPlayer(userId, name);
        } catch (IllegalArgumentException e) {
            return Response.ok(GameMessage.restError(400, e.getMessage()).toString()).build();
        }

        actionLog.logCreatePlayer(userId, player.getName());

        JsonObject data = gson.toJsonTree(player).getAsJsonObject();
        JsonArray tutorialSteps = new JsonArray();
        tutorialSteps.add("闭关修炼: POST /api/game/cultivate/start");
        tutorialSteps.add("停止修炼: POST /api/game/cultivate/stop");
        tutorialSteps.add("游历探索: POST /api/game/exploration");
        tutorialSteps.add("查看秘境: GET /api/game/secret_realm/areas");
        tutorialSteps.add("进入秘境: POST /api/game/secret_realm/enter");
        tutorialSteps.add("境界突破: POST /api/game/realm/breakthrough");
        data.add("tutorial", tutorialSteps);
        return Response.ok(GameMessage.restOk(I18n.t("game.character_created"), data).toString()).build();
    }

    @POST
    @Path("/heal")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response healPlayer() {
        checkActionRateLimit("heal");
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        HealResult result = playerService.healPlayer(player.getId());
        JsonObject data = gson.toJsonTree(result).getAsJsonObject();
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), data).toString()).build();
        } else {
            return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
        }
    }

    @GET
    @Path("/players")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getAllPlayers(
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        List<PlayerInfo> players = playerService.getAllPlayers(limit, offset);
        int total = playerService.getPlayerCount();

        JsonArray playersArray = new JsonArray();
        for (PlayerInfo player : players) {
            JsonObject obj = gson.toJsonTree(player).getAsJsonObject();
            obj.remove("subRealm");
            obj.remove("cultivationProgress");
            obj.remove("lastSecretRealmTime");
            obj.remove("lastExplorationTime");
            obj.remove("createdAt");
            obj.remove("updatedAt");
            playersArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("players", playersArray);
        data.addProperty("count", playersArray.size());
        data.addProperty("total", total);
        data.addProperty("limit", limit);
        data.addProperty("offset", offset);

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/players/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchPlayers(
            @QueryParam("name") @DefaultValue("") String name,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        if (name.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING.getCode(),
                    I18n.t("error.param_missing_search_name")).toString()).build();
        }

        List<PlayerInfo> players = playerService.searchPlayersByName(name.trim(), limit, offset);

        JsonArray playersArray = new JsonArray();
        for (PlayerInfo player : players) {
            JsonObject obj = gson.toJsonTree(player).getAsJsonObject();
            obj.remove("subRealm");
            obj.remove("cultivationProgress");
            obj.remove("lastSecretRealmTime");
            obj.remove("lastExplorationTime");
            obj.remove("createdAt");
            obj.remove("updatedAt");
            playersArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("players", playersArray);
        data.addProperty("count", playersArray.size());
        data.addProperty("keyword", name.trim());

        return Response.ok(GameMessage.restOk(I18n.t("common.search_ok"), data).toString()).build();
    }

    @GET
    @Path("/spiritual_roots")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getSpiritualRoots() {
        JsonArray arr = new JsonArray();
        for (SpiritualRoot root : SpiritualRoot.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("key", root.name());
            o.addProperty("displayName", root.getDisplayName());
            o.addProperty("description", root.getDescription());
            o.addProperty("tier", root.getTier().getDisplayName());
            o.addProperty("attackBonus", root.getAttackBonus());
            o.addProperty("hpBonus", root.getHpBonus());
            o.addProperty("mpBonus", root.getMpBonus());
            o.addProperty("spiritBonus", root.getSpiritBonus());
            o.addProperty("defenseBonus", root.getDefenseBonus());
            o.addProperty("speedBonus", root.getSpeedBonus());
            o.addProperty("effectName", root.getEffect().name());
            o.addProperty("effectValue", root.getEffectValue());
            arr.add(o);
        }
        JsonObject rootData = new JsonObject();
        rootData.add("roots", arr);
        return Response.ok(GameMessage.restOk(I18n.t("root.list_ok"), rootData).toString()).build();
    }

    @POST
    @Path("/daily/morning_cultivation")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.daily")
    public Response morningCultivation() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var result = dailyService.doMorningCultivation(playerId);
        return Response.ok(GameMessage.restOk(result.message(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @GET
    @Path("/daily")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.daily")
    public Response getDailyInfo() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var result = dailyService.getDailyInfo(playerId);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    // ==================== Buff 系统 ====================

    @GET
    @Path("/buff")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getPlayerBuffs() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        BuffService buffService = ServiceRegistry.getBuffService();
        ActiveBuffsResult result = buffService.getActiveBuffs(playerId);

        JsonObject data = new JsonObject();
        List<ActiveBuffView> buffs = result.buffs();
        int count = result.count();

        JsonArray buffArray = new JsonArray();
        for (ActiveBuffView buff : buffs) {
            JsonObject o = new JsonObject();
            o.addProperty("id", buff.id());
            o.addProperty("attackBonus", buff.attackBonus());
            o.addProperty("defenseBonus", buff.defenseBonus());
            o.addProperty("speedBonus", buff.speedBonus());
            o.addProperty("spiritBonus", buff.spiritBonus());
            o.addProperty("remainingSeconds", buff.remainingSeconds());
            buffArray.add(o);
        }

        int totalAtk = buffService.getTotalAttackBonus(playerId);
        int totalDef = buffService.getTotalDefenseBonus(playerId);
        int totalSpd = buffService.getTotalSpeedBonus(playerId);
        int totalSpi = buffService.getTotalSpiritBonus(playerId);

        data.add("buffs", buffArray);
        data.addProperty("count", count);
        data.addProperty("totalAttackBonus", totalAtk);
        data.addProperty("totalDefenseBonus", totalDef);
        data.addProperty("totalSpeedBonus", totalSpd);
        data.addProperty("totalSpiritBonus", totalSpi);

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    // ==================== 玩家数据文件（一用户一个文件）====================

    @POST
    @Path("/player/data/export")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response exportPlayerData() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        com.mtxgdn.playerdata.PlayerDataService dataService = new com.mtxgdn.playerdata.PlayerDataService();
        Map<String, Object> result = dataService.exportPlayer(player.getId());
        if (!Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
        }
        JsonObject data = new JsonObject();
        data.addProperty("playerId", player.getId());
        data.addProperty("filePath", (String) result.get("filePath"));
        data.addProperty("exportedAt", ((Number) result.get("exportedAt")).longValue());
        data.addProperty("message", (String) result.get("message"));
        return Response.ok(GameMessage.restOk((String) result.get("message"), data).toString()).build();
    }

    @POST
    @Path("/player/data/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response restorePlayerData(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        if (!com.mtxgdn.playerdata.PlayerDataService.isSelfRestoreEnabled()) {
            return Response.ok(GameMessage.restError(403, I18n.t("player_data.self_restore_disabled")).toString()).build();
        }
        com.mtxgdn.playerdata.PlayerDataService dataService = new com.mtxgdn.playerdata.PlayerDataService();
        Map<String, Object> result = dataService.restorePlayer(player.getId(), body);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
        }
        return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
    }
}
