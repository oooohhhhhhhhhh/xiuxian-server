package com.mtxgdn.rest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.Main;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.CombatService;
import com.mtxgdn.game.service.DailyService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.PlayerActionLogger;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Path("/game")
public class GameResource {

    private static final Gson gson = new Gson();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();
    private static final PlayerService playerService = new PlayerService();
    private static final RealmService realmService = new RealmService(playerService);
    private static final ItemService itemService = new ItemService();
    private static final SkillService skillService = new SkillService();
    private static final CombatService combatService = new CombatService();
    private static final SecretRealmService secretRealmService = new SecretRealmService();
    private static final ExplorationService explorationService = new ExplorationService();
    private static final DailyService dailyService = new DailyService();
    private static final TradeService tradeService = new TradeService();
    private static final HeartDemonService heartDemonService = new HeartDemonService();

    @Context
    private ContainerRequestContext requestContext;

    private Long getCurrentUserId() {
        Object userId = requestContext.getProperty("userId");
        if (userId instanceof Long) {
            return (Long) userId;
        }
        throw new WebApplicationException("未登录", 401);
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

        JsonObject data = gson.toJsonTree(player).getAsJsonObject();

        RealmConfig current = GameConfigLoader.getRealmConfig(player.getRealm(), player.getSubRealm());
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
}
