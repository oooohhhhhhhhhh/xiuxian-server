package com.mtxgdn.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.PlayerInfo;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.DEFAULT_RANKING_LIMIT;
import static com.mtxgdn.websocket.WsSupport.MAX_RANKING_LIMIT;
import static com.mtxgdn.websocket.WsSupport.clampLimit;
import static com.mtxgdn.websocket.WsSupport.friendService;
import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.playerService;

/**
 * WebSocket · 社交域处理器：排行榜与好友关系（申请/接受/拒绝/删除/列表/待处理）。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里 {@code rank} 与六个 {@code friend_*}
 * action 的处理函数整块搬到这里。</p>
 *
 * <p><b>为什么把 rank 放进社交域</b>：排行榜读的是玩家公开字段（名字/境界/战力/财富），
 * 好友列表也读玩家公开字段，两者都不写玩法状态、都只依赖
 * {@code playerService}（好友额外用 {@code friendService}）。{@code rank} 单独成类会
 * 只剩一个方法，而它与好友域共享"按玩家维度展示"的依赖集合，放一起更贴近
 * 拆分前的分组意图（原文件里 {@code rank} 就紧挨着 {@code friend_*} 之前）。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 排行榜的 {@code limit} 仍然先过 {@link WsSupport#clampLimit}（默认 20、上限 100）
 * 再进 SQL —— 这条防全表扫描的约束随方法一起搬过来，常量也搬到了
 * {@code WsSupport}，取值不变；排行榜项里的 {@code rank} 自增序号、
 * {@code type} 回显字段、好友通知（{@code friend_pending}）的推送结构、
 * 以及"先给目标推通知再给请求方回执"的次序都没动。</p>
 */
final class WsSocialHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("rank", "friend_add", "friend_accept", "friend_reject",
            "friend_remove", "friend_list", "friend_pending");

    WsSocialHandler(WsContext context) {
        super(context);
    }

    void handleRanking(WebSocket socket, long msgId, JsonObject data) {
        String type = data != null && data.has("type") ? data.get("type").getAsString() : "realm";
        // limit 直接来自客户端并被拼进 SQL 的 LIMIT ?：原来不做任何校验，
        // 传 2000000000 就会让数据库把整张 players 表全查出来再逐个建对象
        // （负数在 SQLite 里等价于"不限条数"，同样是全表）。
        // 排行榜本来就只有前几十名有意义，这里夹到 [1, MAX_RANKING_LIMIT]。
        int limit = clampLimit(data != null && data.has("limit") ? data.get("limit").getAsInt() : 20,
                DEFAULT_RANKING_LIMIT, MAX_RANKING_LIMIT);

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

    void handleFriendAdd(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleFriendAccept(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleFriendReject(WebSocket socket, long msgId, Long userId, JsonObject data) {
        if (data == null || !data.has("requesterPlayerId")) {
            socket.send(GameMessage.error(msgId, "friend_reject", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long requesterId = data.get("requesterPlayerId").getAsLong();

        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "friend_reject", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }

        boolean success = friendService.rejectRequest(player.getId(), requesterId);
        if (!success) {
            socket.send(GameMessage.error(msgId, "friend_reject", GameErrorCode.FRIEND_REQUEST_NOT_FOUND).toJson());
            return;
        }

        socket.send(GameMessage.ok(msgId, "friend_reject", "已拒绝该好友申请", null).toJson());
    }

    void handleFriendRemove(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleFriendList(WebSocket socket, long msgId, Long userId) {
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

    void handleFriendPending(WebSocket socket, long msgId, Long userId) {
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
}
