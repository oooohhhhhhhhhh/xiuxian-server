package com.mtxgdn.rest;

import com.mtxgdn.util.JwtUtil;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public class JwtAuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();
        System.out.println("[JwtFilter] " + method + " " + path);

        if (path.startsWith("auth/") || path.startsWith("test/") || path.startsWith("admin/")) {
            System.out.println("[JwtFilter] bypass: " + path);
            return;
        }

        String authorizationHeader = requestContext.getHeaderString("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"未登录或登录已过期\"}")
                    .build());
            return;
        }

        String token = authorizationHeader.substring("Bearer ".length()).trim();

        if (!JwtUtil.validateToken(token)) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"Token无效或已过期，请重新登录\"}")
                    .build());
            return;
        }

        requestContext.setProperty("token", token);
        requestContext.setProperty("userId", JwtUtil.extractUserId(token));
    }
}
