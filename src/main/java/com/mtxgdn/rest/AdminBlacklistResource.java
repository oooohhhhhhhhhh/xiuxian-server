package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 黑名单接口（列表、按 QQ 或 userId 拉黑、按 QQ 或 userId 解封）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /blacklist/**}
 * 只依赖 {@code BlacklistService} 与 {@code Blacklist} 实体，与其它管理端点
 * 没有共享状态，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.blacklist.manage}）、
 * 返回结构、HTTP 状态码全部保持原样，前端 {@code /api/admin/blacklist/*} 不受影响。
 * "qqNumber 与 userId 二选一"这道校验一字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /blacklist/qq/{qqNumber}} 拼出来的地址与
 * 拆分前逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminBlacklistResource {

    // ========== 黑名单管理 API ==========

    @GET
    @Path("/blacklist")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response getBlacklist() {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        java.util.List<com.mtxgdn.onebot.Blacklist> list = blacklistService.getAllBlacklist();

        JsonArray arr = new JsonArray();
        for (com.mtxgdn.onebot.Blacklist b : list) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", b.getId());
            obj.addProperty("qqNumber", b.getQqNumber());
            obj.addProperty("userId", b.getUserId());
            obj.addProperty("reason", b.getReason());
            obj.addProperty("bannedBy", b.getBannedBy());
            obj.addProperty("createdAt", b.getCreatedAt());
            arr.add(obj);
        }

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("blacklist", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/blacklist")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response addToBlacklist(String body) {
        JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        // qqNumber 和 userId 二选一
        String qqNumber = req.has("qqNumber") && !req.get("qqNumber").isJsonNull() ? req.get("qqNumber").getAsString() : null;
        Long userId = req.has("userId") && !req.get("userId").isJsonNull() ? req.get("userId").getAsLong() : null;
        String reason = req.has("reason") ? req.get("reason").getAsString() : "";

        boolean hasQq = qqNumber != null && !qqNumber.isBlank();
        boolean hasUid = userId != null;

        if (!hasQq && !hasUid) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.h7443b43e"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }
        if (hasQq && hasUid) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", I18n.t("admin.hacbe32d4"));
            return Response.status(400).entity(gson.toJson(err)).build();
        }

        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();

        try {
            blacklistService.addToBlacklist(qqNumber, userId, reason, null);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", hasUid ? I18n.t("admin.blacklist.added_by_user_id") : I18n.t("admin.blacklist.added_by_qq"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/blacklist/qq/{qqNumber}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response removeFromBlacklistByQq(@PathParam("qqNumber") String qqNumber) {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        try {
            blacklistService.removeFromBlacklist(qqNumber);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0ae42a94"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/blacklist/user/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.blacklist.manage")
    public Response removeFromBlacklistByUserId(@PathParam("userId") long userId) {
        com.mtxgdn.onebot.BlacklistService blacklistService = new com.mtxgdn.onebot.BlacklistService();
        try {
            blacklistService.removeFromBlacklistByUserId(userId);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h0ae42a94"));
            return Response.ok(gson.toJson(result)).build();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.status(400).entity(gson.toJson(err)).build();
        }
    }
}
