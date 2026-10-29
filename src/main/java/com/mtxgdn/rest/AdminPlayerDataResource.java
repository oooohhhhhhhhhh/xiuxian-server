package com.mtxgdn.rest;

import com.mtxgdn.playerdata.PlayerDataVerifyResult;
import com.mtxgdn.playerdata.PlayerDataExportAllResult;
import com.mtxgdn.playerdata.PlayerDataResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.playerdata.PlayerDataService;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * 管理台 · 玩家数据文件（一人一个 JSON 快照）接口。
 *
 * <p>从原来那个 4000+ 行的 {@code AdminResource} 里拆出来的第一块（该类的其余
 * 端点后来也按同一套规则拆到了 {@code AdminPlayersResource}、{@code AdminDbResource}
 * 等资源类里）：一个类装 100+ 个端点，改任何一处都得在几屏无关代码之间找位置。
 * 拆分只按"路径前缀 + 依赖集合"切，不夹带任何行为改动 —— 路径、权限码、
 * 返回结构全部保持原样，前端 {@code /api/admin/player-data/*} 不受影响。</p>
 *
 * <p>类级路径 {@code /admin/player-data} + 方法级 {@code /export/{playerId}}
 * 拼出来的地址与拆分前逐字一致（Jersey 的路径拼接规则）。</p>
 *
 * <p>权限沿用 {@code admin.database.clear_players}：这是既有安装里已经授出去的权限码，
 * 拆分时顺手改成新码会把现网管理员的权限悄悄收走。</p>
 */
@Path("/admin/player-data")
public class AdminPlayerDataResource {

    private static final Gson gson = new Gson();

    @POST
    @Path("/export/{playerId}")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response exportPlayerData(@PathParam("playerId") long playerId) {
        PlayerDataService dataService = new PlayerDataService();
        PlayerDataResult result = dataService.exportPlayer(playerId);
        JsonObject out = new JsonObject();
        out.addProperty("code", result.success() ? 200 : 400);
        out.addProperty("message", result.message());
        return Response.ok(gson.toJson(out)).build();
    }

    @POST
    @Path("/export-all")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response exportAllPlayerData() {
        PlayerDataService dataService = new PlayerDataService();
        PlayerDataExportAllResult result = dataService.exportAllPlayers();
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("message",
                I18n.t("admin.player_data.exported", result.exported(), result.failed()));
        out.addProperty("dir", result.dir());
        return Response.ok(gson.toJson(out)).build();
    }

    @GET
    @Path("/list")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response listPlayerDataFiles() {
        PlayerDataService dataService = new PlayerDataService();
        List<String> files = dataService.listPlayerFiles();
        JsonArray arr = new JsonArray();
        for (String f : files) {
            arr.add(f);
        }
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.addProperty("count", files.size());
        out.add("files", arr);
        return Response.ok(gson.toJson(out)).build();
    }

    @GET
    @Path("/stats")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response playerDataStats() {
        JsonObject out = new JsonObject();
        out.addProperty("code", 200);
        out.add("data", gson.toJsonTree(PlayerDataService.getStats()));
        return Response.ok(gson.toJson(out)).build();
    }

    @GET
    @Path("/verify/{playerId}")
    @Produces("application/json;charset=UTF-8")
    @RequirePermission("admin.database.clear_players")
    public Response verifyPlayerData(@PathParam("playerId") long playerId) {
        PlayerDataService dataService = new PlayerDataService();
        PlayerDataVerifyResult result = dataService.verifyFile(playerId);
        JsonObject out = new JsonObject();
        out.addProperty("code", result.success() ? 200 : 400);
        out.addProperty("message", result.message());
        out.addProperty("legacy", Boolean.TRUE.equals(result.legacy()));
        if (result.rowCount() != null) {
            out.addProperty("rowCount", result.rowCount());
        }
        return Response.ok(gson.toJson(out)).build();
    }
}
