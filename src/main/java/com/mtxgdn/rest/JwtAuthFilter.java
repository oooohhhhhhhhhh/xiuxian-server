package com.mtxgdn.rest;

import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.TokenBlacklist;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JwtAuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();

        if (path.startsWith("auth/") || path.startsWith("test/")) {
            System.out.println("[JwtFilter] bypass: " + path);
            return;
        }

        // admin/energy/* 路径支持双鉴权：不强制要求用户 JWT，但如有则设置 userId
        boolean isDualAuthPath = path.startsWith("admin/energy/");

        if (path.startsWith("admin/") && !isDualAuthPath) {
            System.out.println("[JwtFilter] bypass: " + path);
            return;
        }

        String authorizationHeader = requestContext.getHeaderString("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            if (isDualAuthPath) {
                return; // 无 token，放行（AdminAuthFilter 可能已通过 admin JWT 鉴权）
            }
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"未登录或登录已过期\"}")
                    .build());
            return;
        }

        String token = authorizationHeader.substring("Bearer ".length()).trim();

        if (TokenBlacklist.isBlacklisted(token)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"Token已失效，请重新登录\"}")
                    .build());
            return;
        }

        if (!JwtUtil.validateToken(token)) {
            if (isDualAuthPath) {
                return; // 用户 token 无效，放行（可能是 admin JWT，AdminAuthFilter 已处理）
            }
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"Token无效或已过期，请重新登录\"}")
                    .build());
            return;
        }

        requestContext.setProperty("token", token);
        requestContext.setProperty("userId", JwtUtil.extractUserId(token));
    }
}
