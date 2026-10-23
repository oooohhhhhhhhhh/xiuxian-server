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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 兑换码接口（列表、创建、编辑、删除）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /redeem-codes/**}
 * 只依赖 {@code RedeemCodeService} 与 {@code RedeemCode} 实体，自带一个
 * 只在兑换码域使用的 {@code toRedeemCodeJson} 序列化方法，与其它管理端点
 * 没有共享状态，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.redeem.code.manage}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/redeem-codes/*} 不受影响。
 * "已兑换或已过期的码不允许编辑"这道校验一字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /redeem-codes/{id}} 拼出来的地址与
 * 拆分前逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminRedeemCodeResource {

    private static final com.mtxgdn.game.service.RedeemCodeService redeemCodeService =
            new com.mtxgdn.game.service.RedeemCodeService();

    // ========== 兑换码管理 API ==========

    @GET
    @Path("/redeem-codes")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response getRedeemCodes() {
        List<com.mtxgdn.game.entity.RedeemCode> codes = redeemCodeService.listAll();
        JsonArray arr = new JsonArray();
        for (com.mtxgdn.game.entity.RedeemCode rc : codes) {
            arr.add(toRedeemCodeJson(rc));
        }
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.add("codes", arr);
        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/redeem-codes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response createRedeemCode(String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String code = req.has("code") ? req.get("code").getAsString() : "";
            String name = req.has("name") ? req.get("name").getAsString() : "";
            long gold = req.has("gold") ? req.get("gold").getAsLong() : 0;
            long spiritStones = req.has("spiritStones") ? req.get("spiritStones").getAsLong() : 0;
            long exp = req.has("exp") ? req.get("exp").getAsLong() : 0;
            int maxUses = req.has("maxUses") ? req.get("maxUses").getAsInt() : 1;
            String expiresAt = req.has("expiresAt") && !req.get("expiresAt").isJsonNull()
                    ? req.get("expiresAt").getAsString() : null;
            String createdBy = req.has("createdBy") ? req.get("createdBy").getAsString() : "admin";

            if (code.isBlank()) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.redeem_code_cannot_be_empty"));
                return Response.ok(gson.toJson(err)).build();
            }

            Map<String, Integer> items = new LinkedHashMap<>();
            if (req.has("items") && req.get("items").isJsonObject()) {
                JsonObject itemsObj = req.getAsJsonObject("items");
                for (String key : itemsObj.keySet()) {
                    items.put(key, itemsObj.get(key).getAsInt());
                }
            }

            com.mtxgdn.game.entity.RedeemCode rc = redeemCodeService.createCode(
                    code, name, items, gold, spiritStones, exp, maxUses, expiresAt, createdBy);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.redeem_code_created"));
            result.add("codeInfo", toRedeemCodeJson(rc));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.creation_failed_2", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/redeem-codes/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response updateRedeemCode(@PathParam("id") long id, String body) {
        try {
            // 已兑换或已过期的码不允许编辑
            com.mtxgdn.game.entity.RedeemCode existing = redeemCodeService.findById(id);
            if (existing != null && (existing.isRedeemed() || existing.isExpired())) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 400);
                err.addProperty("message", I18n.t("admin.redeemed_or_expired_redeem_codes"));
                return Response.ok(gson.toJson(err)).build();
            }

            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String name = req.has("name") ? req.get("name").getAsString() : "";
            long gold = req.has("gold") ? req.get("gold").getAsLong() : 0;
            long spiritStones = req.has("spiritStones") ? req.get("spiritStones").getAsLong() : 0;
            long exp = req.has("exp") ? req.get("exp").getAsLong() : 0;
            int maxUses = req.has("maxUses") ? req.get("maxUses").getAsInt() : 1;
            String expiresAt = req.has("expiresAt") && !req.get("expiresAt").isJsonNull()
                    ? req.get("expiresAt").getAsString() : null;

            Map<String, Integer> items = new LinkedHashMap<>();
            if (req.has("items") && req.get("items").isJsonObject()) {
                JsonObject itemsObj = req.getAsJsonObject("items");
                for (String key : itemsObj.keySet()) {
                    items.put(key, itemsObj.get(key).getAsInt());
                }
            }

            redeemCodeService.updateCode(id, name, items, gold, spiritStones, exp, maxUses, expiresAt);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.redeem_code_updated"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.update_failed", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/redeem-codes/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.redeem.code.manage")
    public Response deleteRedeemCode(@PathParam("id") long id) {
        try {
            redeemCodeService.deleteCode(id);
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.redeem_code_deleted"));
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.delete_failed_2", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    private JsonObject toRedeemCodeJson(com.mtxgdn.game.entity.RedeemCode rc) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", rc.getId());
        obj.addProperty("code", rc.getCode());
        obj.addProperty("name", rc.getName());
        obj.addProperty("gold", rc.getGold());
        obj.addProperty("spiritStones", rc.getSpiritStones());
        obj.addProperty("exp", rc.getExp());
        obj.addProperty("maxUses", rc.getMaxUses());
        obj.addProperty("currentUses", rc.getCurrentUses());
        obj.addProperty("status", rc.getStatus());
        obj.addProperty("isActive", rc.isActive());
        obj.addProperty("expiresAt", rc.getExpiresAt());
        obj.addProperty("createdBy", rc.getCreatedBy());
        obj.addProperty("createdAt", rc.getCreatedAt());

        Map<String, Integer> items = rc.getItems();
        if (items != null && !items.isEmpty()) {
            JsonObject itemsObj = new JsonObject();
            for (Map.Entry<String, Integer> e : items.entrySet()) {
                itemsObj.addProperty(e.getKey(), e.getValue());
            }
            obj.add("items", itemsObj);
        }
        return obj;
    }
}
