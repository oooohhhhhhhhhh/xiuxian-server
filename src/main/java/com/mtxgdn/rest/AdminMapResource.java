package com.mtxgdn.rest;

import com.mtxgdn.game.map.WildernessStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.MapDAO;
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

    /** map_locations / map_connections / 探索记录都归 MapDAO。 */
    private static final MapDAO MAP_DAO = new MapDAO();

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
                    mapService.getWildernessStats().chunkCount());
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
                requireValidMinRealm(realm);
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

            int affected = DatabaseManager.runTransaction(conn ->
                    MAP_DAO.updateLocationFields(conn, id, update));

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
            requireValidMinRealm(minRealm);
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
            // 「查 id 是否占用 / 查名字是否重名 / 插入」三步在同一个事务里：
            // 否则两个管理员同时新建会挑到同一个 id（后插入的那个直接失败）。
            long createdId = DatabaseManager.runTransaction(conn -> {
                long id;
                if (hasJsonValue(req, "id")) {
                    id = req.get("id").getAsLong();
                    if (id <= 0) throw new IllegalArgumentException("地点 id 必须为正数");
                    if (WildernessTerrain.isWildernessId(id)) {
                        throw new IllegalArgumentException("id " + id + " 落在野外 id 段（≥ "
                                + WildernessTerrain.ID_BASE + "），该段由坐标推导、不属于已知世界；"
                                + "请改用 1~" + (WildernessTerrain.ID_BASE - 1) + " 或省略 id 由系统分配");
                    }
                    if (MAP_DAO.existsLocation(conn, id)) {
                        throw new IllegalArgumentException("地点 id " + id + " 已存在");
                    }
                } else {
                    id = MAP_DAO.findNextKnownWorldLocationId(conn, wildernessFloor);
                }
                checkMapLocationNameAvailable(conn, name, 0);

                MAP_DAO.insertAdminLocation(conn, id, name, description, region,
                        minRealm, safeZone, cultivation, exploration, dangerLevel);
                return id;
            });

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

            String name = DatabaseManager.runTransaction(conn -> MAP_DAO.findLocationName(conn, id));
            if (name == null) {
                out.addProperty("code", 404);
                out.addProperty("message", I18n.t("admin.location_not_found", id));
                return Response.ok(gson.toJson(out)).build();
            }

            // 清场五步必须原子：中间失败会留下「连接还在、地点没了」或
            // 「玩家被挪走了、地点还在」这类半截状态。
            int affected = DatabaseManager.runTransaction(conn -> {
                // 玩家先安置：留在已删除地点上的存档会在下次读取时被判为异常位置
                int moved = MAP_DAO.relocatePlayersToSpawn(conn, id);
                if (moved > 0) {
                    LOG.warn("删除地点「" + name + "」(id=" + id + ") 时把 " + moved
                            + " 名玩家送回了出生点");
                }
                MAP_DAO.deleteConnectionsTouching(conn, id);
                MAP_DAO.deleteEventBiasByLocation(conn, id);
                MAP_DAO.deleteExplorationByLocation(conn, id);
                return MAP_DAO.deleteLocationById(conn, id);
            });

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

            DatabaseManager.runTransaction(conn -> {
                if (!MAP_DAO.existsLocation(conn, from)) throw new IllegalArgumentException("起点不存在: " + from);
                if (!MAP_DAO.existsLocation(conn, to)) throw new IllegalArgumentException("终点不存在: " + to);
                MAP_DAO.replaceConnection(conn, from, to, travelSeconds);
                if (!oneWay) {
                    MAP_DAO.replaceConnection(conn, to, from, travelSeconds);
                }
                return null;
            });

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
            int affected = DatabaseManager.runTransaction(conn ->
                    MAP_DAO.deleteConnectionPair(conn, from, to, bothWays));
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
     * 校验境界门槛。
     *
     * <p><b>上界必须用 {@code getMaxRealmId()} 而不是 {@code getRealmTotal() - 1}</b>：
     * 后者是"配置条目数减一"（凡人 + 各境界初期/中期/后期，共 33 条 → 32），
     * 而最高大境界只有 10。用条目数当上界会放行 11~32 这些<b>不存在</b>的境界，
     * 造出的地点谁也进不去，且提示文案会退化成"需要≥凡人"——
     * 与野外境界门槛那个 P0 缺陷是同一种病。</p>
     */
    private static void requireValidMinRealm(int realm) {
        int maxRealm = GameConfigLoader.getMaxRealmId();
        if (realm < 0 || realm > maxRealm) {
            throw new IllegalArgumentException("境界门槛应在 0~" + maxRealm
                    + " 之间（最高大境界为 " + maxRealm
                    + "；超过它的门槛会让该地点永久无人可进）");
        }
    }

    /**
     * 更新地点时校验"改完之后"的安全区/危险度组合是否自洽。
     * <p>行不存在时静默返回，由 UPDATE 的 affected==0 统一给出 404。</p>
     */
    private static void checkDangerConsistencyOnUpdate(long id, Map<String, Object> update) {
        MapDAO.DangerRow row;
        try {
            row = DatabaseManager.runTransaction(conn -> MAP_DAO.findDangerInfo(conn, id));
        } catch (RuntimeException e) {
            return; // 列缺失等异常交给后续 UPDATE 报错，不在这里掩盖
        }
        if (row == null) return;

        boolean nextSafe = row.safeZone();
        int nextDanger = row.dangerLevel();
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
    private static void checkMapLocationNameAvailable(String name, long excludeId) {
        requireNameNotInWildernessNamespace(name);
        boolean taken = DatabaseManager.runTransaction(conn ->
                MAP_DAO.countLocationsByNameExceptId(conn, name, excludeId) > 0);
        if (taken) {
            throw new IllegalArgumentException("已存在同名地点「" + name
                    + "」，重名会导致其中一个无法通过 /地图 go 抵达");
        }
    }

    /** 事务内版本：名字占用检查与随后的插入要落在同一条连接上（避免查完就被别人抢注）。 */
    private static void checkMapLocationNameAvailable(Connection conn, String name, long excludeId)
            throws SQLException {
        requireNameNotInWildernessNamespace(name);
        if (MAP_DAO.countLocationsByNameExceptId(conn, name, excludeId) > 0) {
            throw new IllegalArgumentException("已存在同名地点「" + name
                    + "」，重名会导致其中一个无法通过 /地图 go 抵达");
        }
    }

    /** 野外程序化地名专用的分隔符（见 {@code WildernessTerrain.buildNode} 的 {@code "·"} 尾缀）。 */
    private static final String WILDERNESS_NAME_SEPARATOR = "·";

    /**
     * 拒绝与野外命名空间混淆的地点名。
     *
     * <p>野外地名是 {@code 前缀 + 后缀 + "·" + 两个坐标派生字}（如「青松岭·甲乙」），
     * 而这个分隔符是野外独有的。管理员若用 {@code ·} 建一个已知世界地点，那个名字
     * 看起来会像野外节点，运维时无法区分两者。</p>
     *
     * <p>注意这<b>不会</b>导致"走到错误的地方"——{@code getLocationByName} 只查已知世界，
     * 野外本来就只靠 id 导航。所以这是<b>可读性/命名空间卫生</b>问题，不是正确性问题，
     * 但既然能一行代码消除，就没必要留着。</p>
     */
    private static void requireNameNotInWildernessNamespace(String name) {
        if (name != null && name.contains(WILDERNESS_NAME_SEPARATOR)) {
            throw new IllegalArgumentException("地点名不能包含「" + WILDERNESS_NAME_SEPARATOR
                    + "」——该符号是野外程序化地名的专用分隔符（如「青松岭·甲乙」），"
                    + "用它会让已知世界地点与野外节点在名字上无法区分");
        }
    }


}