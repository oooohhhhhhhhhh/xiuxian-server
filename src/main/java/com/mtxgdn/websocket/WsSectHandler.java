package com.mtxgdn.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.common.result.OperationResult;
import com.mtxgdn.game.entity.SectWarehouseItem;
import org.glassfish.grizzly.websockets.WebSocket;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.websocket.WsSupport.gson;
import static com.mtxgdn.websocket.WsSupport.playerService;
import static com.mtxgdn.websocket.WsSupport.sectService;

/**
 * WebSocket · 宗门域处理器：宗门列表/详情/成员、建宗、申请、审批、职位、仓库、
 * 捐献、退宗、踢人、解散、升级、转让、宗门战、宗门排行。
 *
 * <p><b>拆出哪一块</b>：{@code GameWebSocketApp} 里全部 19 个 {@code sect_*} action
 * 的处理函数整块搬到这里。原来它们是文件里最长的一段连续代码（约 400 行），
 * 也是这次拆分里唯一必须单独成类才不超行数的一块。</p>
 *
 * <p><b>为什么单独成域</b>：19 个动作共享同一份依赖集合 —— {@code sectService} 加上
 * "先把自己解析成 {@code PlayerInfo} / {@code SectMember} 再动手"的固定前缀。
 * 拆开成两个类反而会让"你还没有加入宗门"这类前置判定散落各处。</p>
 *
 * <p><b>协议不变的保证</b>：方法体逐字搬移，只改了可见性（{@code private} → 包级）。
 * 19 个响应 type、每个动作的字段名、失败时的两种既有风格
 * （业务失败 {@code 400 + message}、找不到角色 {@code PLAYER_NOT_FOUND}）、
 * {@code sect_info} 未入宗时返回 {@code ok + "尚未加入宗门" + null} 而不是错误、
 * 以及 {@code sect_war} 回包里的 {@code winner}/{@code attackerWins}/
 * {@code defenderWins}/{@code battleLog} 四字段，全部逐字保留。</p>
 *
 * <p><b>权限</b>：本类不做权限判断。19 个动作的权限码写在 {@code GameWebSocketApp}
 * 的分发 switch 里，其中 {@code sect_donate} 是 {@code game.sect.donate}、
 * {@code sect_take} 是 {@code game.sect.warehouse}，其余 17 个是
 * {@code game.sect.manage} —— 与拆分前一致。</p>
 */
final class WsSectHandler extends WsHandler {

    /** 本处理器负责的 action（与分发 switch 双向对齐，见 WsActionDispatchTest）。 */
    static final Set<String> ACTIONS = Set.of("sect_list", "sect_info", "sect_members", "sect_create",
            "sect_join", "sect_applications", "sect_approve", "sect_reject", "sect_leave", "sect_kick",
            "sect_appoint", "sect_warehouse", "sect_donate", "sect_take", "sect_disband", "sect_levelup",
            "sect_transfer", "sect_war", "sect_top");

    WsSectHandler(WsContext context) {
        super(context);
    }

    void handleSectList(WebSocket socket, long msgId) {
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

    void handleSectInfo(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleSectMembers(WebSocket socket, long msgId, Long userId) {
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

    void handleSectCreate(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleSectJoin(WebSocket socket, long msgId, Long userId, JsonObject data) {
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
        OperationResult result = sectService.applyToSect(player.getId(), sectId, "");
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_join", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_join", 400, result.message()).toJson());
        }
    }

    void handleSectApplications(WebSocket socket, long msgId, Long userId) {
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

    void handleSectApprove(WebSocket socket, long msgId, Long userId, JsonObject data) {
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
        OperationResult result = sectService.approveApplication(player.getId(), appId, true);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_approve", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_approve", 400, result.message()).toJson());
        }
    }

    void handleSectReject(WebSocket socket, long msgId, Long userId, JsonObject data) {
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
        OperationResult result = sectService.approveApplication(player.getId(), appId, false);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_reject", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_reject", 400, result.message()).toJson());
        }
    }

    void handleSectLeave(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_leave", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        OperationResult result = sectService.leaveSect(player.getId());
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_leave", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_leave", 400, result.message()).toJson());
        }
    }

    void handleSectKick(WebSocket socket, long msgId, Long userId, JsonObject data) {
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
        OperationResult result = sectService.kickMember(player.getId(), targetId);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_kick", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_kick", 400, result.message()).toJson());
        }
    }

    void handleSectAppoint(WebSocket socket, long msgId, Long userId, JsonObject data) {
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
        OperationResult result = sectService.appointMember(player.getId(), targetId, role);
        if (result.success()) {
            socket.send(GameMessage.ok(msgId, "sect_appoint", result.message(), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_appoint", 400, result.message()).toJson());
        }
    }

    void handleSectWarehouse(WebSocket socket, long msgId, Long userId) {
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

    void handleSectDonate(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleSectTake(WebSocket socket, long msgId, Long userId, JsonObject data) {
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

    void handleSectDisband(WebSocket socket, long msgId, Long userId) {
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

    void handleSectLevelUp(WebSocket socket, long msgId, Long userId) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_levelup", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        Map<String, Object> result = sectService.levelUp(player.getId());
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_levelup", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_levelup", 400, (String) result.get("message")).toJson());
        }
    }

    void handleSectTransfer(WebSocket socket, long msgId, Long userId, JsonObject data) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_transfer", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (!data.has("targetPlayerId") || data.get("targetPlayerId").getAsLong() == 0) {
            socket.send(GameMessage.error(msgId, "sect_transfer", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long targetPlayerId = data.get("targetPlayerId").getAsLong();
        Map<String, Object> result = sectService.transferLeader(player.getId(), targetPlayerId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            socket.send(GameMessage.ok(msgId, "sect_transfer", (String) result.get("message"), null).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_transfer", 400, (String) result.get("message")).toJson());
        }
    }

    void handleSectWar(WebSocket socket, long msgId, Long userId, JsonObject data) {
        PlayerInfo player = playerService.getPlayerByUserId(userId);
        if (player == null) {
            socket.send(GameMessage.error(msgId, "sect_war", GameErrorCode.PLAYER_NOT_FOUND).toJson());
            return;
        }
        if (!data.has("targetSectId") || data.get("targetSectId").getAsLong() == 0) {
            socket.send(GameMessage.error(msgId, "sect_war", GameErrorCode.PARAM_MISSING).toJson());
            return;
        }
        long targetSectId = data.get("targetSectId").getAsLong();
        Map<String, Object> result = sectService.declareWar(player.getId(), targetSectId);
        if (Boolean.TRUE.equals(result.get("success"))) {
            JsonObject resp = new JsonObject();
            resp.addProperty("winner", (String) result.get("winner"));
            resp.addProperty("attackerWins", (int) result.get("attackerWins"));
            resp.addProperty("defenderWins", (int) result.get("defenderWins"));
            resp.addProperty("battleLog", (String) result.get("battleLog"));
            socket.send(GameMessage.ok(msgId, "sect_war", (String) result.get("message"), resp).toJson());
        } else {
            socket.send(GameMessage.error(msgId, "sect_war", 400, (String) result.get("message")).toJson());
        }
    }

    void handleSectTop(WebSocket socket, long msgId) {
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
}
