package com.mtxgdn.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.ExplorationService;
import com.mtxgdn.game.service.SecretRealmService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.OfflineRewardService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.PlayerActionLogger;
import org.glassfish.grizzly.websockets.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameWebSocketApp extends WebSocketApplication {

    private static final Gson gson = new Gson();
    private static final PlayerActionLogger actionLog = PlayerActionLogger.getInstance();
    private static final PlayerService playerService = new PlayerService();
    private static final RealmService realmService = new RealmService(playerService);
    private static final ItemService itemService = new ItemService();
    private static final SecretRealmService secretRealmService = new SecretRealmService(playerService);
    private static final ExplorationService explorationService = new ExplorationService(playerService);
    private static final HeartDemonService heartDemonService = new HeartDemonService();

    private final Map<WebSocket, Long> sessionUsers = new ConcurrentHashMap<>();
    private final Map<Long, WebSocket> userSessions = new ConcurrentHashMap<>();
    private final Map<WebSocket, Boolean> authenticated = new ConcurrentHashMap<>();

    @Override
    public void onConnect(WebSocket socket) {
        authenticated.put(socket, false);
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
        if (!authenticated.getOrDefault(socket, false)) {
            handleAuth(socket, text);
            return;
        }

        Long userId = sessionUsers.get(socket);
        if (userId == null) {
            return;
        }

        try {
            GameMessage req = GameMessage.fromJson(text);
            handleGameMessage(socket, userId, req);
        } catch (Exception e) {
            GameMessage err = GameMessage.error(0, "error", GameErrorCode.MESSAGE_PARSE_ERROR);
            socket.send(err.toJson());
        }
    }

    private void handleAuth(WebSocket socket, String text) {
        try {
            GameMessage gameMessage = GameMessage.fromJson(text);
            if (!"auth".equals(gameMessage.getType())) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.AUTH_NOT_LOGGED_IN);
                socket.send(err.toJson());
                socket.close(4001, GameErrorCode.AUTH_NOT_LOGGED_IN.getMessage());
                return;
            }

            JsonObject data = gameMessage.getData();
            if (data == null || !data.has("token")) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.PARAM_MISSING);
                socket.send(err.toJson());
                socket.close(4001, "缺少认证token");
                return;
            }

            String token = data.get("token").getAsString();
            if (!JwtUtil.validateToken(token)) {
                GameMessage err = GameMessage.error(0, "error", GameErrorCode.AUTH_INVALID_TOKEN);
                socket.send(err.toJson());
                socket.close(4001, GameErrorCode.AUTH_INVALID_TOKEN.getMessage());
                return;
            }

            Long userId = JwtUtil.extractUserId(token);
            String username = JwtUtil.extractUsername(token);

            sessionUsers.put(socket, userId);
            userSessions.put(userId, socket);
            authenticated.put(socket, true);

            actionLog.logConnect(userId, username);

            OfflineRewardService.OfflineRewardResult offlineReward =
                    new OfflineRewardService().processOfflineRewards(userId);

            JsonObject welcomeData = new JsonObject();
            welcomeData.addProperty("userId", userId);
            welcomeData.addProperty("username", username);

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
                welcomeData.add("offlineReward", offlineJson);
            }
            welcomeData.addProperty("message", "连接成功，欢迎进入修仙世界！");

            GameMessage welcome = GameMessage.ok(0, "welcome", welcomeData);
            socket.send(welcome.toJson());

            broadcastUserOnline(userId, username);
        } catch (Exception e) {
            GameMessage err = GameMessage.error(0, "error", GameErrorCode.MESSAGE_PARSE_ERROR);
            socket.send(err.toJson());
            socket.close(4001, GameErrorCode.MESSAGE_PARSE_ERROR.getMessage());
        }
    }

    @Override
    public void onClose(WebSocket socket, DataFrame frame) {
        Long userId = sessionUsers.remove(socket);
        authenticated.remove(socket);
        if (userId != null) {
            userSessions.remove(userId);
            broadcastUserOffline(userId);
            PlayerInfo player = playerService.getPlayerByUserId(userId);
            if (player != null) {
                playerService.updateLastOfflineTime(player.getId(), System.currentTimeMillis());
            }
            String name = player != null ? player.getName() : "未知玩家";
            actionLog.logDisconnect(userId, name);
        }
    }

    private void handleGameMessage(WebSocket socket, Long userId, GameMessage req) {
        String type = req.getType();
        long msgId = req.getMsgId();
        JsonObject data = req.getData();

        switch (type) {
            case "chat":
                handleChat(msgId, userId, data);
                break;
            case "player_info":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                handlePlayerInfo(socket, msgId, userId);
                break;
            case "cultivate_start":
                if (!checkWsPermission(socket, msgId, userId, "game.cultivate")) break;
                handleCultivateStart(socket, msgId, userId);
                break;
            case "cultivate_stop":
                if (!checkWsPermission(socket, msgId, userId, "game.cultivate")) break;
                handleCultivateStop(socket, msgId, userId, data);
                break;
            case "breakthrough":
                if (!checkWsPermission(socket, msgId, userId, "game.realm.breakthrough")) break;
                handleBreakthrough(socket, msgId, userId);
                break;
            case "inventory":
                if (!checkWsPermission(socket, msgId, userId, "game.inventory.view")) break;
                handleInventory(socket, msgId, userId);
                break;
            case "item_use":
                if (!checkWsPermission(socket, msgId, userId, "game.item.use")) break;
                handleItemUse(socket, msgId, userId, data);
                break;
            case "item_registry":
                if (!checkWsPermission(socket, msgId, userId, "game.item.registry")) break;
                handleItemRegistry(socket, msgId);
                break;
            case "heartbeat":
                JsonObject pongData = new JsonObject();
                pongData.addProperty("timestamp", System.currentTimeMillis());
                GameMessage pong = GameMessage.ok(msgId, "pong", pongData);
                socket.send(pong.toJson());
                break;
            case "secret_realm_areas":
                if (!checkWsPermission(socket, msgId, userId, "game.secret_realm")) break;
                handleSecretRealmAreas(socket, msgId, userId);
                break;
            case "secret_realm_enter":
                if (!checkWsPermission(socket, msgId, userId, "game.secret_realm")) break;
                handleSecretRealmEnter(socket, msgId, userId, data);
                break;
            case "exploration":
                if (!checkWsPermission(socket, msgId, userId, "game.explore")) break;
                handleExploration(socket, msgId, userId);
                break;
            default:
                GameMessage err = GameMessage.error(msgId, type, GameErrorCode.UNKNOWN_TYPE);
                socket.send(err.toJson());
                break;
        }
    }

    private void handleChat(long msgId, Long userId, JsonObject data) {
        if (data == null) return;
        String content = data.has("content") ? data.get("content").getAsString() : "";
        if (content.isEmpty()) return;

        JsonObject chatData = new JsonObject();
        chatData.addProperty("fromUserId", userId);
        chatData.addProperty("content", content);
        chatData.addProperty("timestamp", System.currentTimeMillis());

        GameMessage chatMessage = GameMessage.ok(0, "chat", chatData);
        broadcast(chatMessage);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        actionLog.logChat(userId, name, content);
    }

    private boolean checkWsPermission(WebSocket socket, long msgId, Long userId, String permission) {
        if (!PermissionService.hasPermission(userId, permission)) {
            GameMessage err = GameMessage.error(msgId, "error", GameErrorCode.PARAM_INVALID.getCode(), "权限不足: " + permission);
            socket.send(err.toJson());
            return false;
        }
        return true;
    }

    private void handlePlayerInfo(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        JsonObject data = new JsonObject();

        if (player != null) {
            data = gson.toJsonTree(player).getAsJsonObject();
        } else {
            data.addProperty("exists", false);
        }

        GameMessage response = GameMessage.ok(msgId, "player_info", data);
        socket.send(response.toJson());
    }

    private void handleCultivateStart(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "cultivate_start", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        playerService.setCultivating(player.getId(), true);

        actionLog.logCultivateStart(userId, player.getName(), player.getRealm());

        JsonObject data = new JsonObject();
        data.addProperty("cultivating", true);
        data.addProperty("cultivationPerSecond", getRealmCultivationPerSecond(player.getRealm()));

        GameMessage response = GameMessage.ok(msgId, "cultivate_start", "开始修炼", data);
        socket.send(response.toJson());
    }

    private void handleCultivateStop(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);

        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "cultivate_stop", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        int elapsedSeconds = reqData != null && reqData.has("elapsedSeconds") ? reqData.get("elapsedSeconds").getAsInt() : 0;
        int cultivationPerSec = getRealmCultivationPerSecond(player.getRealm());
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
        JsonObject respData = gson.toJsonTree(updated).getAsJsonObject();
        respData.addProperty("experienceGained", netExp);
        if (hdResult.triggered) {
            respData.addProperty("heartDemonTriggered", true);
            respData.addProperty("heartDemonSeverity", hdResult.severity);
            respData.addProperty("heartDemonNarrative", hdResult.narrative);
            respData.addProperty("heartDemonExpLost", hdResult.expLost);
        }

        String msg;
        if (hdResult.triggered) {
            msg = "修炼结束，" + hdResult.narrative;
        } else {
            msg = "停止修炼，获得了 " + netExp + " 经验";
        }

        GameMessage response = GameMessage.ok(msgId, "cultivate_stop", msg, respData);
        socket.send(response.toJson());
    }

    private void handleBreakthrough(WebSocket socket, long msgId, Long userId) {
        RealmBreakthroughResult btResult = realmService.tryBreakthrough(userId);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        actionLog.logBreakthrough(userId, name, btResult.isSuccess(), btResult.getMessage());

        JsonObject data = gson.toJsonTree(btResult).getAsJsonObject();

        if (btResult.isSuccess()) {
            PlayerInfo updated = playerService.getPlayerByUserId(userId);
            data.add("player", gson.toJsonTree(updated));

            GameMessage response = GameMessage.ok(msgId, "breakthrough", btResult.getMessage(), data);
            socket.send(response.toJson());
        } else {
            GameMessage response = GameMessage.ok(msgId, "breakthrough", btResult.getMessage(), data);
            socket.send(response.toJson());
        }
    }

    private void handleInventory(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "inventory", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        List<ItemService.InventoryEntry> inventory = itemService.getInventory((int) player.getId());

        JsonArray items = new JsonArray();
        for (ItemService.InventoryEntry entry : inventory) {
            JsonObject obj = gson.toJsonTree(entry.getItem()).getAsJsonObject();
            obj.addProperty("quantity", entry.getQuantity());
            items.add(obj);
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        GameMessage response = GameMessage.ok(msgId, "inventory", data);
        socket.send(response.toJson());
    }

    private void handleItemUse(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            GameMessage err = GameMessage.error(msgId, "item_use", GameErrorCode.PLAYER_NOT_FOUND);
            socket.send(err.toJson());
            return;
        }

        String itemKey = reqData != null && reqData.has("itemKey") ? reqData.get("itemKey").getAsString() : "";
        if (itemKey.isEmpty()) {
            GameMessage err = GameMessage.error(msgId, "item_use", GameErrorCode.PARAM_MISSING);
            socket.send(err.toJson());
            return;
        }

        Map<String, Object> useResult = itemService.useItem((int) player.getId(), itemKey);

        boolean success = (boolean) useResult.getOrDefault("success", false);
        String msg = (String) useResult.getOrDefault("message", "");
        actionLog.logItemUse(userId, player.getName(), itemKey, success, msg);

        JsonObject respData = gson.toJsonTree(useResult).getAsJsonObject();

        GameMessage response = success
                ? GameMessage.ok(msgId, "item_use", msg, respData)
                : GameMessage.error(msgId, "item_use", GameErrorCode.ITEM_USE_FAILED.getCode(), msg);
        socket.send(response.toJson());
    }

    private void handleItemRegistry(WebSocket socket, long msgId) {
        JsonArray items = new JsonArray();
        for (Item item : ItemRegistry.getAll()) {
            items.add(gson.toJsonTree(item));
        }

        JsonObject data = new JsonObject();
        data.add("items", items);
        data.addProperty("count", items.size());

        GameMessage response = GameMessage.ok(msgId, "item_registry", data);
        socket.send(response.toJson());
    }

    private int getRealmCultivationPerSecond(int realm) {
        double multiplier = com.mtxgdn.game.config.GameConfigLoader.getCultivationMultiplier(realm);
        int base = com.mtxgdn.game.config.GameConfigLoader.getCultivationBaseValue();
        return (int) (base * multiplier);
    }

    public void broadcast(GameMessage message) {
        String json = message.toJson();
        for (WebSocket socket : sessionUsers.keySet()) {
            if (socket.isConnected()) {
                socket.send(json);
            }
        }
    }

    public void sendToUser(Long userId, GameMessage message) {
        WebSocket socket = userSessions.get(userId);
        if (socket != null && socket.isConnected()) {
            socket.send(message.toJson());
        }
    }

    private void broadcastUserOnline(Long userId, String username) {
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("username", username);
        data.addProperty("online", true);

        GameMessage message = GameMessage.ok(0, "user_online", data);
        broadcast(message);
    }

    private void broadcastUserOffline(Long userId) {
        JsonObject data = new JsonObject();
        data.addProperty("userId", userId);
        data.addProperty("online", false);

        GameMessage message = GameMessage.ok(0, "user_offline", data);
        broadcast(message);
    }

    private void handleSecretRealmAreas(WebSocket socket, long msgId, Long userId) {
        List<SecretRealm> areas = secretRealmService.getAvailableAreas(userId);

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

        GameMessage response = GameMessage.ok(msgId, "secret_realm_areas", data);
        socket.send(response.toJson());
    }

    private void handleSecretRealmEnter(WebSocket socket, long msgId, Long userId, JsonObject reqData) {
        if (reqData == null || !reqData.has("area")) {
            GameMessage err = GameMessage.error(msgId, "secret_realm_enter", GameErrorCode.PARAM_MISSING);
            socket.send(err.toJson());
            return;
        }

        String area = reqData.get("area").getAsString();
        SecretRealmResult result = secretRealmService.enterSecretRealm(userId, area);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        actionLog.logSecretRealmEnter(userId, name, area, result.isSuccess(), result.getMessage());

        if (!result.isSuccess()) {
            GameMessage err = GameMessage.error(msgId, "secret_realm_enter", GameErrorCode.SECRET_REALM_NOT_FOUND.getCode(), result.getMessage());
            socket.send(err.toJson());
            return;
        }

        JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
        GameMessage response = GameMessage.ok(msgId, "secret_realm_enter", result.getMessage(), respData);
        socket.send(response.toJson());
    }

    private void handleExploration(WebSocket socket, long msgId, Long userId) {
        ExplorationResult result = explorationService.explore(userId);

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";
        String eventName = result.getEventType() != null ? result.getEventType() : "未知事件";
        actionLog.logExploration(userId, name, eventName, result.getMessage());

        if (!result.isSuccess()) {
            GameMessage err = GameMessage.error(msgId, "exploration", GameErrorCode.EXPLORATION_COOLDOWN.getCode(), result.getMessage());
            socket.send(err.toJson());
            return;
        }

        JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
        GameMessage response = GameMessage.ok(msgId, "exploration", result.getMessage(), respData);
        socket.send(response.toJson());
    }

    public int getOnlineCount() {
        return sessionUsers.size();
    }
}
