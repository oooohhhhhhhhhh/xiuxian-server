package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.hasJsonValue;
import static com.mtxgdn.rest.AdminSupport.reloadMapCacheIfNeeded;

/**
 * 管理台 · 地图事件权重偏置接口（按地点放大或压制某类随机事件的权重）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /map/event-bias}
 * 虽然也属于地图域，但它读写的是 {@code map_location_event_bias} 这张独立的表，
 * 依赖集合与 {@link AdminMapResource}（地点/连接）不重叠，因此按
 * "路径前缀 + 依赖集合"单独成类 —— 也让 {@link AdminMapResource} 不必为一个
 * 两张端点的子资源撑到 700 行以上。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.database.access}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/map/event-bias} 不受影响。
 * "先删后插而不是依赖 UPSERT 语法"这段两端通用的写法也一字未改。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /map/event-bias} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminMapEventBiasResource {

    /** 新增或更新某地点的事件权重偏置（事件 key → 倍率）。 */
    @POST
    @Path("/map/event-bias")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response upsertMapEventBias(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            long locationId = req.get("locationId").getAsLong();
            String eventKey = hasJsonValue(req, "eventKey") ? req.get("eventKey").getAsString().trim() : "";
            if (eventKey.isEmpty()) throw new IllegalArgumentException("事件 key 不能为空");
            if (eventKey.length() > 64) throw new IllegalArgumentException("事件 key 过长（上限 64 字符）");
            double multiplier = req.get("multiplier").getAsDouble();
            if (Double.isNaN(multiplier) || multiplier <= 0 || multiplier > 100) {
                throw new IllegalArgumentException("权重倍率应在 0~100 之间且大于 0");
            }

            try (Connection conn = DatabaseManager.getConnection()) {
                if (!mapLocationExists(conn, locationId)) {
                    throw new IllegalArgumentException("地点不存在: " + locationId);
                }
                // 先删后插而不是依赖 UPSERT 语法：SQLite 与 MySQL 的 upsert 写法不同，
                // 而这里对并发不敏感（管理台手工操作），换来一份两端通用的 SQL。
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
                    ps.setLong(1, locationId);
                    ps.setString(2, eventKey);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO map_location_event_bias (location_id, event_key, weight_multiplier) "
                                + "VALUES (?, ?, ?)")) {
                    ps.setLong(1, locationId);
                    ps.setString(2, eventKey);
                    ps.setDouble(3, multiplier);
                    ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_location_event_bias");

            JsonObject data = new JsonObject();
            data.addProperty("locationId", locationId);
            data.addProperty("eventKey", eventKey);
            data.addProperty("multiplier", multiplier);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h77706204"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.hd7073d40", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 删除某地点的一条事件偏置。 */
    @DELETE
    @Path("/map/event-bias")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapEventBias(@QueryParam("locationId") long locationId,
                                       @QueryParam("eventKey") String eventKey) {
        JsonObject out = new JsonObject();
        try {
            if (locationId <= 0) throw new IllegalArgumentException("locationId 必须为正数");
            if (eventKey == null || eventKey.isBlank()) throw new IllegalArgumentException("eventKey 不能为空");

            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM map_location_event_bias WHERE location_id = ? AND event_key = ?")) {
                ps.setLong(1, locationId);
                ps.setString(2, eventKey.trim());
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.hfc3d7aa4"));
                return Response.ok(gson.toJson(out)).build();
            }

            reloadMapCacheIfNeeded("map_location_event_bias");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.h05227a8c"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.h76c11ee3", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    private static boolean mapLocationExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }
}
