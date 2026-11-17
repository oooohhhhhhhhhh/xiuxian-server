package com.mtxgdn.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.audit.AuditService;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.permission.RequirePermission;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 · 审计日志（取代原来的「玩家轨迹」）。
 *
 * <p>审计合并了玩家 / 管理员 / 系统 / 适配器 / 插件五类主体，所以页面不再叫「玩家轨迹」，
 * 而是按主体筛的「审计日志」。</p>
 */
@Path("/admin/audit-logs")
public class AdminAuditResource extends BaseResource {

    private static final int EXPORT_LIMIT = 10000;

    /** 分页查询。 */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RequirePermission("admin.logs.view")
    public Response query(@QueryParam("actorType") @DefaultValue("") String actorType,
                          @QueryParam("action") @DefaultValue("") String action,
                          @QueryParam("actorId") @DefaultValue("") String actorId,
                          @QueryParam("targetId") @DefaultValue("") String targetId,
                          @QueryParam("result") @DefaultValue("") String result,
                          @QueryParam("keyword") @DefaultValue("") String keyword,
                          @QueryParam("from") Long from,
                          @QueryParam("to") Long to,
                          @QueryParam("limit") @DefaultValue("50") int limit,
                          @QueryParam("offset") @DefaultValue("0") int offset) {
        try {
            AuditService audit = AuditService.getInstance();
            List<Map<String, Object>> rows = audit.query(
                    actorType, action, actorId, targetId, result, keyword, from, to, limit, offset);
            int total = audit.count(actorType, action, actorId, targetId, result, keyword, from, to);

            JsonArray arr = new JsonArray();
            for (Map<String, Object> row : rows) {
                arr.add(toJson(row));
            }
            JsonObject resp = new JsonObject();
            resp.addProperty("code", 200);
            resp.add("logs", arr);
            resp.addProperty("total", total);
            return Response.ok(resp.toString()).build();
        } catch (RuntimeException e) {
            return error(500, I18n.t("admin.query_failed", e.getMessage()));
        }
    }

    /** 导出 CSV（最多 1 万条，足够排查；更大范围用归档文件）。 */
    @GET
    @Path("/export")
    @Produces("text/csv")
    @RequirePermission("admin.logs.view")
    public Response export(@QueryParam("actorType") @DefaultValue("") String actorType,
                           @QueryParam("action") @DefaultValue("") String action,
                           @QueryParam("actorId") @DefaultValue("") String actorId,
                           @QueryParam("targetId") @DefaultValue("") String targetId,
                           @QueryParam("result") @DefaultValue("") String result,
                           @QueryParam("keyword") @DefaultValue("") String keyword,
                           @QueryParam("from") Long from,
                           @QueryParam("to") Long to) {
        try {
            List<Map<String, Object>> rows = AuditService.getInstance().query(
                    actorType, action, actorId, targetId, result, keyword, from, to, EXPORT_LIMIT, 0);
            StringBuilder csv = new StringBuilder();
            csv.append("id,ts,actor_type,actor_id,actor_name,action,target_type,target_id,result,ip,trace_id,detail\n");
            for (Map<String, Object> row : rows) {
                csv.append(row.get("id")).append(',')
                        .append(row.get("ts")).append(',')
                        .append(csv(row.get("actor_type"))).append(',')
                        .append(csv(row.get("actor_id"))).append(',')
                        .append(csv(row.get("actor_name"))).append(',')
                        .append(csv(row.get("action"))).append(',')
                        .append(csv(row.get("target_type"))).append(',')
                        .append(csv(row.get("target_id"))).append(',')
                        .append(csv(row.get("result"))).append(',')
                        .append(csv(row.get("ip"))).append(',')
                        .append(csv(row.get("trace_id"))).append(',')
                        .append(csv(row.get("detail"))).append('\n');
            }
            String filename = URLEncoder.encode("audit-logs.csv", StandardCharsets.UTF_8);
            return Response.ok("\uFEFF" + csv)
                    .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                    .build();
        } catch (RuntimeException e) {
            return error(500, "导出失败: " + e.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static JsonObject toJson(Map<String, Object> row) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", num(row.get("id")));
        obj.addProperty("ts", num(row.get("ts")));
        obj.addProperty("actorType", str(row.get("actor_type")));
        obj.addProperty("actorId", str(row.get("actor_id")));
        obj.addProperty("actorName", str(row.get("actor_name")));
        obj.addProperty("action", str(row.get("action")));
        obj.addProperty("targetType", str(row.get("target_type")));
        obj.addProperty("targetId", str(row.get("target_id")));
        obj.addProperty("detail", str(row.get("detail")));
        obj.addProperty("ip", str(row.get("ip")));
        obj.addProperty("result", str(row.get("result")));
        obj.addProperty("traceId", str(row.get("trace_id")));
        return obj;
    }

    private static long num(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** CSV 单元格转义：包裹双引号并转义内部引号。 */
    private static String csv(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    private static Response error(int status, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("code", status);
        out.addProperty("message", message);
        return Response.status(status).entity(out.toString()).build();
    }
}
