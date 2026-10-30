package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.TeamService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static com.mtxgdn.rest.GameSupport.gson;
import static com.mtxgdn.rest.GameSupport.playerService;

/**
 * 玩家侧 · 队伍接口。
 *
 * <p>从近 3000 行的 {@code GameResource} 里拆出来的一块：创建队伍、邀请、接受邀请、
 * 离队、队伍信息。队伍服务在方法内部按单例取用（与拆分前一致），因此本类只需要
 * 玩家服务与请求上下文这两项依赖，单独成类最干净。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 类级 {@code @Path} 仍是 {@code /game}，方法级子路径
 * （{@code /team/create}、{@code /team/invite}、{@code /team/accept}、
 * {@code /team/leave}、{@code /team/info}）逐字照搬；权限码
 * {@code game.team.manage} / {@code game.team.view} 保持原样，返回结构未动。</p>
 */
@Path("/game")
public class GameTeamResource {

    @Context
    private ContainerRequestContext requestContext;

    /** 取当前登录用户 id；未登录抛 401。实现见 {@link GameSupport#getCurrentUserId}。 */
    private Long getCurrentUserId() {
        return GameSupport.getCurrentUserId(requestContext);
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
            return Response.ok(GameMessage.restError(400, I18n.t("team.already_in_team")).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("team.created"), data).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("team.invite_failed")).toString()).build();
        }

        return Response.ok(GameMessage.restOk(I18n.t("team.invite_sent") + target.getName() + "】", null).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("team.no_pending_invite")).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("team.joined"), data).toString()).build();
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
            return Response.ok(GameMessage.restError(400, I18n.t("team.not_in_team")).toString()).build();
        }

        return Response.ok(GameMessage.restOk(I18n.t("team.left"), null).toString()).build();
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
            return Response.ok(GameMessage.restOk(I18n.t("team.not_in_team"), null).toString()).build();
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
        return Response.ok(GameMessage.restOk(I18n.t("common.rest.ok"), data).toString()).build();
    }
}
