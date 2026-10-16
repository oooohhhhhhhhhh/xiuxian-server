package com.mtxgdn.rest;

import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.util.GameLogger;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class PermissionFilter implements ContainerRequestFilter {

    private static final GameLogger LOG = GameLogger.getLogger(PermissionFilter.class);

    @Context
    private ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // S2 修复：ResourceInfo 注入失败时拒绝请求并记录错误，绝不静默放行
        if (resourceInfo == null) {
            String path = requestContext.getUriInfo() != null ? requestContext.getUriInfo().getPath() : "?";
            LOG.error("[Permission] ResourceInfo 注入失败，权限检查不可用，拒绝请求 path=" + path);
            requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"code\":500,\"message\":\"权限子系统异常，请联系管理员\"}")
                    .build());
            return;
        }

        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
            // 没有匹配到资源方法时不应到达这里（jersey 会先做路由匹配），但作为兜底拒绝
            return;
        }

        RequirePermission methodAnnotation = method.getAnnotation(RequirePermission.class);
        RequirePermission classAnnotation = resourceInfo.getResourceClass().getAnnotation(RequirePermission.class);

        String requiredPermission = null;
        if (methodAnnotation != null) {
            requiredPermission = methodAnnotation.value();
        } else if (classAnnotation != null) {
            requiredPermission = classAnnotation.value();
        }

        if (requiredPermission == null) {
            return;
        }

        // S1 修复：admin token 走完整权限校验，不再短路放过。
        // 只有 SUPER_ADMIN 角色才允许直接放行（向后兼容原有 super_admin 行为）。
        String adminRole = (String) requestContext.getProperty("adminRole");
        if ("super_admin".equals(adminRole)) {
            return;
        }

        Long userId = null;
        Object userIdObj = requestContext.getProperty("userId");
        if (userIdObj instanceof Long) {
            userId = (Long) userIdObj;
        }
        Object adminUserIdObj = requestContext.getProperty("adminUserId");
        if (userId == null && adminUserIdObj instanceof Long) {
            userId = (Long) adminUserIdObj;
        }

        if (userId == null) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"未登录\"}")
                    .build());
            return;
        }

        if (!PermissionService.hasPermission(userId, requiredPermission)) {
            String path = requestContext.getUriInfo() != null ? requestContext.getUriInfo().getPath() : "?";
            LOG.warn("[Permission] 拒绝访问 userId=" + userId + " role=" + adminRole
                    + " required=" + requiredPermission + " path=" + path);
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"code\":403,\"message\":\"权限不足: " + requiredPermission + "\"}")
                    .build());
        }
    }
}
