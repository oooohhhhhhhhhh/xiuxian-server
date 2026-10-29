package com.mtxgdn.rest;

import com.mtxgdn.game.skill.LearnSkillResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.EnhanceService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.SkillService;
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
import static com.mtxgdn.rest.GameSupport.actionLog;
import static com.mtxgdn.rest.GameSupport.itemService;
import static com.mtxgdn.rest.GameSupport.getPlayerName;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 背包、装备与技能接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：物品图鉴、背包列表、物品使用、
 * 装备穿戴/卸下/强化、技能列表与学习。这些端点读写的都是"玩家身上的物品与技能"，
 * 依赖集合（物品服务、强化服务、技能服务、行为日志）与其它域不重叠。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /inventory}、{@code /equipment/enhance}、{@code /item/use}、
 * {@code /skill/learn} 等）逐字照搬；权限码 {@code game.inventory.view}、
 * {@code game.equipment.equip}、{@code game.equipment.enhance}、{@code game.item.use}、
 * {@code game.skill.learn} 以及 {@code /item/add} 上的 {@code admin.items.give}
 * 全部保持原样，返回结构未动。</p>
 *
 * <p>{@code /item/registry} 与 {@code /skills} 是静态图鉴查询，也留在本类：
 * 它们与背包/技能端点共享同一批服务引用，拆开反而要重复引用同一份依赖。</p>
 */
@Path("/game")
public class GameInventoryResource {

    private static final SkillService skillService = ServiceRegistry.getSkillService();

    private static final EnhanceService enhanceService = ServiceRegistry.getEnhanceService();

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
    @Path("/item/registry")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.item.registry")
    public Response getItemRegistry() {
        JsonArray items = new JsonArray();
        for (Item item : ItemRegistry.getAll()) {
            items.add(gson.toJsonTree(item));
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/inventory")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.inventory.view")
    public Response getInventory() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        List<ItemService.InventoryEntry> inventory = itemService.getInventory(playerId);

        JsonArray items = new JsonArray();
        for (ItemService.InventoryEntry entry : inventory) {
            JsonObject obj = gson.toJsonTree(entry.getItem()).getAsJsonObject();
            obj.addProperty("quantity", entry.getQuantity());
            items.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/item/use")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.item.use")
    public Response useItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";

        Map<String, Object> useResult = itemService.useItem(playerId, itemKey);

        boolean success = (boolean) useResult.getOrDefault("success", false);
        String message = (String) useResult.getOrDefault("message", "");
        actionLog.logItemUse(userId, getPlayerName(userId), itemKey, success, message);

        JsonObject data = gson.toJsonTree(useResult).getAsJsonObject();

        if (success) {
            return Response.ok(GameMessage.restOk(message, data).toString()).build();
        } else {
            return Response.ok(GameMessage.restError(GameErrorCode.ITEM_USE_FAILED.getCode(), message).toString()).build();
        }
    }

    @POST
    @Path("/item/add")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.items.give")
    public Response addItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 1;

        boolean ok = itemService.addItem(playerId, itemKey, quantity);

        if (ok) {
            actionLog.logItemAdd(userId, getPlayerName(userId), itemKey, quantity);
        }

        JsonObject data = new JsonObject();
        data.addProperty("itemKey", itemKey);
        data.addProperty("quantity", quantity);
        data.addProperty("added", ok);

        if (ok) {
            return Response.ok(GameMessage.restOk(I18n.t("item.gain_ok"), data).toString()).build();
        } else {
            return Response.ok(GameMessage.restError(GameErrorCode.ITEM_NOT_FOUND).toString()).build();
        }
    }

    @GET
    @Path("/equipment")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.inventory.view")
    public Response getEquipment() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        Map<String, String> equipment = itemService.getEquipment(playerId);
        JsonObject data = new JsonObject();
        JsonObject slots = new JsonObject();

        for (var entry : equipment.entrySet()) {
            Item item = ItemRegistry.get(entry.getValue());
            if (item != null) {
                JsonObject slotData = gson.toJsonTree(item).getAsJsonObject();
                int enhanceLevel = enhanceService.getEnhanceLevel(playerId, entry.getKey());
                slotData.addProperty("enhanceLevel", enhanceLevel);
                if (enhanceLevel > 0) {
                    int[] bonuses = EnhanceService.getEnhanceStatBonus(enhanceLevel);
                    slotData.addProperty("enhanceAtk", bonuses[0]);
                    slotData.addProperty("enhanceDef", bonuses[1]);
                    slotData.addProperty("enhanceSpd", bonuses[2]);
                    slotData.addProperty("enhanceSpirit", bonuses[3]);
                }
                slots.add(entry.getKey(), slotData);
            }
        }
        data.add("slots", slots);

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/equipment/equip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.equipment.equip")
    public Response equipItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        String slot = json.has("slot") ? json.get("slot").getAsString() : "";

        if (itemKey.isEmpty() || slot.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        var result = itemService.equipItem(playerId, itemKey, slot);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), null).toString()).build();
    }

    @POST
    @Path("/equipment/unequip")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.equipment.equip")
    public Response unequipItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String slot = json.has("slot") ? json.get("slot").getAsString() : "";

        if (slot.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        var result = itemService.unequipItem(playerId, slot);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), null).toString()).build();
    }

    @POST
    @Path("/equipment/enhance")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.equipment.enhance")
    public Response enhanceEquipment(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String slot = json.has("slot") ? json.get("slot").getAsString() : "";

        if (slot.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        Map<String, Object> result = enhanceService.enhanceItem(playerId, slot);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
            return Response.ok(GameMessage.restOk((String) result.get("message"), respData).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/skills")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.item.registry")
    public Response getSkills() {
        List<Skill> allSkills = skillService.getAllSkills();

        JsonArray skillsArray = new JsonArray();
        for (Skill skill : allSkills) {
            JsonObject obj = gson.toJsonTree(skill).getAsJsonObject();
            skillsArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("skills", skillsArray);
        data.addProperty("count", skillsArray.size());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @GET
    @Path("/skill/my")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.inventory.view")
    public Response getMySkills() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        List<Skill> mySkills = skillService.getPlayerSkills(playerId);

        JsonArray skillsArray = new JsonArray();
        for (Skill skill : mySkills) {
            JsonObject obj = gson.toJsonTree(skill).getAsJsonObject();
            skillsArray.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("skills", skillsArray);
        data.addProperty("count", skillsArray.size());

        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/skill/learn")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.skill.learn")
    public Response learnSkill(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long skillId = json.has("skillId") ? json.get("skillId").getAsLong() : 0;

        if (skillId <= 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_INVALID).toString()).build();
        }

        Skill skill = skillService.getSkillById(skillId);
        LearnSkillResult learnResult = skillService.learnSkill(playerId, skillId);

        JsonObject data = gson.toJsonTree(learnResult).getAsJsonObject();

        String skillName = skill != null ? skill.getName() : "技能ID:" + skillId;
        actionLog.logSkillLearn(userId, getPlayerName(userId), skillName, learnResult.success(),
                learnResult.message());

        if (learnResult.success()) {
            return Response.ok(GameMessage.restOk(learnResult.message(), data).toString()).build();
        } else {
            return Response.ok(GameMessage.restError(learnResult.code(), learnResult.message()).toString()).build();
        }
    }
}
