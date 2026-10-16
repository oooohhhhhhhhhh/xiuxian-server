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
        if (path == null) {
            path = "";
        }

        // V9 修复：原实现把 "test/" 列入免鉴权白名单，调试端点 /api/test/test 可匿名访问。
        // 这里移除该白名单（端点本身保留，但现在需要一个合法的玩家 token）。
        //
        // 同时把前缀匹配收紧为"路径段"匹配：原实现用 path.startsWith("auth/")，
        // 会让 "authXxx/..." 这类路径被误判进白名单。
        if (isBypassPath(path, "auth") || isBypassPath(path, "install") || isBypassPath(path, "mc-command")) {
            System.out.println("[JwtFilter] bypass: " + path);
            return;
        }

        // admin/energy/* 路径支持双鉴权：不强制要求用户 JWT，但如有则设置 userId
        boolean isDualAuthPath = isBypassPath(path, "admin/energy");

        if (isBypassPath(path, "admin") && !isDualAuthPath) {
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

    /**
     * V9：按"路径段"判断是否命中白名单前缀。
     *
     * <p>要求前缀后紧跟 {@code /}（或路径与该段完全相等），
     * 避免 {@code adminXxx}、{@code authEvil} 这类路径因 {@code startsWith} 被误放行。</p>
     */
    private static boolean isBypassPath(String path, String segment) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return path.equals(segment) || path.startsWith(segment + "/");
    }
}
