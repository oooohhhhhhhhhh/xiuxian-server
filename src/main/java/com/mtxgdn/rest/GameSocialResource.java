package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.ChatMessage;
import com.mtxgdn.game.entity.Friend;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.ChatService;
import com.mtxgdn.game.service.FriendService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.RateLimiter;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.actionLog;
import static com.mtxgdn.rest.GameSupport.playerService;

/**
 * 玩家侧 · 聊天与好友接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：世界频道收发、私聊收发、
 * 好友列表/待处理申请/添加/接受/拒绝/删除。聊天与好友共享同一份"玩家身份解析 + 动作限流"
 * 依赖（发消息与加好友都要限流），因此放在同一个类里。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /chat/world}、{@code /chat/private}、{@code /chat/private/{targetPlayerId}}、
 * {@code /friend/list}、{@code /friend/pending}、{@code /friend/add}、
 * {@code /friend/accept}、{@code /friend/reject}、{@code /friend/remove}）逐字照搬，
 * 路径模板参数名未改；权限码 {@code game.chat.world} / {@code game.chat.private} /
 * {@code game.friend.manage} 全部保持原样，返回结构未动。</p>
 *
 * <p>发送类端点的动作限流（{@code checkActionRateLimit}）需要请求上下文，无法静态化，
 * 本类保留一份与拆分前逐字相同的副本。</p>
 */
@Path("/game")
public class GameSocialResource {

    private static final ChatService chatService = ServiceRegistry.getChatService();

    private static final FriendService friendService = ServiceRegistry.getFriendService();

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.sent"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.sent"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("friend.request_sent"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("friend.added"), null).toString()).build();
    }

    @POST
    @Path("/friend/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.friend.manage")
    public Response rejectFriendRequest(String body) {
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

        boolean success = friendService.rejectRequest(player.getId(), requesterId);
        if (!success) {
            return Response.ok(GameMessage.restError(GameErrorCode.FRIEND_REQUEST_NOT_FOUND).toString()).build();
        }
        return Response.ok(GameMessage.restOk(I18n.t("friend.rejected"), null).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("friend.removed"), null).toString()).build();
    }
}
