package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;

/**
 * 管理台 · 野外分片接口（分片总览、手动补货）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：野外<b>不进数据库</b>，
 * 读的是分片文件（{@code data/wilderness/...}），依赖集合与
 * {@link AdminMapResource}（地点/连接/偏置都在库表里）完全不同，
 * 所以按"路径前缀 + 依赖集合"单独成类。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.database.access}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/map/wilderness*} 不受影响。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /map/wilderness} 拼出来的地址与拆分前
 * 逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminWildernessResource {

    /**
     * 手动给野外补货：把已采空的资源补回上限。
     *
     * <p>只补资源，<b>不换地形、不删任何东西</b>：野外是无限且永久的，
     * 已探索的内容不会被刷新掉，这里做的只是"矿重新长出来"。</p>
     */
    @POST
    @Path("/map/wilderness/restock")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response restockWilderness() {
        JsonObject out = new JsonObject();
        try {
            int chunks = ServiceRegistry.getMapService().restockWilderness();
            out.addProperty("code", 200);
            out.addProperty("message", I18n.t("admin.restocked_wilderness_shard_in_place", chunks));
            JsonObject data = new JsonObject();
            data.addProperty("restockedChunks", chunks);
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.wilderness_restock_failed", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /**
     * 野外分片总览。
     *
     * <p>野外不进数据库，所以这里读的是分片文件（{@code data/wilderness/...}）。
     * 只给只读视图：分片一旦被探索就永久固化，管理台不提供删除入口。</p>
     */
    @GET
    @Path("/map/wilderness")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response wildernessOverview(@QueryParam("cx") String cxParam,
                                       @QueryParam("cy") String cyParam) {
        JsonObject out = new JsonObject();
        try {
            var mapService = ServiceRegistry.getMapService();
            JsonObject data = new JsonObject();
            Map<String, Object> stats = mapService.getWildernessStats();
            for (Map.Entry<String, Object> entry : stats.entrySet()) {
                data.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }

            JsonArray chunks = new JsonArray();
            Integer cx = parseCoordinate(cxParam);
            Integer cy = parseCoordinate(cyParam);
            if (cx != null && cy != null) {
                chunks.add(chunkJson(mapService, cx, cy));
            } else {
                // 不给坐标时以原点为中心展示一圈，便于一眼看到"世界铺到哪了"
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        JsonObject c = chunkJson(mapService, x, y);
                        if (c.get("exists").getAsBoolean()) {
                            chunks.add(c);
                        }
                    }
                }
            }
            data.add("chunks", chunks);
            out.addProperty("code", 200);
            out.addProperty("message", "ok");
            out.add("data", data);
        } catch (Exception e) {
            out.addProperty("code", 500);
            out.addProperty("message", I18n.t("admin.failed_to_read_wilderness_shard", e.getMessage()));
        }
        return Response.ok(gson.toJson(out)).build();
    }

    /** 一个分片的只读快照；{@code exists=false} 表示这片从未有人涉足、磁盘上没有文件。 */
    private static JsonObject chunkJson(com.mtxgdn.game.service.MapService mapService, int cx, int cy) {
        JsonObject c = new JsonObject();
        var nodes = mapService.getChunkNodes(cx, cy);
        var chunk = mapService.getChunk(cx, cy);
        boolean exists = chunk.hasAnyExploration() || chunk.resourcesRolled;
        c.addProperty("chunkX", cx);
        c.addProperty("chunkY", cy);
        c.addProperty("exists", exists);
        c.addProperty("pinned", chunk.pinned);
        c.addProperty("gateway", chunk.gateway);
        c.addProperty("exploredNodes", chunk.exploredNodeCount());
        c.addProperty("explorers", chunk.explorerCount());
        c.addProperty("restockAt", chunk.restockAt);
        c.addProperty("resourceEntries", countResources(chunk));
        if (!nodes.isEmpty()) {
            c.addProperty("terrain", nodes.get(0).getRegion());
            c.addProperty("dangerLevel", nodes.get(0).getDangerLevel());
            c.addProperty("dangerName", mapService.getDangerName(nodes.get(0).getDangerLevel()));
            c.addProperty("nodeCount", nodes.size());
        }
        return c;
    }

    private static int countResources(com.mtxgdn.game.map.WildernessChunk chunk) {
        if (chunk.resources == null) {
            return 0;
        }
        int count = 0;
        for (Map<String, com.mtxgdn.game.map.WildernessChunk.Resource> slot : chunk.resources.values()) {
            count += slot.size();
        }
        return count;
    }

    private static Integer parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
