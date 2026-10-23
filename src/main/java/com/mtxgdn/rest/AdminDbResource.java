package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mtxgdn.rest.AdminSupport.gson;
import static com.mtxgdn.rest.AdminSupport.reloadMapCacheIfNeeded;

/**
 * 管理台 · 数据库接口（通用表浏览/增删改、清档/重置、备份与导入）。
 *
 * <p>从 4000+ 行的 {@code AdminResource} 里拆出来的一块：{@code /db/**}、
 * {@code /database/**}、{@code /backup/**} 全部直接对着 {@code DatabaseManager}
 * 操作，是同一套依赖集合，因此按"路径前缀 + 依赖集合"整块搬出来。</p>
 *
 * <p>拆分不夹带任何行为改动 —— 路径、权限码（{@code admin.database.access} /
 * {@code admin.database.clear_players} / {@code admin.database.reset_all}）、
 * 返回结构全部保持原样，前端 {@code /api/admin/db|database|backup*} 不受影响。
 * 通用表管理改到地图表后的缓存热重载仍走
 * {@link AdminSupport#reloadMapCacheIfNeeded(String)}，逻辑一行未动。</p>
 *
 * <p>类级路径 {@code /admin} + 方法级 {@code /db/tables/{tableName}} 拼出来的地址与
 * 拆分前逐字一致（Jersey 的路径拼接规则）。</p>
 */
@Path("/admin")
public class AdminDbResource {

    @POST
    @Path("/database/clear_players")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.clear_players")
    public Response clearPlayerData() {
        System.out.println("[Admin] >>> POST /admin/database/clear_players");
        Map<String, Integer> counts = DatabaseManager.clearPlayerData();

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.hd6ef4b84"));
        result.addProperty("totalDeleted", total);

        JsonObject detail = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            detail.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("detail", detail);

        return Response.ok(gson.toJson(result)).build();
    }

    @POST
    @Path("/database/reset_all")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response resetAllData() {
        System.out.println("[Admin] >>> POST /admin/database/reset_all");
        Map<String, Integer> counts = DatabaseManager.resetAllData();

        ServiceRegistry.getSkillService().insertDefaultSkills();
        ServiceRegistry.getTechniqueService().insertDefaultTechniques();
        ServiceRegistry.getCraftingService().insertDefaultRecipes();

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        result.addProperty("message", I18n.t("admin.he0c1aa7c"));
        result.addProperty("totalDeleted", total);

        JsonObject detail = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            detail.addProperty(entry.getKey(), entry.getValue());
        }
        result.add("detail", detail);

