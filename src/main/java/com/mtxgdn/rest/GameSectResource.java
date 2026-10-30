package com.mtxgdn.rest;

import com.mtxgdn.game.sect.WarResult;
import com.mtxgdn.game.sect.WarehouseDonateResult;
import com.mtxgdn.game.sect.SectActionResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectApplication;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SectWarehouseItem;
import com.mtxgdn.game.service.SectService;
import com.mtxgdn.common.result.OperationResult;
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
import static com.mtxgdn.rest.GameSupport.getPlayerName;
import static com.mtxgdn.rest.GameSupport.getPlayerIdByUserId;

/**
 * 玩家侧 · 宗门接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：宗门创建/加入/退出/解散、
 * 成员与申请审批、职位任命与转让、宗门等级、宗门仓库捐入与取出、宗门战宣战、
 * 宗门排行。这是原文件里端点最多的一域（17 个端点），全部读写宗门服务，
 * 因此整块搬出来单独成类。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /sect/create}、{@code /sect/approve/{appId}}、{@code /sect/take}、
 * {@code /sect/war/{targetSectId}} 等）逐字照搬，路径模板里的参数名也没改；
 * 权限码 {@code game.sect.manage} / {@code game.sect.donate} /
 * {@code game.sect.warehouse} 全部保持原样，返回结构未动。</p>
 *
 * <p>注意：{@code ModuleGateFilter} 里 {@code sect → sect}、
 * {@code sect/war → experimental_sect_war} 的门控映射按 URL 前缀匹配，
 * 与资源类的划分无关，本类拆分不影响它（{@code ModuleGateCoverageTest} 覆盖这一点）。</p>
 *
 * <p>{@code buildSectResponse} 是拆分前就带 {@code @SuppressWarnings("unused")} 的
 * 未使用辅助方法，为保持"一行功能都不删"跟着本域搬进来，未做任何修改。</p>
 */
@Path("/game")
public class GameSectResource {

    private static final SectService sectService = ServiceRegistry.getSectService();

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
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
            return Response.ok(GameMessage.restOk(I18n.t("sect.not_member"), null).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        SectActionResult result = sectService.createSect(playerId, name, desc);

        actionLog.logCreatePlayer(userId, getPlayerName(userId));
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(),
                    gson.toJsonTree(result.sect()).getAsJsonObject()).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/join/{sectId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response joinSect(@PathParam("sectId") long sectId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        OperationResult result = sectService.applyToSect(playerId, sectId, "");
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("sect.not_member")).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }

    @POST
    @Path("/sect/approve/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response approveApplication(@PathParam("appId") long appId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        OperationResult result = sectService.approveApplication(playerId, appId, true);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/reject/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response rejectApplication(@PathParam("appId") long appId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        OperationResult result = sectService.approveApplication(playerId, appId, false);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/leave")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response leaveSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        OperationResult result = sectService.leaveSect(playerId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/kick/{targetPlayerId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response kickMember(@PathParam("targetPlayerId") long targetPlayerId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        OperationResult result = sectService.kickMember(playerId, targetPlayerId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
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
        OperationResult result = sectService.appointMember(playerId, targetId, role);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("sect.not_member")).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("sect.not_member")).toString()).build();
        }
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 0;
        WarehouseDonateResult result = sectService.donateToWarehouse(playerId, member.getSectId(), itemKey, quantity);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("sect.not_member")).toString()).build();
        }
        JsonObject json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        String itemKey = json.has("itemKey") ? json.get("itemKey").getAsString() : "";
        int quantity = json.has("quantity") ? json.get("quantity").getAsInt() : 0;
        OperationResult result = sectService.withdrawFromWarehouse(playerId, member.getSectId(), itemKey, quantity);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/disband")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response disbandSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectActionResult result = sectService.disbandSect(playerId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/levelup")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response levelUpSect() {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectActionResult result = sectService.levelUp(playerId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/transfer/{targetPlayerId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response transferSect(@PathParam("targetPlayerId") long targetPlayerId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        SectActionResult result = sectService.transferLeader(playerId, targetPlayerId);
        if (result.success()) {
            return Response.ok(GameMessage.restOk(result.message(), null).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
    }

    @POST
    @Path("/sect/war/{targetSectId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("game.sect.manage")
    public Response declareWar(@PathParam("targetSectId") long targetSectId) {
        Long userId = getCurrentUserId();
        int playerId = getPlayerIdByUserId(userId);
        WarResult result = sectService.declareWar(playerId, targetSectId);
        if (result.success()) {
            JsonObject data = new JsonObject();
            data.addProperty("winner", result.winner());
            data.addProperty("attackerWins", result.attackerWins());
            data.addProperty("defenderWins", result.defenderWins());
            data.addProperty("battleLog", result.battleLog());
            return Response.ok(GameMessage.restOk(result.message(), data).toString()).build();
        }
        return Response.ok(GameMessage.restError(400, result.message()).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }
}
