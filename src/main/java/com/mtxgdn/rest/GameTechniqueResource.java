package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.TechniqueService;
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
import static com.mtxgdn.rest.GameSupport.playerService;

/**
 * 玩家侧 · 功法与合成接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：功法总表/我的功法/学习/
 * 装备/卸下/升级，以及配方列表与合成。功法与合成在玩法上是同一条养成链
 * （配方吃材料、功法吃功法书），依赖集合（功法服务、合成服务、玩家服务）也一致，
 * 因此合成没有另开一个类。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /techniques}、{@code /technique/my}、{@code /technique/upgrade}、
 * {@code /crafting/recipes}、{@code /crafting/craft}）逐字照搬；权限码
 * {@code game.technique.learn} / {@code game.technique.equip} /
 * {@code game.technique.upgrade} / {@code game.crafting.recipes} /
 * {@code game.crafting.craft} 全部保持原样，返回结构未动。</p>
 */
@Path("/game")
public class GameTechniqueResource {

    private static final TechniqueService techniqueService = ServiceRegistry.getTechniqueService();

    private static final CraftingService craftingService = ServiceRegistry.getCraftingService();

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
    @Path("/techniques")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.learn")
    public Response getAllTechniques() {
        List<Technique> techniques = techniqueService.getAllTechniques();
        JsonArray arr = new JsonArray();
        for (Technique t : techniques) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.getId());
            o.addProperty("name", t.getName());
            o.addProperty("description", t.getDescription());
            o.addProperty("requiredRealm", t.getRequiredRealm());
            o.addProperty("learnCostGold", t.getLearnCostGold());
            o.addProperty("learnCostSpiritStones", t.getLearnCostSpiritStones());
            o.addProperty("upgradeBaseCostGold", t.getUpgradeBaseCostGold());
            o.addProperty("upgradeBaseCostSpiritStones", t.getUpgradeBaseCostSpiritStones());
            o.addProperty("type", t.getType().name());
            o.addProperty("maxLevel", t.getMaxLevel());
            o.addProperty("hpBonus", t.getHpBonus());
            o.addProperty("mpBonus", t.getMpBonus());
            o.addProperty("attackBonus", t.getAttackBonus());
            o.addProperty("defenseBonus", t.getDefenseBonus());
            o.addProperty("speedBonus", t.getSpeedBonus());
            o.addProperty("spiritBonus", t.getSpiritBonus());
            o.addProperty("cultivationSpeedBonus", t.getCultivationSpeedBonus());
            o.addProperty("expBonus", t.getExpBonus());
            o.addProperty("combatDamageBonus", t.getCombatDamageBonus());
            o.addProperty("damageReduction", t.getDamageReduction());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("techniques", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/technique/my")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.learn")
    public Response getMyTechniques() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        List<Technique> techniques = techniqueService.getPlayerTechniques(player.getId());
        JsonArray arr = new JsonArray();
        for (Technique t : techniques) {
            JsonObject o = gson.toJsonTree(t).getAsJsonObject();
            o.addProperty("scaledHpBonus", t.getScaledHpBonus());
            o.addProperty("scaledMpBonus", t.getScaledMpBonus());
            o.addProperty("scaledAttackBonus", t.getScaledAttackBonus());
            o.addProperty("scaledDefenseBonus", t.getScaledDefenseBonus());
            o.addProperty("scaledSpeedBonus", t.getScaledSpeedBonus());
            o.addProperty("scaledSpiritBonus", t.getScaledSpiritBonus());
            o.addProperty("scaledCultivationSpeedBonus", t.getScaledCultivationSpeedBonus());
            o.addProperty("scaledExpBonus", t.getScaledExpBonus());
            o.addProperty("scaledCombatDamageBonus", t.getScaledCombatDamageBonus());
            o.addProperty("scaledDamageReduction", t.getScaledDamageReduction());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("techniques", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/technique/learn")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.learn")
    public Response learnTechnique(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        long techniqueId = req.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.learnTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/technique/equip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.equip")
    public Response equipTechnique(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        long techniqueId = req.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.equipTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/technique/unequip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.equip")
    public Response unequipTechnique(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        long techniqueId = req.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.unequipTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/technique/upgrade")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.technique.upgrade")
    public Response upgradeTechnique(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        long techniqueId = req.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.upgradeTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/crafting/recipes")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.crafting.recipes")
    public Response getRecipes(@QueryParam("category") @DefaultValue("") String category) {
        List<Recipe> recipes;
        if (category != null && !category.isEmpty()) {
            try {
                Recipe.Category cat = Recipe.Category.valueOf(category.toUpperCase());
                recipes = craftingService.getRecipesByCategory(cat);
            } catch (IllegalArgumentException e) {
                return Response.ok(GameMessage.restError(400, I18n.t("error.invalid_recipe_category") + category).toString()).build();
            }
        } else {
            recipes = craftingService.getAllRecipes();
        }
        JsonArray arr = new JsonArray();
        for (Recipe r : recipes) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.getId());
            o.addProperty("name", r.getName());
            o.addProperty("description", r.getDescription());
            o.addProperty("category", r.getCategory().name());
            o.addProperty("requiredRealm", r.getRequiredRealm());
            o.addProperty("resultItemKey", r.getResultItemKey());
            o.addProperty("resultQuantity", r.getResultQuantity());
            if (r.getMaterial1Key() != null) o.addProperty("material1Key", r.getMaterial1Key());
            if (r.getMaterial1Key() != null) o.addProperty("material1Count", r.getMaterial1Count());
            if (r.getMaterial2Key() != null) o.addProperty("material2Key", r.getMaterial2Key());
            if (r.getMaterial2Key() != null) o.addProperty("material2Count", r.getMaterial2Count());
            if (r.getMaterial3Key() != null) o.addProperty("material3Key", r.getMaterial3Key());
            if (r.getMaterial3Key() != null) o.addProperty("material3Count", r.getMaterial3Count());
            o.addProperty("costGold", r.getCostGold());
            o.addProperty("costSpiritStones", r.getCostSpiritStones());
            o.addProperty("successRate", r.getSuccessRate());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("recipes", arr);
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/crafting/craft")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.crafting.craft")
    public Response craftItem(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject req = gson.fromJson(body, JsonObject.class);
        long recipeId = req.get("recipeId").getAsLong();
        Map<String, Object> result = craftingService.craft(player.getId(), recipeId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject data = new JsonObject();
            data.addProperty("message", (String) result.get("message"));
            if (result.containsKey("craftSuccess")) data.addProperty("craftSuccess", (boolean) result.get("craftSuccess"));
            if (result.containsKey("expGained")) data.addProperty("expGained", ((Number) result.get("expGained")).longValue());
            if (result.containsKey("itemGained")) data.addProperty("itemGained", (String) result.get("itemGained"));
            if (result.containsKey("itemQuantity")) data.addProperty("itemQuantity", ((Number) result.get("itemQuantity")).intValue());
            return Response.ok(GameMessage.restOk((String) result.get("message"), data).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }
}