        return Response.ok(gson.toJson(result)).build();
    }

    // ========== 数据库浏览 API ==========

    @GET
    @Path("/db/tables")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getDbTables() {
        try {
            List<String> tables = DatabaseManager.getAllTableNames();
            JsonArray arr = new JsonArray();
            for (String name : tables) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", name);
                try {
                    obj.addProperty("rowCount", DatabaseManager.countTableRows(name));
                } catch (Exception e) {
                    obj.addProperty("rowCount", -1);
                }
                arr.add(obj);
            }
            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("tables", arr);
            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hc24ff258", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getTableData(
            @PathParam("tableName") String tableName,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            List<Map<String, Object>> columns = DatabaseManager.getTableColumns(tableName);
            List<Map<String, Object>> rows = DatabaseManager.queryTableData(tableName, limit, offset);
            int total = DatabaseManager.countTableRows(tableName);

            JsonArray colsArr = new JsonArray();
            for (Map<String, Object> col : columns) {
                JsonObject co = new JsonObject();
                co.addProperty("name", String.valueOf(col.get("name")));
                co.addProperty("type", String.valueOf(col.get("type")));
                co.addProperty("notnull", (Boolean) col.get("notnull"));
                co.addProperty("pk", (Boolean) col.get("pk"));
                colsArr.add(co);
            }

            JsonArray rowsArr = new JsonArray();
            for (Map<String, Object> row : rows) {
                JsonObject ro = new JsonObject();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    Object val = entry.getValue();
                    if (val == null) {
                        ro.add(entry.getKey(), null);
                    } else if (val instanceof Number) {
                        ro.addProperty(entry.getKey(), (Number) val);
                    } else if (val instanceof Boolean) {
                        ro.addProperty(entry.getKey(), (Boolean) val);
                    } else {
                        ro.addProperty(entry.getKey(), String.valueOf(val));
                    }
                }
                rowsArr.add(ro);
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("columns", colsArr);
            result.add("rows", rowsArr);
            result.addProperty("total", total);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h12619b1f", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @GET
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response getTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            Map<String, Object> row = DatabaseManager.getRowById(tableName, id);
            if (row == null) {
                JsonObject err = new JsonObject();
                err.addProperty("code", 404);
                err.addProperty("message", I18n.t("admin.h2d6880bc"));
                return Response.ok(gson.toJson(err)).build();
            }

            JsonObject ro = new JsonObject();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    ro.add(entry.getKey(), null);
                } else if (val instanceof Number) {
                    ro.addProperty(entry.getKey(), (Number) val);
                } else if (val instanceof Boolean) {
                    ro.addProperty(entry.getKey(), (Boolean) val);
                } else {
                    ro.addProperty(entry.getKey(), String.valueOf(val));
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.add("row", ro);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h12619b1f", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response insertTableRow(
            @PathParam("tableName") String tableName,
            String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key : req.keySet()) {
                var elem = req.get(key);
                if (elem.isJsonNull()) {
                    data.put(key, null);
                } else if (elem.getAsJsonPrimitive().isNumber()) {
                    data.put(key, elem.getAsNumber());
                } else if (elem.getAsJsonPrimitive().isBoolean()) {
                    data.put(key, elem.getAsBoolean());
                } else {
                    data.put(key, elem.getAsString());
                }
            }

            long newId = DatabaseManager.insertRow(tableName, data);
            reloadMapCacheIfNeeded(tableName);

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h34edd3d4"));
            result.addProperty("id", newId);
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h00199ba0", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/db/tables/{tableName}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response updateTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id,
            String body) {
        try {
            JsonObject req = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            Map<String, Object> data = new LinkedHashMap<>();
            for (String key : req.keySet()) {
                var elem = req.get(key);
                if (elem.isJsonNull()) {
                    data.put(key, null);
                } else if (elem.getAsJsonPrimitive().isNumber()) {
                    data.put(key, elem.getAsNumber());
                } else if (elem.getAsJsonPrimitive().isBoolean()) {
                    data.put(key, elem.getAsBoolean());
                } else {
                    data.put(key, elem.getAsString());
                }
            }

            int updated = DatabaseManager.updateRow(tableName, id, data);
            if (updated > 0) {
                reloadMapCacheIfNeeded(tableName);
            }

            JsonObject result = new JsonObject();
            if (updated > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.h49113c80"));
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.hd2524528"));
            }
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.hae754cdb", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @DELETE
    @Path("/db/tables/{tableName}/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response deleteTableRow(
            @PathParam("tableName") String tableName,
            @PathParam("id") long id) {
        try {
            int deleted = DatabaseManager.deleteRow(tableName, id);
            if (deleted > 0) {
                reloadMapCacheIfNeeded(tableName);
            }

            JsonObject result = new JsonObject();
            if (deleted > 0) {
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("admin.h7e7ba6b0"));
            } else {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("admin.h2d6880bc"));
            }
            return Response.ok(gson.toJson(result)).build();
        } catch (IllegalArgumentException e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 400);
            err.addProperty("message", e.getMessage());
            return Response.ok(gson.toJson(err)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h4360d4cc", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    // ========== 数据库备份与导入 ==========

    @GET
    @Path("/backup")
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.access")
    public Response backupDatabase() {
        try {
            String json = DatabaseManager.exportAllData();
            return Response.ok(json, MediaType.APPLICATION_JSON)
                    .header("Content-Disposition", "attachment; filename=\"xiuxian_backup_" +
                            java.time.LocalDate.now() + ".json\"")
                    .build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h04e20937", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }

    @POST
    @Path("/backup/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.database.reset_all")
    public Response importBackup(String body) {
        try {
            Map<String, Integer> counts = DatabaseManager.importData(body);

            // 重新初始化默认数据
            ServiceRegistry.getSkillService().insertDefaultSkills();
            ServiceRegistry.getTechniqueService().insertDefaultTechniques();
            ServiceRegistry.getCraftingService().insertDefaultRecipes();

            JsonObject result = new JsonObject();
            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("admin.h7503790c"));

            int total = 0;
            JsonObject detail = new JsonObject();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                detail.addProperty(entry.getKey(), entry.getValue());
                if (!entry.getKey().endsWith("_deleted") && !entry.getKey().endsWith("_errors") && !entry.getKey().endsWith("_delete_error")) {
                    total += entry.getValue();
                }
            }
            result.addProperty("totalImported", total);
            result.add("detail", detail);

            return Response.ok(gson.toJson(result)).build();
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("code", 500);
            err.addProperty("message", I18n.t("admin.h6f8a2d18", e.getMessage()));
            return Response.ok(gson.toJson(err)).build();
        }
    }
}
