package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.map.WildernessTerrain;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.mtxgdn.rest.AdminSupport.LOG;
import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.hasJsonValue;
import static com.mtxgdn.rest.AdminSupport.reloadMapCacheIfNeeded;

/**
 * 管理台 · 地图管理接口（地点总览/增删改、连接）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：地图相关的端点自带
 * 一整套只在本地图域用到的 SQL 辅助方法（地点名唯一性校验、野外 id 段保护、
 * 安全区与危险度的一致性校验、连接 upsert），与其它管理端点没有共享状态，
 * 因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.database.access}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/map/*} 不受影响。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /map/locations/{id}} 拼出来的地址与
 * 拆分前逐字一致（Jersey 的路径拼接规则）。改完地图表后的缓存热重载仍走
 * {@link AdminSupport#reloadMapCacheIfNeeded(String)}，逻辑一行未动。</p>
 *
 * <p>野外分片视图另拆到 {@link AdminWildernessResource}（{@code /admin/map/wilderness}），
 * 它读的是分片文件而不是数据库，依赖集合完全不同；事件权重偏置另拆到
 * {@link AdminMapEventBiasResource}（{@code /admin/map/event-bias}），它读写的是
 * {@code map_location_event_bias} 这张独立的表。</p>
 */
@Path("/admin")
public class AdminMapResource {

    // ==================== 地图管理 ====================

