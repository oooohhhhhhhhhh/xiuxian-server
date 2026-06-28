package com.mtxgdn.rest;

import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.permission.RequirePermission;
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

    @Context
    private ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (resourceInfo == null) {
            return;
        }

        Method method = resourceInfo.getResourceMethod();
        if (method == null) {
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

        // admin JWT 鉴权通过（AdminAuthFilter 设置），超级管理员拥有所有权限
        Object adminAuthObj = requestContext.getProperty("adminAuthenticated");
        if (Boolean.TRUE.equals(adminAuthObj)) {
            return;
        }

        Object userIdObj = requestContext.getProperty("userId");
        if (userIdObj == null) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"未登录\"}")
                    .build());
            return;
        }

        long userId = (Long) userIdObj;

        if (!PermissionService.hasPermission(userId, requiredPermission)) {
            System.out.println("[Permission] 拒绝 access, userId=" + userId + " permission=" + requiredPermission);
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"code\":403,\"message\":\"权限不足: " + requiredPermission + "\"}")
                    .build());
        }
    }
}
