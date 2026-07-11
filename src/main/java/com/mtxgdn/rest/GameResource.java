package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.common.ExperimentalConfig;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.DailyService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TeamService;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.game.service.EnhanceService;
import com.mtxgdn.game.service.ChatService;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.FarmService;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.service.SectService;
import com.mtxgdn.game.service.MapService;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.Title;
import com.mtxgdn.game.service.TitleService;
import com.mtxgdn.game.title.TitleRegistry;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.PlayerActionLogger;
import jakarta.ws.rs.*;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/game")
public class GameResource {

    private static final Gson gson = new Gson();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();
    private static final RealmService realmService = ServiceRegistry.getRealmService();
    private static final ItemService itemService = ServiceRegistry.getItemService();
    private static final SkillService skillService = ServiceRegistry.getSkillService();
    private static final CombatService combatService = ServiceRegistry.getCombatService();
    private static final SecretRealmService secretRealmService = ServiceRegistry.getSecretRealmService();
    private static final ExplorationService explorationService = ServiceRegistry.getExplorationService();
    private static final DailyService dailyService = ServiceRegistry.getDailyService();
    private static final TradeService tradeService = ServiceRegistry.getTradeService();
    private static final HeartDemonService heartDemonService = ServiceRegistry.getHeartDemonService();
    private static final TechniqueService techniqueService = ServiceRegistry.getTechniqueService();
    private static final CraftingService craftingService = ServiceRegistry.getCraftingService();
    private static final EnhanceService enhanceService = ServiceRegistry.getEnhanceService();
    private static final ChatService chatService = ServiceRegistry.getChatService();
    private static final FriendService friendService = ServiceRegistry.getFriendService();
    private static final SectService sectService = ServiceRegistry.getSectService();
    private static final MapService mapService = new MapService();

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
            case "breakthrough", "cultivate" -> 5;
            case "explore", "secret" -> 6;
            case "heal", "friend" -> 15;
            default -> 30;
        };
        if (!RateLimiter.allow(key, limit, 60)) {
            throw new WebApplicationException("操作太频繁，请稍后再试", 429);
        }
    }

    private String getPlayerName(Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        return player != null ? player.getName() : "未知玩家";
    }

    @GET
    @Path("/player")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getPlayerInfo() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            JsonObject result = GameMessage.restOk("尚未创建角色", null);
            return Response.ok(result.toString()).build();
        }

        OfflineRewardService.OfflineRewardResult offlineReward =
                new OfflineRewardService().processOfflineRewards(userId);

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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        PlayerInfo player = playerService.createPlayer(userId, name);

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
        return Response.ok(GameMessage.restOk("角色创建成功", data).toString()).build();
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
    @Path("/realm/config")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.realm.config")
    public Response getRealmConfig() {
        JsonObject data = gson.toJsonTree(GameConfigLoader.getRealmConfigs()).getAsJsonObject();
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        playerService.setCultivating(player.getId(), true);

        actionLog.logCultivateStart(userId, player.getName(), player.getRealm());

        JsonObject data = new JsonObject();
        data.addProperty("cultivating", true);
        data.addProperty("cultivationPerSecond", getCultivationPerSecond(player.getRealm()));
        data.addProperty("startTime", System.currentTimeMillis());

        return Response.ok(GameMessage.restOk("开始修炼", data).toString()).build();
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

        long startTime = player.getCultivationStartTime();
        int elapsedSeconds = 0;
        if (startTime > 0) {
            elapsedSeconds = (int) ((System.currentTimeMillis() - startTime) / 1000);
        }

        int cultivationPerSec = getCultivationPerSecond(player.getRealm());
        long expGained = (long) elapsedSeconds * cultivationPerSec;

        playerService.setCultivating(player.getId(), false);

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

    private int getCultivationPerSecond(int realm) {
        double multiplier = GameConfigLoader.getCultivationMultiplier(realm);
        int base = GameConfigLoader.getCultivationBaseValue();
        return (int) (base * multiplier);
    }

    private int getPlayerIdByUserId(long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            throw new WebApplicationException("请先创建角色", 400);
        }
        return (int) player.getId();
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
            data.addProperty("message", "请先创建角色");
            return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        Map<String, Object> result = playerService.healPlayer(player.getId());
        JsonObject data = gson.toJsonTree(result).getAsJsonObject();
        boolean success = (boolean) result.getOrDefault("success", false);
        if (success) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), data).toString()).build();
        } else {
            return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
        }
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
    @RequirePermission("game.item.add")
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
            return Response.ok(GameMessage.restOk("获得物品成功", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        Map<String, Object> learnResult = skillService.learnSkill(playerId, skillId);

        JsonObject data = gson.toJsonTree(learnResult).getAsJsonObject();
        boolean success = (boolean) learnResult.getOrDefault("success", false);

        String skillName = skill != null ? skill.getName() : "技能ID:" + skillId;
        actionLog.logSkillLearn(userId, getPlayerName(userId), skillName, success,
                (String) learnResult.getOrDefault("message", ""));

        if (success) {
            return Response.ok(GameMessage.restOk((String) learnResult.get("message"), data).toString()).build();
        } else {
            int code = learnResult.containsKey("code") ? ((Number) learnResult.get("code")).intValue() : 6104;
            return Response.ok(GameMessage.restError(code, (String) learnResult.get("message")).toString()).build();
        }
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

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getServerStatus() {
        long uptimeMillis = System.currentTimeMillis() - Main.serverStartTime;
        long uptimeSeconds = uptimeMillis / 1000;

        int onlineCount = Main.gameWebSocketApp != null ? Main.gameWebSocketApp.getOnlineCount() : 0;
        int totalPlayers = playerService.getPlayerCount();
        int totalUsers = playerService.getUserCount();

        JsonObject data = new JsonObject();
        data.addProperty("uptimeSeconds", uptimeSeconds);
        data.addProperty("uptimeFormatted", formatUptime(uptimeSeconds));
        data.addProperty("onlinePlayers", onlineCount);
        data.addProperty("totalPlayers", totalPlayers);
        data.addProperty("totalUsers", totalUsers);
        data.addProperty("serverVersion", "1.0.0");

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        data.addProperty("usedMemoryMB", usedMemory);
        data.addProperty("maxMemoryMB", maxMemory);

        return Response.ok(GameMessage.restOk("服务器运行正常", data).toString()).build();
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @GET
    @Path("/players/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response searchPlayers(
            @QueryParam("name") @DefaultValue("") String name,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        if (name.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING.getCode(), "请提供搜索名称").toString()).build();
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

        return Response.ok(GameMessage.restOk("搜索成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取灵根列表成功", rootData).toString()).build();
    }

    @POST
    @Path("/daily/morning_cultivation")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.daily")
    public Response morningCultivation() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var result = dailyService.doMorningCultivation(playerId);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @GET
    @Path("/daily")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.daily")
    public Response getDailyInfo() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var result = dailyService.getDailyInfo(playerId);
        return Response.ok(GameMessage.restOk("获取成功", gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @GET
    @Path("/market")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.view")
    public Response getMarketListings() {
        var listings = tradeService.getActiveListings();
        JsonArray arr = new JsonArray();
        for (var l : listings) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id);
            o.addProperty("sellerPlayerId", l.sellerPlayerId);
            o.addProperty("itemKey", l.itemKey);
            Item item = ItemRegistry.get(l.itemKey);
            o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
            o.addProperty("quantity", l.quantity);
            o.addProperty("priceSpiritStones", l.priceSpiritStones);
            o.addProperty("fee", l.fee);
            o.addProperty("createdAt", l.createdAt);
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("listings", arr);
        data.addProperty("count", arr.size());
        data.addProperty("tradeFeeRate", "5%");
        return Response.ok(GameMessage.restOk("坊市列表", data).toString()).build();
    }

    @POST
    @Path("/market/list")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.list")
    public Response listItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.get("itemKey").getAsString();
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 1;
        long price = json.get("priceSpiritStones").getAsLong();
        var result = tradeService.listItem(playerId, itemKey, quantity, price);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/market/buy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.buy")
    public Response buyItem(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long listingId = json.get("listingId").getAsLong();
        var result = tradeService.buyItem(playerId, listingId);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/market/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.cancel")
    public Response cancelListing(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long listingId = json.get("listingId").getAsLong();
        var result = tradeService.cancelListing(playerId, listingId);
        return Response.ok(GameMessage.restOk(result.get("message").toString(), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @GET
    @Path("/market/my_listings")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.market.view")
    public Response getMyListings() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        var listings = tradeService.getPlayerListings(playerId);
        JsonArray arr = new JsonArray();
        for (var l : listings) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id);
            o.addProperty("itemKey", l.itemKey);
            Item item = ItemRegistry.get(l.itemKey);
            o.addProperty("itemName", item != null ? item.getName() : l.itemKey);
            o.addProperty("quantity", l.quantity);
            o.addProperty("priceSpiritStones", l.priceSpiritStones);
            o.addProperty("fee", l.fee);
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("listings", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
                return Response.ok(GameMessage.restError(400, "无效的配方分类: " + category).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

    @GET
    @Path("/chat/world")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.chat.world")
    public Response getWorldChat(@QueryParam("limit") @DefaultValue("50") int limit) {
        List<ChatMessage> messages = chatService.getWorldMessages(limit);
        chatService.setSenderNames(messages, playerService);

        JsonArray arr = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("id", msg.getId());
            o.addProperty("senderPlayerId", msg.getSenderPlayerId());
            o.addProperty("senderName", msg.getSenderName() != null ? msg.getSenderName() : "未知");
            o.addProperty("content", msg.getContent());
            o.addProperty("createdAt", msg.getCreatedAt());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("messages", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @GET
    @Path("/chat/private/{targetPlayerId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.chat.private")
    public Response getPrivateChat(@PathParam("targetPlayerId") long targetPlayerId,
                                    @QueryParam("limit") @DefaultValue("50") int limit) {
        Long userId = getCurrentUserId();
        PlayerInfo self = playerService.getPlayerByUserId(userId);
        if (self == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        List<ChatMessage> messages = chatService.getPrivateMessages(self.getId(), targetPlayerId, limit);
        chatService.setSenderNames(messages, playerService);

        JsonArray arr = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("id", msg.getId());
            o.addProperty("senderPlayerId", msg.getSenderPlayerId());
            o.addProperty("senderName", msg.getSenderName() != null ? msg.getSenderName() : "未知");
            o.addProperty("receiverPlayerId", msg.getReceiverPlayerId() != null ? msg.getReceiverPlayerId() : 0);
            o.addProperty("receiverName", msg.getReceiverName() != null ? msg.getReceiverName() : "");
            o.addProperty("content", msg.getContent());
            o.addProperty("createdAt", msg.getCreatedAt());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("messages", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @POST
    @Path("/chat/world")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.chat.world")
    public Response sendWorldChat(String body) {
        checkActionRateLimit("chat");
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        String content = json.has("content") ? json.get("content").getAsString() : "";
        if (content.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        ChatMessage msg = chatService.sendWorldMessage(player.getId(), player.getName(), content);
        if (msg == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_INVALID).toString()).build();
        }
        JsonObject data = gson.toJsonTree(msg).getAsJsonObject();
        return Response.ok(GameMessage.restOk("发送成功", data).toString()).build();
    }

    @POST
    @Path("/chat/private")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.chat.private")
    public Response sendPrivateChat(String body) {
        checkActionRateLimit("chat");
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        String content = json.has("content") ? json.get("content").getAsString() : "";
        long targetId = json.has("targetPlayerId") ? json.get("targetPlayerId").getAsLong() : 0;

        if (targetId == 0 || content.trim().isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        if (targetId == player.getId()) {
            return Response.ok(GameMessage.restError(GameErrorCode.CHAT_SELF_MESSAGE).toString()).build();
        }
        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.CHAT_RECEIVER_NOT_FOUND).toString()).build();
        }

        ChatMessage msg = chatService.sendPrivateMessage(player.getId(), player.getName(), targetId, content);
        actionLog.logChat(userId, player.getName(), "[私聊→" + target.getName() + "] " + content);

        JsonObject data = gson.toJsonTree(msg).getAsJsonObject();
        return Response.ok(GameMessage.restOk("发送成功", data).toString()).build();
    }

    @GET
    @Path("/rank")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.rank.view")
    public Response getRanking(@QueryParam("type") @DefaultValue("realm") String type,
                                @QueryParam("limit") @DefaultValue("20") int limit) {
        List<PlayerInfo> players;
        switch (type) {
            case "power":
                players = playerService.getTopByPower(limit);
                break;
            case "wealth":
                players = playerService.getTopByWealth(limit);
                break;
            default:
                players = playerService.getTopByRealm(limit);
                break;
        }

        JsonArray arr = new JsonArray();
        int rank = 1;
        for (PlayerInfo p : players) {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank++);
            o.addProperty("playerId", p.getId());
            o.addProperty("name", p.getName());
            o.addProperty("realm", p.getRealm());
            o.addProperty("realmName", p.getRealmName());
            o.addProperty("level", p.getLevel());
            o.addProperty("attack", p.getAttack());
            o.addProperty("defense", p.getDefense());
            o.addProperty("gold", p.getGold());
            long spiritStones = itemService.getSpiritStoneCount(p.getId());
            o.addProperty("spiritStones", spiritStones);
            o.addProperty("totalWealth", p.getGold() + spiritStones);
            arr.add(o);
        }

        JsonObject data = new JsonObject();
        data.add("ranking", arr);
        data.addProperty("type", type);

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @GET
    @Path("/friend/list")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response getFriendList() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        List<Friend> friends = friendService.getFriends(player.getId());
        JsonArray arr = new JsonArray();
        for (Friend f : friends) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.getId());
            o.addProperty("friendPlayerId", f.getFriendPlayerId());
            o.addProperty("friendName", f.getFriendName() != null ? f.getFriendName() : "");
            o.addProperty("friendRealm", f.getFriendRealm() != null ? f.getFriendRealm() : "");
            o.addProperty("status", f.getStatus());
            o.addProperty("createdAt", f.getCreatedAt());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("friends", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @GET
    @Path("/friend/pending")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response getPendingRequests() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        List<Friend> requests = friendService.getPendingRequests(player.getId());
        JsonArray arr = new JsonArray();
        for (Friend f : requests) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.getId());
            o.addProperty("requesterPlayerId", f.getPlayerId());
            o.addProperty("requesterName", f.getFriendName() != null ? f.getFriendName() : "");
            o.addProperty("requesterRealm", f.getFriendRealm() != null ? f.getFriendRealm() : "");
            o.addProperty("status", f.getStatus());
            o.addProperty("createdAt", f.getCreatedAt());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("requests", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @POST
    @Path("/friend/add")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response addFriend(String body) {
        checkActionRateLimit("friend");
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        long targetId = json.has("targetPlayerId") ? json.get("targetPlayerId").getAsLong() : 0;

        if (targetId == 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        if (targetId == player.getId()) {
            return Response.ok(GameMessage.restError(GameErrorCode.FRIEND_SELF_TARGET).toString()).build();
        }
        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        Friend result = friendService.sendRequest(player.getId(), targetId);
        if (result == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        if ("exists".equals(result.getStatus())) {
            return Response.ok(GameMessage.restError(GameErrorCode.FRIEND_ALREADY_EXISTS).toString()).build();
        }

        JsonObject data = gson.toJsonTree(result).getAsJsonObject();
        return Response.ok(GameMessage.restOk("好友申请已发送", data).toString()).build();
    }

    @POST
    @Path("/friend/accept")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response acceptFriend(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        long requesterId = json.has("requesterPlayerId") ? json.get("requesterPlayerId").getAsLong() : 0;

        if (requesterId == 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        boolean success = friendService.acceptRequest(player.getId(), requesterId);
        if (!success) {
            return Response.ok(GameMessage.restError(GameErrorCode.FRIEND_REQUEST_NOT_FOUND).toString()).build();
        }
        return Response.ok(GameMessage.restOk("已添加好友", null).toString()).build();
    }

    @POST
    @Path("/friend/remove")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response removeFriend(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }
        JsonObject json = gson.fromJson(body, JsonObject.class);
        long friendId = json.has("friendPlayerId") ? json.get("friendPlayerId").getAsLong() : 0;

        if (friendId == 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        boolean success = friendService.removeFriend(player.getId(), friendId);
        if (!success) {
            return Response.ok(GameMessage.restError(GameErrorCode.FRIEND_NOT_FOUND).toString()).build();
        }
        return Response.ok(GameMessage.restOk("已删除好友", null).toString()).build();
    }


    @GET
    @Path("/sect/members")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response getSectMembers() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Sect sect = sectService.getPlayerSect(playerId);
        if (sect == null) {
            return Response.ok(GameMessage.restOk("尚未加入宗门", null).toString()).build();
        }
        List<SectMember> members = sectService.getSectMembers(sect.getId());
        JsonArray arr = new JsonArray();
        for (SectMember m : members) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getId());
            o.addProperty("playerId", m.getPlayerId());
            o.addProperty("playerName", m.getPlayerName());
            o.addProperty("role", m.getRole());
            o.addProperty("roleDisplay", SectMember.getRoleDisplayName(m.getRole()));
            o.addProperty("contribution", m.getContribution());
            o.addProperty("playerRealm", m.getPlayerRealm());
            o.addProperty("playerLevel", m.getPlayerLevel());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("members", arr);
        data.addProperty("sectName", sect.getName());
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @POST
    @Path("/sect/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response createSect(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

        String name = json.has("name") ? json.get("name").getAsString() : "";
        String desc = json.has("description") ? json.get("description").getAsString() : "";
        Map<String, Object> result = sectService.createSect(playerId, name, desc);

        actionLog.logCreatePlayer(userId, getPlayerName(userId));
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"),
                    gson.toJsonTree(result.get("sect")).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/join/{sectId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response joinSect(@PathParam("sectId") long sectId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.applyToSect(playerId, sectId, "");
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/sect/applications")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response getSectApplications() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectMember member = sectService.getPlayerMember(playerId);
        if (member == null) {
            return Response.ok(GameMessage.restError(400, "你还没有加入宗门").toString()).build();
        }
        List<SectApplication> apps = sectService.getPendingApplications(member.getSectId());
        JsonArray arr = new JsonArray();
        for (SectApplication a : apps) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.getId());
            o.addProperty("playerId", a.getPlayerId());
            o.addProperty("playerName", a.getPlayerName());
            o.addProperty("message", a.getMessage());
            o.addProperty("createdAt", a.getCreatedAt());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("applications", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @POST
    @Path("/sect/approve/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response approveApplication(@PathParam("appId") long appId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.approveApplication(playerId, appId, true);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/reject/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response rejectApplication(@PathParam("appId") long appId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.approveApplication(playerId, appId, false);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/leave")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response leaveSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.leaveSect(playerId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/kick/{targetPlayerId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response kickMember(@PathParam("targetPlayerId") long targetPlayerId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.kickMember(playerId, targetPlayerId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/appoint")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response appointMember(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        long targetId = json.has("targetPlayerId") ? json.get("targetPlayerId").getAsLong() : 0;
        String role = json.has("role") ? json.get("role").getAsString() : "";
        if (targetId == 0 || role.isEmpty()) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }
        Map<String, Object> result = sectService.appointMember(playerId, targetId, role);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/sect/warehouse")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response getSectWarehouse() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectMember member = sectService.getPlayerMember(playerId);
        if (member == null) {
            return Response.ok(GameMessage.restError(400, "你还没有加入宗门").toString()).build();
        }
        List<SectWarehouseItem> items = sectService.getWarehouse(member.getSectId());
        JsonArray arr = new JsonArray();
        for (SectWarehouseItem item : items) {
            JsonObject o = new JsonObject();
            o.addProperty("id", item.getId());
            o.addProperty("itemKey", item.getItemKey());
            o.addProperty("quantity", item.getQuantity());
            o.addProperty("donatedByPlayerId", item.getDonatedByPlayerId());
            o.addProperty("donatedByName", item.getDonatedByName());
            com.mtxgdn.game.item.Item it = com.mtxgdn.game.item.ItemRegistry.get(item.getItemKey());
            long contributionCost = it != null ? sectService.getItemContributionCost(it) : 50;
            o.addProperty("contributionCost", contributionCost);
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("warehouse", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @POST
    @Path("/sect/donate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.donate")
    public Response donateToWarehouse(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectMember member = sectService.getPlayerMember(playerId);
        if (member == null) {
            return Response.ok(GameMessage.restError(400, "你还没有加入宗门").toString()).build();
        }
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 0;
        Map<String, Object> result = sectService.donateToWarehouse(playerId, member.getSectId(), itemKey, quantity);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/take")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.warehouse")
    public Response takeFromWarehouse(String body) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectMember member = sectService.getPlayerMember(playerId);
        if (member == null) {
            return Response.ok(GameMessage.restError(400, "你还没有加入宗门").toString()).build();
        }
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 0;
        Map<String, Object> result = sectService.withdrawFromWarehouse(playerId, member.getSectId(), itemKey, quantity);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/disband")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response disbandSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.disbandSect(playerId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/levelup")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response levelUpSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.levelUp(playerId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/transfer/{targetPlayerId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response transferSect(@PathParam("targetPlayerId") long targetPlayerId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.transferLeader(playerId, targetPlayerId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @POST
    @Path("/sect/war/{targetSectId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response declareWar(@PathParam("targetSectId") long targetSectId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = sectService.declareWar(playerId, targetSectId);
        if ((boolean) result.get("success")) {
            JsonObject data = new JsonObject();
            data.addProperty("winner", (String) result.get("winner"));
            data.addProperty("attackerWins", (int) result.get("attackerWins"));
            data.addProperty("defenderWins", (int) result.get("defenderWins"));
            data.addProperty("battleLog", (String) result.get("battleLog"));
            return Response.ok(GameMessage.restOk((String) result.get("message"), data).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/sect/top")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response getSectTop() {
        List<Sect> sects = sectService.getTopSects(10);
        JsonArray arr = new JsonArray();
        int rank = 1;
        for (Sect s : sects) {
            JsonObject o = new JsonObject();
            o.addProperty("rank", rank++);
            o.addProperty("id", s.getId());
            o.addProperty("name", s.getName());
            o.addProperty("leaderName", s.getLeaderName());
            o.addProperty("prestige", s.getPrestige());
            o.addProperty("memberCount", s.getMemberCount());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("top", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @GET
    @Path("/map")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getMapSurroundings() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = mapService.getPlayerSurroundings(playerId);
        return Response.ok(GameMessage.restOk("获取成功", gson.toJsonTree(result).getAsJsonObject()).toString()).build();
    }

    @POST
    @Path("/map/travel/{locationId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response travelTo(@PathParam("locationId") long locationId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        Map<String, Object> result = mapService.travel(playerId, locationId);
        if ((boolean) result.get("success")) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }

    @GET
    @Path("/map/locations")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getMapLocations() {
        mapService.ensureInitialized();
        List<MapLocation> locations = mapService.getAllLocations();
        JsonArray arr = new JsonArray();
        for (MapLocation loc : locations) {
            JsonObject o = new JsonObject();
            o.addProperty("id", loc.getId());
            o.addProperty("name", loc.getName());
            o.addProperty("description", loc.getDescription());
            o.addProperty("region", loc.getRegion());
            o.addProperty("minRealm", loc.getMinRealm());
            o.addProperty("safeZone", loc.isSafeZone());
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("locations", arr);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    @SuppressWarnings("unused")
    private Response buildSectResponse(Sect sect, int playerId) {
        JsonObject data = gson.toJsonTree(sect).getAsJsonObject();
        data.addProperty("maxMembers", Sect.getMaxMembersForLevel(sect.getLevel()));
        SectMember me = sectService.getMember(sect.getId(), playerId);
        if (me != null) {
            data.addProperty("myRole", me.getRole());
            data.addProperty("myRoleDisplay", SectMember.getRoleDisplayName(me.getRole()));
            data.addProperty("myContribution", me.getContribution());
        }
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

    private String formatUptime(long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天 ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("小时 ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("分 ");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
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
        return Response.ok(GameMessage.restOk((String) result.get("message"), gson.toJsonTree(result).getAsJsonObject()).toString()).build();
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

    // ==================== 团队系统 ====================

    @POST
    @Path("/team/create")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.team.manage")
    public Response createTeam() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        TeamService teamService = TeamService.getInstance();
        TeamService.Team team = teamService.createTeam(player.getId());
        if (team == null) {
            return Response.ok(GameMessage.restError(400, "你已经在一个团队中").toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("teamId", team.getTeamId());
        data.addProperty("leaderId", team.getLeaderId());
        data.addProperty("leaderName", player.getName());
        JsonArray members = new JsonArray();
        for (long memberId : team.getMemberIds()) {
            PlayerInfo m = playerService.getPlayerInfoById(memberId);
            JsonObject o = new JsonObject();
            o.addProperty("playerId", memberId);
            o.addProperty("name", m != null ? m.getName() : "未知");
            members.add(o);
        }
        data.add("members", members);
        return Response.ok(GameMessage.restOk("团队创建成功", data).toString()).build();
    }

    @POST
    @Path("/team/invite")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.team.manage")
    public Response invitePlayer(String body) {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        JsonObject json = gson.fromJson(body, JsonObject.class);
        long targetId = json.has("targetPlayerId") ? json.get("targetPlayerId").getAsLong() : 0;

        if (targetId == 0) {
            return Response.ok(GameMessage.restError(GameErrorCode.PARAM_MISSING).toString()).build();
        }

        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        TeamService teamService = TeamService.getInstance();
        boolean success = teamService.invitePlayer(player.getId(), targetId);
        if (!success) {
            return Response.ok(GameMessage.restError(400, "邀请失败，可能你不是队长或团队已满").toString()).build();
        }

        return Response.ok(GameMessage.restOk("邀请已发送给【" + target.getName() + "】", null).toString()).build();
    }

    @POST
    @Path("/team/accept")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.team.manage")
    public Response acceptInvite() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        TeamService teamService = TeamService.getInstance();
        TeamService.Team team = teamService.acceptInvite(player.getId());
        if (team == null) {
            return Response.ok(GameMessage.restError(400, "没有待接受的邀请").toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("teamId", team.getTeamId());
        data.addProperty("leaderId", team.getLeaderId());
        JsonArray members = new JsonArray();
        for (long memberId : team.getMemberIds()) {
            PlayerInfo m = playerService.getPlayerInfoById(memberId);
            JsonObject o = new JsonObject();
            o.addProperty("playerId", memberId);
            o.addProperty("name", m != null ? m.getName() : "未知");
            members.add(o);
        }
        data.add("members", members);
        return Response.ok(GameMessage.restOk("成功加入团队", data).toString()).build();
    }

    @POST
    @Path("/team/leave")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.team.manage")
    public Response leaveTeam() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        TeamService teamService = TeamService.getInstance();
        boolean success = teamService.leaveTeam(player.getId());
        if (!success) {
            return Response.ok(GameMessage.restError(400, "你不在任何团队中").toString()).build();
        }

        return Response.ok(GameMessage.restOk("已离开团队", null).toString()).build();
    }

    @GET
    @Path("/team/info")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.team.view")
    public Response getTeamInfo() {
        Long userId = getCurrentUserId();
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            return Response.ok(GameMessage.restError(GameErrorCode.PLAYER_NOT_FOUND).toString()).build();
        }

        TeamService teamService = TeamService.getInstance();
        TeamService.Team team = teamService.getTeam(player.getId());
        if (team == null) {
            return Response.ok(GameMessage.restOk("你不在任何团队中", null).toString()).build();
        }

        JsonObject data = new JsonObject();
        data.addProperty("teamId", team.getTeamId());
        data.addProperty("leaderId", team.getLeaderId());
        PlayerInfo leader = playerService.getPlayerInfoById(team.getLeaderId());
        data.addProperty("leaderName", leader != null ? leader.getName() : "未知");
        JsonArray members = new JsonArray();
        for (long memberId : team.getMemberIds()) {
            PlayerInfo m = playerService.getPlayerInfoById(memberId);
            JsonObject o = new JsonObject();
            o.addProperty("playerId", memberId);
            o.addProperty("name", m != null ? m.getName() : "未知");
            o.addProperty("isLeader", memberId == team.getLeaderId());
            members.add(o);
        }
        data.add("members", members);
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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
        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

    // ==================== Buff 系统 ====================

    @GET
    @Path("/buff")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getPlayerBuffs() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        BuffService buffService = new BuffService();
        Map<String, Object> result = buffService.getActiveBuffs(playerId);

        JsonObject data = new JsonObject();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buffs = (List<Map<String, Object>>) result.get("buffs");
        int count = (int) result.get("count");

        JsonArray buffArray = new JsonArray();
        for (Map<String, Object> buff : buffs) {
            JsonObject o = new JsonObject();
            o.addProperty("id", (String) buff.get("id"));
            o.addProperty("attackBonus", (int) buff.get("attackBonus"));
            o.addProperty("defenseBonus", (int) buff.get("defenseBonus"));
            o.addProperty("speedBonus", (int) buff.get("speedBonus"));
            o.addProperty("spiritBonus", (int) buff.get("spiritBonus"));
            o.addProperty("remainingSeconds", (long) buff.get("remainingSeconds"));
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

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
    }

    // ==================== 种田系统 ====================

    @GET
    @Path("/farm/plots")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.player.info")
    public Response getFarmPlots() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);

        FarmService farmService = new FarmService();
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

        Season currentSeason = Season.getCurrentSeason();
        JsonObject data = new JsonObject();
        data.add("plots", plotArray);
        data.addProperty("count", plotArray.size());
        data.addProperty("currentSeason", currentSeason.name());
        data.addProperty("currentSeasonDisplay", currentSeason.getDisplayName());
        data.addProperty("currentSeasonDesc", currentSeason.getDescription());

        return Response.ok(GameMessage.restOk("获取成功", data).toString()).build();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
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

        FarmService farmService = new FarmService();
        Map<String, Object> result = farmService.usePesticide(playerId, plotIndex);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return Response.ok(GameMessage.restOk((String) result.get("message"), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, (String) result.get("message")).toString()).build();
    }
}