    /**
     * 地图总览：全部地点（含境界名、灵气/地界倍率、安全区、邻居与路程、事件偏置）+ 区域分组。
     *
     * <p>通用表管理只能看到 map_locations 的裸行：境界是个数字、邻接关系在另一张表里、
     * 加成是两列 DOUBLE、事件偏置又在第三张表。地图页把它们拼成"一张看得懂的地图"，
     * 否则管理员想确认"问道峰到底要什么境界"得自己联三张表。</p>
     */
    @GET
    @Path("/map/locations")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getMapLocations() {
        JsonObject out = new JsonObject();
        try {
            var mapService = ServiceRegistry.getMapService();
            mapService.ensureInitialized();

            // 已知世界与野外同在一张表，但后台必须分开计数：
            // 已知世界是固定的 15 个地点 / 17 条边（历史不变量，改坏了要能立刻看出来），
            // 野外是随机生成、会刷新的临时节点，数量与连接每次刷新都不同。
            // 混在一起统计，"地点数/连接数"就成了一个每次都变、无法判断是否正常的数字。
            Set<Long> knownIds = new java.util.HashSet<>();
            for (var loc : mapService.getAllLocations()) {
                knownIds.add(loc.getId());
            }

            JsonArray arr = new JsonArray();
            Map<String, Integer> regionCounts = new LinkedHashMap<>();
            int directedEdges = 0;
            int directedWildernessEdges = 0;
            // 野外不在库中，因此这里列的只有已知世界；野外分片另有 /admin/map/wilderness 视图
            for (var loc : mapService.getAllLocations()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", loc.getId());
                o.addProperty("name", loc.getName());
                o.addProperty("region", loc.getRegion());
                o.addProperty("description", loc.getDescription());
                o.addProperty("minRealm", loc.getMinRealm());
                o.addProperty("minRealmName", mapService.getRealmName(loc.getMinRealm()));
                o.addProperty("safeZone", loc.isSafeZone());
                o.addProperty("cultivationBonus", loc.getCultivationBonus());
                o.addProperty("explorationBonus", loc.getExplorationBonus());
                o.addProperty("dangerLevel", loc.getDangerLevel());
                o.addProperty("dangerName", mapService.getDangerName(loc.getDangerLevel()));
                o.addProperty("wilderness", loc.isWilderness());

                JsonArray neighbors = new JsonArray();
                for (var nb : loc.getConnections()) {
                    JsonObject n = new JsonObject();
                    n.addProperty("id", nb.getId());
                    n.addProperty("name", nb.getName());
                    n.addProperty("region", nb.getRegion());
                    n.addProperty("travelSeconds", mapService.getTravelTimeSeconds(loc.getId(), nb.getId()));
                    n.addProperty("wilderness", nb.isWilderness());
                    neighbors.add(n);

                    if (knownIds.contains(loc.getId()) && knownIds.contains(nb.getId())) {
                        directedEdges++;
                    } else {
                        directedWildernessEdges++;
                    }
                }
                o.add("neighbors", neighbors);

                JsonObject bias = new JsonObject();
                for (Map.Entry<String, Double> entry : mapService.getEventBias(loc.getId()).entrySet()) {
                    bias.addProperty(entry.getKey(), entry.getValue());
                }
                o.add("eventBias", bias);

                arr.add(o);
                regionCounts.merge(loc.getRegion(), 1, Integer::sum);
            }

            JsonObject data = new JsonObject();
            data.add("locations", arr);
            data.addProperty("locationCount", knownIds.size());
            data.addProperty("wildernessCount",
                    ((Number) mapService.getWildernessStats().get("chunkCount")).longValue());
            // 连接在库里是双向写入的，按无向边计数要除以 2
            data.addProperty("connectionCount", directedEdges / 2);
            data.addProperty("wildernessConnectionCount", directedWildernessEdges / 2);
            data.addProperty("wildernessEnabled", mapService.isWildernessEnabled());
            data.addProperty("wildernessRefreshAt", 0);
            JsonArray regions = new JsonArray();
            for (Map.Entry<String, Integer> entry : regionCounts.entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("name", entry.getKey());
                r.addProperty("count", entry.getValue());
                regions.add(r);
            }
            data.add("regions", regions);

            out.addProperty("code", 200);
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_fetch_map_data", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 更新地点参数（名称/描述/区域/境界门槛/安全区/灵气倍率/地界倍率），改完立即热重载。
     *
     * <p>只更新请求里出现的字段 —— "没传"不等于"清空"，避免前端漏字段就把数据抹掉。</p>
     */
    @POST
    @Path("/map/locations/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response updateMapLocation(@PathParam("id") long id, String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

            // SET 子句的列名全部来自下面这些字面量（不是用户输入），因此不存在注入面
            Map<String, Object> update = new LinkedHashMap<>();
            if (hasJsonValue(req, "name")) {
                String name = req.get("name").getAsString().trim();
                if (name.isEmpty()) throw new IllegalArgumentException("地点名不能为空");
                update.put("name", name);
            }
            if (hasJsonValue(req, "description")) {
                update.put("description", req.get("description").getAsString());
            }
            if (hasJsonValue(req, "region")) {
                String region = req.get("region").getAsString().trim();
                if (region.isEmpty()) throw new IllegalArgumentException("区域名不能为空");
                update.put("region", region);
            }
            if (hasJsonValue(req, "minRealm")) {
                int realm = req.get("minRealm").getAsInt();
                int maxRealm = GameConfigLoader.getRealmTotal() - 1;
                if (realm < 0 || realm > maxRealm) {
                    throw new IllegalArgumentException("境界门槛应在 0~" + maxRealm + " 之间");
                }
                update.put("min_realm", realm);
            }
            if (hasJsonValue(req, "safeZone")) {
                update.put("is_safe_zone", req.get("safeZone").getAsBoolean());
            }
            if (hasJsonValue(req, "cultivationBonus")) {
                update.put("cultivation_bonus", parseMapBonus(req.get("cultivationBonus"), "灵气倍率"));
            }
            if (hasJsonValue(req, "explorationBonus")) {
                update.put("exploration_bonus", parseMapBonus(req.get("explorationBonus"), "地界倍率"));
            }
            if (hasJsonValue(req, "dangerLevel")) {
                int danger = req.get("dangerLevel").getAsInt();
                if (danger < 0 || danger > MAX_DANGER_LEVEL) {
                    throw new IllegalArgumentException("危险度应在 0~" + MAX_DANGER_LEVEL
                            + " 之间（0=安全 1=低危 2=中危 3=高危 4=极危）");
                }
                update.put("danger_level", danger);
            }
            if (update.isEmpty()) {
                throw new IllegalArgumentException("没有需要更新的字段");
            }

            // 不变式：安全区不能同时是险地。这里把"库里的现值"和"本次请求"合并后再校验 ——
            // 只改其中一个字段同样能造出矛盾数据（例如给已是安全区的地点填危险度 3）。
            checkDangerConsistencyOnUpdate(id, update);
            // 地点名是 /地图 go <地名> 的唯一解析依据，重名会让其中一个永远走不到
            if (update.containsKey("name")) {
                checkMapLocationNameAvailable((String) update.get("name"), id);
            }

            String sql = "UPDATE map_locations SET "
                    + String.join(", ", update.keySet().stream().map(col -> col + " = ?").toList())
                    + " WHERE id = ?";
            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                int index = 1;
                for (Object value : update.values()) {
                    if (value instanceof Boolean b) {
                        ps.setBoolean(index++, b);
                    } else if (value instanceof Integer i) {
                        ps.setInt(index++, i);
                    } else if (value instanceof Double d) {
                        ps.setDouble(index++, d);
                    } else {
                        ps.setString(index++, (String) value);
                    }
                }
                ps.setLong(index, id);
                affected = ps.executeUpdate();
            }

            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.location_not_found", id));
                return Response.ok(gson.toJson(out)).build();
            }

            // 改完立即热重载：否则地图缓存还是旧值，管理员会以为没改上
            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("updated", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.updated"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_update_location", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 倍率校验：限定 0.1~100，挡住 0、负数与手滑多打的 0。 */
    private static double parseMapBonus(com.google.gson.JsonElement element, String label) {
        double value = element.getAsDouble();
        if (Double.isNaN(value) || value < 0.1 || value > 100) {
            throw new IllegalArgumentException(label + "应在 0.1~100 之间");
        }
        return value;
    }

    // ==================== 地图增删与连接/偏置编辑 ====================

    /** 危险度上限（0=安全 1=低危 2=中危 3=高危 4=极危）。 */
    private static final int MAX_DANGER_LEVEL = 4;

    /**
     * 新建地点。
     *
     * <p>与 {@code GET /map/locations} 同路径不同方法：GET 是总览、POST 是新增，
     * 符合集合资源的常规语义。</p>
     *
     * <p>id 可省略（自动取已知世界范围内的下一个空位）。<b>不允许手工指定野外保留区
     * （≥ 野外 idBase）的 id</b>：那一段是程序按槽位循环复用的，手填进去的地点在下次
     * 野外刷新时会被当成过期节点删掉。</p>
     */
    @POST
    @Path("/map/locations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response createMapLocation(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();

            String name = hasJsonValue(req, "name") ? req.get("name").getAsString().trim() : "";
            if (name.isEmpty()) throw new IllegalArgumentException("地点名不能为空");
            String region = hasJsonValue(req, "region") ? req.get("region").getAsString().trim() : "";
            if (region.isEmpty()) throw new IllegalArgumentException("区域名不能为空");

            int minRealm = hasJsonValue(req, "minRealm") ? req.get("minRealm").getAsInt() : 0;
            int maxRealm = GameConfigLoader.getRealmTotal() - 1;
            if (minRealm < 0 || minRealm > maxRealm) {
                throw new IllegalArgumentException("境界门槛应在 0~" + maxRealm + " 之间");
            }
            boolean safeZone = hasJsonValue(req, "safeZone") && req.get("safeZone").getAsBoolean();
            int dangerLevel = hasJsonValue(req, "dangerLevel") ? req.get("dangerLevel").getAsInt() : 0;
            if (dangerLevel < 0 || dangerLevel > MAX_DANGER_LEVEL) {
                throw new IllegalArgumentException("危险度应在 0~" + MAX_DANGER_LEVEL
                        + " 之间（0=安全 1=低危 2=中危 3=高危 4=极危）");
            }
            requireDangerConsistent(safeZone, dangerLevel);

            double cultivation = hasJsonValue(req, "cultivationBonus")
                    ? parseMapBonus(req.get("cultivationBonus"), "灵气倍率") : 1.0;
            double exploration = hasJsonValue(req, "explorationBonus")
                    ? parseMapBonus(req.get("explorationBonus"), "地界倍率") : 1.0;
            String description = hasJsonValue(req, "description") ? req.get("description").getAsString() : "";

            // 野外的 id 不再是"保留段"，而是按坐标算出来的极大值；管理员新建地点时
            // 只需避开这一段，防止 id 撞上某个真实存在的野外节点
            long wildernessFloor = WildernessTerrain.ID_BASE;
            long createdId;
            try (Connection conn = DatabaseManager.getConnection()) {
                if (hasJsonValue(req, "id")) {
                    createdId = req.get("id").getAsLong();
                    if (createdId <= 0) throw new IllegalArgumentException("地点 id 必须为正数");
                    if (WildernessTerrain.isWildernessId(createdId)) {
                        throw new IllegalArgumentException("id " + createdId + " 落在野外 id 段（≥ "
                                + WildernessTerrain.ID_BASE + "），该段由坐标推导、不属于已知世界；"
                                + "请改用 1~" + (WildernessTerrain.ID_BASE - 1) + " 或省略 id 由系统分配");
                    }
                    if (mapLocationExists(conn, createdId)) {
                        throw new IllegalArgumentException("地点 id " + createdId + " 已存在");
                    }
                } else {
                    createdId = nextKnownWorldLocationId(conn, wildernessFloor);
                }
                checkMapLocationNameAvailable(name, 0);

                String sql = "INSERT INTO map_locations "
                        + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus,"
                        + " exploration_bonus, danger_level, is_wilderness) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, createdId);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.setString(4, region);
                    ps.setInt(5, minRealm);
                    ps.setBoolean(6, safeZone);
                    ps.setDouble(7, cultivation);
                    ps.setDouble(8, exploration);
                    ps.setInt(9, dangerLevel);
                    ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("id", createdId);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.new_location_created", name, createdId));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_create_location", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 删除地点。
     *
     * <p>连带删除该地点的全部连接、事件偏置与野外资源，并把仍站在这里的玩家送回
     * 出生点 —— 否则会留下一批指向不存在地点的连接，以及"人在虚空里"的存档。</p>
     */
    @DELETE
    @Path("/map/locations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapLocation(@PathParam("id") long id) {
        JsonObject out = new JsonObject();
        try {
            if (id <= 0) throw new IllegalArgumentException("地点 id 必须为正数");

            int affected;
            try (Connection conn = DatabaseManager.getConnection()) {
                String name = null;
                try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM map_locations WHERE id = ?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) name = rs.getString(1);
                    }
                }
                if (name == null) {
                    out.addProperty("code", 404);
                    out.addProperty("message", I18n.t("admin.location_not_found", id));
                    return Response.ok(gson.toJson(out)).build();
                }

                // 玩家先安置：留在已删除地点上的存档会在下次读取时被判为异常位置
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE players SET current_location_id = 1, last_travel_time = 0 "
                                + "WHERE current_location_id = ?")) {
                    ps.setLong(1, id);
                    int moved = ps.executeUpdate();
                    if (moved > 0) {
                        LOG.warn("删除地点「" + name + "」(id=" + id + ") 时把 " + moved
                                + " 名玩家送回了出生点");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_connections WHERE from_location_id = ? OR to_location_id = ?")) {
                    ps.setLong(1, id);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM map_location_event_bias WHERE location_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM player_map_exploration WHERE location_id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM map_locations WHERE id = ?")) {
                    ps.setLong(1, id);
                    affected = ps.executeUpdate();
                }
            }

            reloadMapCacheIfNeeded("map_locations");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.location_and_its_connections_biases"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_delete_location", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 新增或更新一条<b>有向</b>连接（from → to）。
     *
     * <p>默认同时写入反向边：地图的邻接是双向可达的，只加单向会让玩家"去了回不来"，
     * 而库里历史上就出现过这种数据（{@code repairReverseConnections} 一直在兜）。
     * 确实需要单向路（例如传送阵式的单行道）时显式传 {@code oneWay: true}。</p>
     */
    @POST
    @Path("/map/connections")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response upsertMapConnection(String body) {
        JsonObject out = new JsonObject();
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            long from = req.get("from").getAsLong();
            long to = req.get("to").getAsLong();
            if (from == to) throw new IllegalArgumentException("不能把地点连到它自己");
            int travelSeconds = hasJsonValue(req, "travelSeconds") ? req.get("travelSeconds").getAsInt() : 5;
            if (travelSeconds < 1 || travelSeconds > 86400) {
                throw new IllegalArgumentException("路程秒数应在 1~86400 之间");
            }
            boolean oneWay = hasJsonValue(req, "oneWay") && req.get("oneWay").getAsBoolean();

            try (Connection conn = DatabaseManager.getConnection()) {
                if (!mapLocationExists(conn, from)) throw new IllegalArgumentException("起点不存在: " + from);
                if (!mapLocationExists(conn, to)) throw new IllegalArgumentException("终点不存在: " + to);
                upsertConnection(conn, from, to, travelSeconds);
                if (!oneWay) {
                    upsertConnection(conn, to, from, travelSeconds);
                }
            }

            reloadMapCacheIfNeeded("map_connections");

            JsonObject data = new JsonObject();
            data.addProperty("from", from);
            data.addProperty("to", to);
            data.addProperty("travelSeconds", travelSeconds);
            data.addProperty("oneWay", oneWay);
            out.addProperty("code", 200);
            out.addProperty("message", oneWay
                    ? I18n.t("admin.map_link.one_way")
                    : I18n.t("admin.map_link.two_way"));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_save_connection", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 删除连接；{@code bothWays=true}（默认）时连反向边一起删，避免留下"只能来不能回"的路。 */
    @DELETE
    @Path("/map/connections")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteMapConnection(@QueryParam("from") long from,
                                        @QueryParam("to") long to,
                                        @QueryParam("bothWays") @DefaultValue("true") boolean bothWays) {
        JsonObject out = new JsonObject();
        try {
            if (from <= 0 || to <= 0) throw new IllegalArgumentException("from 与 to 都必须为正数");
            int affected;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(bothWays
                         ? "DELETE FROM map_connections WHERE (from_location_id = ? AND to_location_id = ?)"
                         + " OR (from_location_id = ? AND to_location_id = ?)"
                         : "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?")) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                if (bothWays) {
                    ps.setLong(3, to);
                    ps.setLong(4, from);
                }
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.connection_not_found", from, to));
                return Response.ok(gson.toJson(out)).build();
            }

            reloadMapCacheIfNeeded("map_connections");

            JsonObject data = new JsonObject();
            data.addProperty("deleted", affected);
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.deleted_connection", affected));
            out.add("data", data);
        } catch (IllegalArgumentException e) {
            out.addProperty("code", 400);
            out.addProperty("message", e.getMessage());
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_delete_connection", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 安全区与危险度不能同时成立（安全区意味着禁止 PK，而危险度是 PK 收益的依据）。 */
    private static void requireDangerConsistent(boolean safeZone, int dangerLevel) {
        if (safeZone && dangerLevel > 0) {
            throw new IllegalArgumentException("安全区的危险度必须为 0（安全区禁止 PK，"
                    + "危险度是 PK 掠夺的依据，两者不能同时成立）");
        }
    }

    /**
     * 更新地点时校验"改完之后"的安全区/危险度组合是否自洽。
     * <p>行不存在时静默返回，由 UPDATE 的 affected==0 统一给出 404。</p>
     */
    private static void checkDangerConsistencyOnUpdate(long id, Map<String, Object> update) {
        boolean nextSafe;
        int nextDanger;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT is_safe_zone, COALESCE(danger_level, 0) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                nextSafe = rs.getBoolean(1);
                nextDanger = rs.getInt(2);
            }
        } catch (SQLException e) {
            return; // 列缺失等异常交给后续 UPDATE 报错，不在这里掩盖
        }
        if (update.containsKey("is_safe_zone")) nextSafe = (Boolean) update.get("is_safe_zone");
        if (update.containsKey("danger_level")) nextDanger = (Integer) update.get("danger_level");
        requireDangerConsistent(nextSafe, nextDanger);
    }

    /**
     * 校验地点名未被占用。
     * <p>地点名是 {@code /地图 go <地名>} 与 {@code /地图 传送 <地名>} 的唯一解析依据，
     * 重名会让其中一个地点永远无法通过指令抵达（{@code getLocationByName} 只返回第一个）。</p>
     *
     * @param excludeId 更新场景下排除自身；新建传 0
     */
    private static void checkMapLocationNameAvailable(String name, long excludeId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM map_locations WHERE name = ? AND id <> ?")) {
            ps.setString(1, name);
            ps.setLong(2, excludeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new IllegalArgumentException("已存在同名地点「" + name
                            + "」，重名会导致其中一个无法通过 /地图 go 抵达");
                }
            }
        }
    }

    private static boolean mapLocationExists(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM map_locations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 已知世界范围内（id &lt; 野外 idBase）的下一个空闲 id。 */
    private static long nextKnownWorldLocationId(Connection conn, long wildernessFloor) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM map_locations WHERE id < ?")) {
            ps.setLong(1, wildernessFloor);
            try (ResultSet rs = ps.executeQuery()) {
                long next = rs.next() ? rs.getLong(1) : 1L;
                return Math.max(1L, Math.min(next, wildernessFloor - 1));
            }
        }
    }

    private static void upsertConnection(Connection conn, long from, long to, int travelSeconds)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM map_connections WHERE from_location_id = ? AND to_location_id = ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO map_connections (from_location_id, to_location_id, travel_time_seconds) "
                        + "VALUES (?, ?, ?)")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setInt(3, travelSeconds);
            ps.executeUpdate();
        }
    }
}