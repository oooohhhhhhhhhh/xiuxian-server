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
import com.mtxgdn.game.entity.Recipe;
import com.mtxgdn.game.entity.Technique;
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
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.EnhanceService;
import com.mtxgdn.game.service.ChatService;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.game.service.SectService;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.PlayerActionLogger;
import com.mtxgdn.util.RateLimiter;
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
    private static final TechniqueService techniqueService = new TechniqueService();
    private static final CraftingService craftingService = new CraftingService();
    private static final EnhanceService enhanceService = new EnhanceService();
    private static final ChatService chatService = new ChatService();
    private static final FriendService friendService = new FriendService();
    private static final SectService sectService = new SectService();

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

        String rateKey = "ws:" + userId + ":" + type;
        if (!RateLimiter.allow(rateKey, 20, 60)) {
            socket.send(GameMessage.error(msgId, type, GameErrorCode.RATE_LIMITED).toJson());
            return;
        }

        switch (type) {
            case "chat":
                handleChat(socket, msgId, userId, data);
                break;
            case "chat_private":
                if (!checkWsPermission(socket, msgId, userId, "game.chat.private")) break;
                handlePrivateChat(socket, msgId, userId, data);
                break;
            case "chat_history":
                if (!checkWsPermission(socket, msgId, userId, "game.chat.world")) break;
                handleChatHistory(socket, msgId, data);
                break;
            case "rank":
                if (!checkWsPermission(socket, msgId, userId, "game.rank.view")) break;
                handleRanking(socket, msgId, data);
                break;
            case "friend_add":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                handleFriendAdd(socket, msgId, userId, data);
                break;
            case "friend_accept":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                handleFriendAccept(socket, msgId, userId, data);
                break;
            case "friend_remove":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                handleFriendRemove(socket, msgId, userId, data);
                break;
            case "friend_list":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                handleFriendList(socket, msgId, userId);
                break;
            case "friend_pending":
                if (!checkWsPermission(socket, msgId, userId, "game.friend.manage")) break;
                handleFriendPending(socket, msgId, userId);
                break;
            case "heal":
                if (!checkWsPermission(socket, msgId, userId, "game.player.info")) break;
                handleHeal(socket, msgId, userId);
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
            case "techniques":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                handleTechniques(socket, msgId);
                break;
            case "my_techniques":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                handleMyTechniques(socket, msgId, userId);
                break;
            case "technique_learn":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.learn")) break;
                handleTechniqueLearn(socket, msgId, userId, data);
                break;
            case "technique_equip":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.equip")) break;
                handleTechniqueEquip(socket, msgId, userId, data);
                break;
            case "technique_unequip":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.equip")) break;
                handleTechniqueUnequip(socket, msgId, userId, data);
                break;
            case "technique_upgrade":
                if (!checkWsPermission(socket, msgId, userId, "game.technique.upgrade")) break;
                handleTechniqueUpgrade(socket, msgId, userId, data);
                break;
            case "crafting_recipes":
                if (!checkWsPermission(socket, msgId, userId, "game.crafting.recipes")) break;
                handleCraftingRecipes(socket, msgId, data);
                break;
            case "crafting_craft":
                if (!checkWsPermission(socket, msgId, userId, "game.crafting.craft")) break;
                handleCraftingCraft(socket, msgId, userId, data);
                break;
            case "equipment_enhance":
                if (!checkWsPermission(socket, msgId, userId, "game.equipment.enhance")) break;
                handleEquipmentEnhance(socket, msgId, userId, data);
                break;
            case "sect_list":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectList(socket, msgId);
                break;
            case "sect_info":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectInfo(socket, msgId, userId, data);
                break;
            case "sect_members":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectMembers(socket, msgId, userId);
                break;
            case "sect_create":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectCreate(socket, msgId, userId, data);
                break;
            case "sect_join":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectJoin(socket, msgId, userId, data);
                break;
            case "sect_applications":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectApplications(socket, msgId, userId);
                break;
            case "sect_approve":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectApprove(socket, msgId, userId, data);
                break;
            case "sect_reject":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectReject(socket, msgId, userId, data);
                break;
            case "sect_leave":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectLeave(socket, msgId, userId);
                break;
            case "sect_kick":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectKick(socket, msgId, userId, data);
                break;
            case "sect_appoint":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectAppoint(socket, msgId, userId, data);
                break;
            case "sect_warehouse":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectWarehouse(socket, msgId, userId);
                break;
            case "sect_donate":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.donate")) break;
                handleSectDonate(socket, msgId, userId, data);
                break;
            case "sect_take":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.warehouse")) break;
                handleSectTake(socket, msgId, userId, data);
                break;
            case "sect_disband":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectDisband(socket, msgId, userId);
                break;
            case "sect_top":
                if (!checkWsPermission(socket, msgId, userId, "game.sect.manage")) break;
                handleSectTop(socket, msgId);
                break;
            default:
                GameMessage err = GameMessage.error(msgId, type, GameErrorCode.UNKNOWN_TYPE);
                socket.send(err.toJson());
                break;
        }
    }

    private void handleChat(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null) return;
        String content = data.has("content") ? data.get("content").getAsString() : "";
        if (content.isEmpty()) {
            socket.send(GameMessage.error(msgId, "chat", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        String name = player != null ? player.getName() : "未知玩家";

        ChatMessage msg = chatService.sendWorldMessage(player.getId(), name, content);
        if (msg == null) {
            socket.send(GameMessage.error(msgId, "chat", GameErrorCode.PARAM_INVALID).toJson());
            return;
        }

        JsonObject chatData = new JsonObject();
        chatData.addProperty("senderPlayerId", player.getId());
        chatData.addProperty("senderName", name);
        chatData.addProperty("content", content);
        chatData.addProperty("timestamp", System.currentTimeMillis());

        GameMessage chatMessage = GameMessage.ok(0, "chat", chatData);
        broadcast(chatMessage);

        socket.send(GameMessage.ok(msgId, "chat", "发送成功", chatData).toJson());
        actionLog.logChat(userId, name, content);
    }

    private void handlePrivateChat(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null) return;
        String content = data.has("content") ? data.get("content").getAsString() : "";
        long targetId = data.has("targetPlayerId") ? data.get("targetPlayerId").getAsLong() : 0;

        if (targetId == 0 || content.trim().isEmpty()) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (targetId == player.getId()) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.CHAT_SELF_MESSAGE).toJson());
            return;
        }

        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            socket.send(GameMessage.error(msgId, "chat_private", GameErrorCode.CHAT_RECEIVER_NOT_FOUND).toJson());
            return;
        }

        chatService.sendPrivateMessage(player.getId(), player.getName(), targetId, content);
        actionLog.logChat(userId, player.getName(), "[私聊→" + target.getName() + "] " + content);

        JsonObject chatData = new JsonObject();
        chatData.addProperty("fromUserId", userId);
        chatData.addProperty("senderPlayerId", player.getId());
        chatData.addProperty("senderName", player.getName());
        chatData.addProperty("receiverPlayerId", targetId);
        chatData.addProperty("receiverName", target.getName());
        chatData.addProperty("content", content);
        chatData.addProperty("timestamp", System.currentTimeMillis());

        GameMessage chatMessage = GameMessage.ok(0, "chat_private", chatData);
        sendToUser((long) target.getUserId(), chatMessage);

        socket.send(GameMessage.ok(msgId, "chat_private", "发送成功", chatData).toJson());
    }

    private void handleChatHistory(WebSocket socket, long msgId, JsonObject data) {
        int limit = data != null && data.has("limit") ? data.get("limit").getAsInt() : 50;
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
        JsonObject respData = new JsonObject();
        respData.add("messages", arr);
        socket.send(GameMessage.ok(msgId, "chat_history", respData).toJson());
    }

    private void handleRanking(WebSocket socket, long msgId, JsonObject data) {
        String type = data != null && data.has("type") ? data.get("type").getAsString() : "realm";
        int limit = data != null && data.has("limit") ? data.get("limit").getAsInt() : 20;

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
            arr.add(o);
        }
        JsonObject respData = new JsonObject();
        respData.add("ranking", arr);
        respData.addProperty("type", type);
        socket.send(GameMessage.ok(msgId, "rank", respData).toJson());
    }

    private void handleFriendAdd(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("targetPlayerId")) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long targetId = data.get("targetPlayerId").getAsLong();

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (targetId == player.getId()) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.FRIEND_SELF_TARGET).toJson());
            return;
        }

        PlayerInfo target = playerService.getPlayerInfoById(targetId);
        if (target == null) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }

        Friend result = friendService.sendRequest(player.getId(), targetId);
        if (result == null) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if ("exists".equals(result.getStatus())) {
            socket.send(GameMessage.error(msgId, "friend_add", GameErrorCode.FRIEND_ALREADY_EXISTS).toJson());
            return;
        }

        JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
        socket.send(GameMessage.ok(msgId, "friend_add", "好友申请已发送", respData).toJson());

        JsonObject notifyData = new JsonObject();
        notifyData.addProperty("requesterPlayerId", player.getId());
        notifyData.addProperty("requesterName", player.getName());
        sendToUser((long) target.getUserId(), GameMessage.ok(0, "friend_pending", notifyData));
    }

    private void handleFriendAccept(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("requesterPlayerId")) {
            socket.send(GameMessage.error(msgId, "friend_accept", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long requesterId = data.get("requesterPlayerId").getAsLong();

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_accept", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }

        boolean success = friendService.acceptRequest(player.getId(), requesterId);
        if (!success) {
            socket.send(GameMessage.error(msgId, "friend_accept", GameErrorCode.FRIEND_REQUEST_NOT_FOUND).toJson());
            return;
        }

        socket.send(GameMessage.ok(msgId, "friend_accept", "已添加好友", null).toJson());
    }

    private void handleFriendRemove(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("friendPlayerId")) {
            socket.send(GameMessage.error(msgId, "friend_remove", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long friendId = data.get("friendPlayerId").getAsLong();

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_remove", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }

        boolean success = friendService.removeFriend(player.getId(), friendId);
        if (!success) {
            socket.send(GameMessage.error(msgId, "friend_remove", GameErrorCode.FRIEND_NOT_FOUND).toJson());
            return;
        }

        socket.send(GameMessage.ok(msgId, "friend_remove", "已删除好友", null).toJson());
    }

    private void handleFriendList(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_list", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
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
        JsonObject respData = new JsonObject();
        respData.add("friends", arr);
        socket.send(GameMessage.ok(msgId, "friend_list", respData).toJson());
    }

    private void handleFriendPending(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_pending", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
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
        JsonObject respData = new JsonObject();
        respData.add("requests", arr);
        socket.send(GameMessage.ok(msgId, "friend_pending", respData).toJson());
    }

    private void handleHeal(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "heal", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = playerService.healPlayer(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject data = gson.toJsonTree(result).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "heal", (String) result.get("message"), data).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "heal", 400, (String) result.get("message")).toJson());
        }
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

            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null) {
                JsonObject rootObj = new JsonObject();
                rootObj.addProperty("key", root.name());
                rootObj.addProperty("displayName", root.getDisplayName());
                rootObj.addProperty("description", root.getDescription());
                rootObj.addProperty("tier", root.getTier().getDisplayName());
                rootObj.addProperty("attackBonus", root.getAttackBonus());
                rootObj.addProperty("hpBonus", root.getHpBonus());
                rootObj.addProperty("mpBonus", root.getMpBonus());
                rootObj.addProperty("spiritBonus", root.getSpiritBonus());
                rootObj.addProperty("defenseBonus", root.getDefenseBonus());
                rootObj.addProperty("speedBonus", root.getSpeedBonus());
                rootObj.addProperty("effectName", root.getEffect().name());
                rootObj.addProperty("effectValue", root.getEffectValue());
                data.add("spiritualRoot", rootObj);
            }

            long spiritStones = itemService.getSpiritStoneCount(player.getId());
            data.addProperty("spiritStones", spiritStones);
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

    private void handleTechniques(WebSocket socket, long msgId) {
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
        socket.send(GameMessage.ok(msgId, "techniques", data).toJson());
    }

    private void handleMyTechniques(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "my_techniques", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
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
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("techniques", arr);
        socket.send(GameMessage.ok(msgId, "my_techniques", data).toJson());
    }

    private void handleTechniqueLearn(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_learn", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_learn", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.learnTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "technique_learn", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_learn", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleTechniqueEquip(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_equip", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_equip", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.equipTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "technique_equip", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_equip", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleTechniqueUnequip(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_unequip", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_unequip", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.unequipTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "technique_unequip", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_unequip", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleTechniqueUpgrade(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("techniqueId")) {
            socket.send(GameMessage.error(msgId, "technique_upgrade", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "technique_upgrade", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long techniqueId = data.get("techniqueId").getAsLong();
        Map<String, Object> result = techniqueService.upgradeTechnique(player.getId(), techniqueId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "technique_upgrade", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "technique_upgrade", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleCraftingRecipes(WebSocket socket, long msgId, JsonObject data) {
        List<Recipe> recipes;
        if (data != null && data.has("category")) {
            try {
                recipes = craftingService.getRecipesByCategory(Recipe.Category.valueOf(data.get("category").getAsString().toUpperCase()));
            } catch (IllegalArgumentException e) {
                socket.send(GameMessage.error(msgId, "crafting_recipes", 400, "无效的分类").toJson());
                return;
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
        JsonObject respData = new JsonObject();
        respData.add("recipes", arr);
        socket.send(GameMessage.ok(msgId, "crafting_recipes", respData).toJson());
    }

    private void handleCraftingCraft(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("recipeId")) {
            socket.send(GameMessage.error(msgId, "crafting_craft", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "crafting_craft", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long recipeId = data.get("recipeId").getAsLong();
        Map<String, Object> result = craftingService.craft(player.getId(), recipeId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject respData = new JsonObject();
            respData.addProperty("message", (String) result.get("message"));
            if (result.containsKey("craftSuccess")) respData.addProperty("craftSuccess", (boolean) result.get("craftSuccess"));
            if (result.containsKey("expGained")) respData.addProperty("expGained", ((Number) result.get("expGained")).longValue());
            if (result.containsKey("itemGained")) respData.addProperty("itemGained", (String) result.get("itemGained"));
            if (result.containsKey("itemQuantity")) respData.addProperty("itemQuantity", ((Number) result.get("itemQuantity")).intValue());
            socket.send(GameMessage.ok(msgId, "crafting_craft", (String) result.get("message"), respData).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "crafting_craft", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleEquipmentEnhance(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("slot")) {
            socket.send(GameMessage.error(msgId, "equipment_enhance", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "equipment_enhance", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        String slot = data.get("slot").getAsString();
        Map<String, Object> result = enhanceService.enhanceItem(player.getId(), slot);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject respData = gson.toJsonTree(result).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "equipment_enhance", (String) result.get("message"), respData).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "equipment_enhance", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectList(WebSocket socket, long msgId) {
        List<Sect> sects = sectService.getAllSects();
        JsonArray arr = new JsonArray();
        for (Sect s : sects) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.getId());
            o.addProperty("name", s.getName());
            o.addProperty("description", s.getDescription());
            o.addProperty("leaderPlayerId", s.getLeaderPlayerId());
            o.addProperty("leaderName", s.getLeaderName());
            o.addProperty("level", s.getLevel());
            o.addProperty("prestige", s.getPrestige());
            o.addProperty("memberCount", s.getMemberCount());
            o.addProperty("maxMembers", Sect.getMaxMembersForLevel(s.getLevel()));
            arr.add(o);
        }
        JsonObject data = new JsonObject();
        data.add("sects", arr);
        socket.send(GameMessage.ok(msgId, "sect_list", data).toJson());
    }

    private void handleSectInfo(WebSocket socket, long msgId, Long userId, JsonObject data) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_info", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Sect sect;
        if (data != null && data.has("sectId")) {
            sect = sectService.getSectById(data.get("sectId").getAsLong());
        } else {
            sect = sectService.getPlayerSect(player.getId());
        }
        if (sect == null) {
            socket.send(GameMessage.ok(msgId, "sect_info", "尚未加入宗门", null).toJson());
            return;
        }
        JsonObject resp = gson.toJsonTree(sect).getAsJsonObject();
        resp.addProperty("maxMembers", Sect.getMaxMembersForLevel(sect.getLevel()));
        SectMember me = sectService.getMember(sect.getId(), player.getId());
        if (me != null) {
            resp.addProperty("myRole", me.getRole());
            resp.addProperty("myRoleDisplay", SectMember.getRoleDisplayName(me.getRole()));
            resp.addProperty("myContribution", me.getContribution());
        }
        socket.send(GameMessage.ok(msgId, "sect_info", resp).toJson());
    }

    private void handleSectMembers(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_members", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Sect sect = sectService.getPlayerSect(player.getId());
        if (sect == null) {
            socket.send(GameMessage.error(msgId, "sect_members", 400, "你还没有加入宗门").toJson());
            return;
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
        JsonObject resp = new JsonObject();
        resp.add("members", arr);
        resp.addProperty("sectName", sect.getName());
        socket.send(GameMessage.ok(msgId, "sect_members", resp).toJson());
    }

    private void handleSectCreate(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("name")) {
            socket.send(GameMessage.error(msgId, "sect_create", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_create", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        String name = data.get("name").getAsString();
        String desc = data.has("description") ? data.get("description").getAsString() : "";
        Map<String, Object> result = sectService.createSect(player.getId(), name, desc);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject resp = gson.toJsonTree(result.get("sect")).getAsJsonObject();
            socket.send(GameMessage.ok(msgId, "sect_create", (String) result.get("message"), resp).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_create", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectJoin(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("sectId")) {
            socket.send(GameMessage.error(msgId, "sect_join", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_join", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long sectId = data.get("sectId").getAsLong();
        Map<String, Object> result = sectService.applyToSect(player.getId(), sectId, "");
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_join", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_join", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectApplications(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_applications", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        SectMember member = sectService.getPlayerMember(player.getId());
        if (member == null) {
            socket.send(GameMessage.error(msgId, "sect_applications", 400, "你还没有加入宗门").toJson());
            return;
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
        JsonObject resp = new JsonObject();
        resp.add("applications", arr);
        socket.send(GameMessage.ok(msgId, "sect_applications", resp).toJson());
    }

    private void handleSectApprove(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("appId")) {
            socket.send(GameMessage.error(msgId, "sect_approve", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_approve", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long appId = data.get("appId").getAsLong();
        Map<String, Object> result = sectService.approveApplication(player.getId(), appId, true);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_approve", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_approve", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectReject(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("appId")) {
            socket.send(GameMessage.error(msgId, "sect_reject", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_reject", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long appId = data.get("appId").getAsLong();
        Map<String, Object> result = sectService.approveApplication(player.getId(), appId, false);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_reject", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_reject", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectLeave(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_leave", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = sectService.leaveSect(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_leave", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_leave", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectKick(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("targetPlayerId")) {
            socket.send(GameMessage.error(msgId, "sect_kick", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_kick", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long targetId = data.get("targetPlayerId").getAsLong();
        Map<String, Object> result = sectService.kickMember(player.getId(), targetId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_kick", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_kick", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectAppoint(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("targetPlayerId") || !data.has("role")) {
            socket.send(GameMessage.error(msgId, "sect_appoint", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_appoint", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        long targetId = data.get("targetPlayerId").getAsLong();
        String role = data.get("role").getAsString();
        Map<String, Object> result = sectService.appointMember(player.getId(), targetId, role);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_appoint", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_appoint", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectWarehouse(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_warehouse", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        SectMember member = sectService.getPlayerMember(player.getId());
        if (member == null) {
            socket.send(GameMessage.error(msgId, "sect_warehouse", 400, "你还没有加入宗门").toJson());
            return;
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
            arr.add(o);
        }
        JsonObject resp = new JsonObject();
        resp.add("warehouse", arr);
        socket.send(GameMessage.ok(msgId, "sect_warehouse", resp).toJson());
    }

    private void handleSectDonate(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("itemKey") || !data.has("quantity")) {
            socket.send(GameMessage.error(msgId, "sect_donate", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_donate", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        SectMember member = sectService.getPlayerMember(player.getId());
        if (member == null) {
            socket.send(GameMessage.error(msgId, "sect_donate", 400, "你还没有加入宗门").toJson());
            return;
        }
        String itemKey = data.get("itemKey").getAsString();
        int quantity = data.get("quantity").getAsInt();
        Map<String, Object> result = sectService.donateToWarehouse(player.getId(), member.getSectId(), itemKey, quantity);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_donate", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_donate", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectTake(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("itemKey") || !data.has("quantity")) {
            socket.send(GameMessage.error(msgId, "sect_take", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_take", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        SectMember member = sectService.getPlayerMember(player.getId());
        if (member == null) {
            socket.send(GameMessage.error(msgId, "sect_take", 400, "你还没有加入宗门").toJson());
            return;
        }
        String itemKey = data.get("itemKey").getAsString();
        int quantity = data.get("quantity").getAsInt();
        Map<String, Object> result = sectService.withdrawFromWarehouse(player.getId(), member.getSectId(), itemKey, quantity);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_take", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_take", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectDisband(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_disband", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = sectService.disbandSect(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_disband", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_disband", 400, (String) result.get("message")).toJson());
        }
    }

    private void handleSectTop(WebSocket socket, long msgId) {
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
        JsonObject resp = new JsonObject();
        resp.add("top", arr);
        socket.send(GameMessage.ok(msgId, "sect_top", resp).toJson());
    }

    public void shutdownGracefully() {
        try {
            var sockets = new java.util.ArrayList<>(authenticated.keySet());
            authenticated.clear();
            sessionUsers.clear();
            userSessions.clear();
            for (WebSocket socket : sockets) {
                try {
                    socket.close(1001, "服务器关闭");
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            System.err.println("WebSocket 优雅关闭失败: " + e.getMessage());
        }
    }
}
