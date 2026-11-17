package com.mtxgdn.rest;

import com.mtxgdn.audit.AuditActions;
import com.mtxgdn.audit.AuditActor;
import com.mtxgdn.audit.AuditResult;
import com.mtxgdn.audit.AuditService;
import com.mtxgdn.audit.AuditWriteException;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * 审计过滤器：管理后台的写操作与「玩家数据」读取。
 *
 * <h3>为什么用过滤器而不是逐个端点埋点</h3>
 * <p>后台有两百多个端点，逐个加审计调用既容易漏、也会让每个资源类都依赖审计服务。
 * 这里按「路径 + 方法」统一识别：写操作 = {@code /admin} 下非 GET 请求；
 * 敏感读 = 玩家/用户/数据库/自身资料与 {@code /api/game} 的 GET。
 * 权限、业务规则仍由各资源自己负责，过滤器只负责留痕。</p>
 *
 * <h3>fail-closed（写操作）</h3>
 * <p>写请求先落一条「进行中」审计，落库失败直接 503 拒绝执行 —— 这是产品要求：
 * 审计写不进去，操作就不允许发生。响应回来后再回填结果。</p>
 *
 * <h3>fail-open（读操作）</h3>
 * <p>读请求只留痕、不阻断：审计库故障不应该让「翻一页列表」也失败。</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 20)
public class AuditFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** 该请求对应的审计行 ID；0 表示没有审计。 */
    private static final String PROP_AUDIT_ID = "audit.logId";
    /** 写操作需要在响应阶段回填结果。 */
    private static final String PROP_AUDIT_WRITE = "audit.isWrite";
    /** 请求开始时间（毫秒），用于记录耗时。 */
    private static final String PROP_AUDIT_START = "audit.startMs";
    /** 审计详情里的「方法 + 路径」。 */
    private static final String PROP_AUDIT_DESC = "audit.desc";

    private static final String[] SENSITIVE_READ_PREFIXES = {
            "admin/players", "admin/users", "admin/database", "admin/me", "game/"
    };

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String method = ctx.getMethod();
        if (path == null || path.isEmpty()) {
            return;
        }
        boolean write = isAdminWrite(path, method);
        boolean read = isSensitiveRead(path, method);
        if (!write && !read) {
            return;
        }

        Actor actor = resolveActor(ctx);
        String ip = resolveIp(ctx);
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String desc = method + " /" + path + queryString(ctx);

        ctx.setProperty(PROP_AUDIT_START, System.currentTimeMillis());
        ctx.setProperty(PROP_AUDIT_DESC, desc);

        if (write) {
            try {
                long id = AuditService.getInstance().begin(actor.type, actor.id, actor.name,
                        AuditActions.ADMIN_REQUEST, "HTTP", path, desc, ip, traceId);
                ctx.setProperty(PROP_AUDIT_ID, id);
                ctx.setProperty(PROP_AUDIT_WRITE, Boolean.TRUE);
            } catch (AuditWriteException e) {
                // fail-closed：审计写不进去，拒绝执行
                ctx.abortWith(Response.status(503)
                        .entity("{\"code\":503,\"message\":\"审计日志写入失败，操作已拒绝\"}")
                        .type("application/json").build());
            }
        } else {
            // fail-open：读操作只留痕，写不进去也不阻断
            long id = AuditService.getInstance().recordQuietly(actor.type, actor.id, actor.name,
                    AuditActions.DATA_READ, "HTTP", path, desc, ip, AuditResult.OK, traceId);
            ctx.setProperty(PROP_AUDIT_ID, id);
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object idObj = request.getProperty(PROP_AUDIT_ID);
        if (!(idObj instanceof Long id) || id <= 0) {
            return;
        }
        Object writeObj = request.getProperty(PROP_AUDIT_WRITE);
        if (!Boolean.TRUE.equals(writeObj)) {
            return; // 读操作写入时已经是完整记录
        }
        int status = response.getStatus();
        AuditResult result = status >= 500 ? AuditResult.FAIL
                : status >= 400 ? AuditResult.DENY : AuditResult.OK;
        Object startObj = request.getProperty(PROP_AUDIT_START);
        long elapsed = startObj instanceof Long start ? System.currentTimeMillis() - start : -1;
        Object descObj = request.getProperty(PROP_AUDIT_DESC);
        String desc = descObj == null ? "" : descObj.toString();
        AuditService.getInstance().finish(id, result, desc + " -> " + status
                + (elapsed >= 0 ? " (" + elapsed + "ms)" : ""));
    }

    // ==================== 规则 ====================

    static boolean isAdminWrite(String path, String method) {
        if (!path.startsWith("admin/")) {
            return false;
        }
        return !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }

    static boolean isSensitiveRead(String path, String method) {
        if (!"GET".equals(method)) {
            return false;
        }
        for (String prefix : SENSITIVE_READ_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 审计主体：优先管理员身份，其次玩家身份，都没有就记 SYSTEM。 */
    private static Actor resolveActor(ContainerRequestContext ctx) {
        Object adminUserId = ctx.getProperty("adminUserId");
        Object adminRole = ctx.getProperty("adminRole");
        if (adminUserId instanceof Number n) {
            String role = adminRole == null ? "" : String.valueOf(adminRole);
            return new Actor(AuditActor.ADMIN, String.valueOf(n.longValue()),
                    role.isEmpty() ? "admin" : "admin:" + role);
        }
        Object playerUserId = ctx.getProperty("userId");
        if (playerUserId instanceof Number n) {
            Object username = ctx.getProperty("username");
            return new Actor(AuditActor.PLAYER, String.valueOf(n.longValue()),
                    username == null ? "" : String.valueOf(username));
        }
        return new Actor(AuditActor.SYSTEM, "anonymous", "anonymous");
    }

    /** 客户端 IP：只在信任反代时读 X-Forwarded-For，否则用连接地址。 */
    private static String resolveIp(ContainerRequestContext ctx) {
        try {
            return ClientIp.remoteAddress(ctx, null);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String queryString(ContainerRequestContext ctx) {
        String raw = ctx.getUriInfo().getRequestUri().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // 查询串里可能有手机号/邮箱，截断即可；审计不追求完整参数
        return "?" + (raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
    }

    private record Actor(AuditActor type, String id, String name) {
    }
}
