package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.service.FarmService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 种田与全局季节接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：田块总览、播种、浇水、
 * 施肥、收获、清地、扩建、一键浇水/施肥/收获、驱虫，以及全局季节信息。
 * 季节直接决定种田的产出倍率，两者共用同一批依赖（玩家 id 解析 + 季节/田地状态），
 * 因此季节端点放在本类而不是别处。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /farm/plots}、{@code /farm/plant}、{@code /farm/water-all}、
 * {@code /farm/pesticide}、{@code /season} 等）逐字照搬；11 个种田端点的权限码
 * {@code game.player.info} 与季节端点的"无权限码"状态都保持原样，返回结构未动。</p>
 */
@Path("/game")
public class GameFarmResource {

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        Object userId = requestContext.getProperty("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        throw new WebApplicationException("未登录", 401);
    }

    // ==================== 种田系统 ====================

    @GET
    @Path("/farm/plots")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getFarmPlots() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        FarmService farmService = ServiceRegistry.getFarmService();
        List<FarmPlot> plots = farmService.getPlots(playerId);

        JsonArray plotArray = new JsonArray();
        for (FarmPlot plot : plots) {
            JsonObject o = new JsonObject();
            o.addProperty("id", plot.getId());
            o.addProperty("plotIndex", plot.getPlotIndex());
            o.addProperty("state", plot.getState().name());
            o.addProperty("stateDisplay", plot.getState().getDisplayName());
            o.addProperty("seedKey", plot.getSeedKey());
            o.addProperty("cropKey", plot.getCropKey());
            o.addProperty("growthStage", plot.getGrowthStage());
            o.addProperty("waterLevel", plot.getWaterLevel());
            o.addProperty("fertilizerLevel", plot.getFertilizerLevel());
            o.addProperty("pestState", plot.getPestState() != null ? plot.getPestState().name() : "CLEAN");
            o.addProperty("pestStateDisplay", plot.getPestState() != null ? plot.getPestState().getDisplayName() : "健康");
            o.addProperty("rootBonus", plot.getRootBonus());
            o.addProperty("seasonModifier", plot.getSeasonModifier());
            if (plot.getState() != FarmPlot.PlotState.EMPTY && plot.getHarvestTime() > 0) {
                long remaining = Math.max(0, plot.getHarvestTime() - System.currentTimeMillis());
                o.addProperty("harvestRemainingMs", remaining);
            }
            plotArray.add(o);
        }

        Season currentSeason = com.mtxgdn.game.service.SeasonService.getCurrentSeason();
        JsonObject data = new JsonObject();
        data.add("plots", plotArray);
        data.addProperty("count", plotArray.size());
        data.addProperty("currentSeason", currentSeason.name());
        data.addProperty("currentSeasonDisplay", currentSeason.getDisplayName());
        data.addProperty("currentSeasonDesc", currentSeason.getDescription());
        data.addProperty("seasonEffect", com.mtxgdn.game.service.SeasonService.getSeasonEffectDescription());
        data.addProperty("seasonRemainingMs", com.mtxgdn.game.service.SeasonService.getSeasonRemainingMs());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/farm/plant")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmPlant(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;
        String seedKey = json.has("seedKey") ? json.get("seedKey").getAsString() : "";

        if (plotIndex < 0 || seedKey.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.plant(playerId, plotIndex, seedKey);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/water")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmWater(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;

        if (plotIndex < 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.water(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/fertilize")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmFertilize(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;

        if (plotIndex < 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.fertilize(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/harvest")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmHarvest(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;

        if (plotIndex < 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.harvest(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/clear")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmClear(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;

        if (plotIndex < 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.clearPlot(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/expand")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmExpand() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.expandPlot(playerId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/water-all")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmWaterAll() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.waterAll(playerId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/fertilize-all")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmFertilizeAll(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        String fertilizerKey = json.has("fertilizerKey") ? json.get("fertilizerKey").getAsString() : "mtxgdn:low_grade_fertilizer";

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.fertilizeAll(playerId, fertilizerKey);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/harvest-all")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmHarvestAll() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.harvestAll(playerId);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/farm/pesticide")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response farmPesticide(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = gson.fromJson(body, JsonObject.class);
        int plotIndex = json.has("plotIndex") ? json.get("plotIndex").getAsInt() : -1;

        if (plotIndex < 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        FarmService farmService = ServiceRegistry.getFarmService();
        Map<String, Object> result = farmService.usePesticide(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    // ==================== 全局季节系统 ====================

    @GET
    @Path("/season")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSeasonInfo() {
        JsonObject data = new JsonObject();
        data.addProperty("season", com.mtxgdn.game.service.SeasonService.getCurrentSeason().name());
        data.addProperty("seasonDisplay", com.mtxgdn.game.service.SeasonService.getCurrentSeason().getDisplayName());
        data.addProperty("seasonDesc", com.mtxgdn.game.service.SeasonService.getCurrentSeason().getDescription());
        data.addProperty("seasonEffect", com.mtxgdn.game.service.SeasonService.getSeasonEffectDescription());
        data.addProperty("cultivationMultiplier", com.mtxgdn.game.service.SeasonService.getCultivationMultiplier());
        data.addProperty("explorationMultiplier", com.mtxgdn.game.service.SeasonService.getExplorationMultiplier());
        data.addProperty("spiritStoneMultiplier", com.mtxgdn.game.service.SeasonService.getSpiritStoneMultiplier());
        data.addProperty("dropMultiplier", com.mtxgdn.game.service.SeasonService.getDropMultiplier());
        data.addProperty("seasonStartMs", com.mtxgdn.game.service.SeasonService.getSeasonStartMs());
        data.addProperty("seasonEndMs", com.mtxgdn.game.service.SeasonService.getSeasonEndMs());
        data.addProperty("remainingMs", com.mtxgdn.game.service.SeasonService.getSeasonRemainingMs());
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }
}
