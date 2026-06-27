package com.mtxgdn.rest;

import com.mtxgdn.util.AppConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AdminAuthFilter implements ContainerRequestFilter {

    private static final String ADMIN_SECRET = AppConfig.get("jwt.secret", "xiuxian-admin-secret");
    private static final SecretKey ADMIN_KEY = Keys.hmacShaKeyFor(ADMIN_SECRET.getBytes(StandardCharsets.UTF_8));
    private static final long ADMIN_TOKEN_EXPIRY = 12 * 60 * 60 * 1000L;

    private static final String ADMIN_USERNAME = initAdminUsername();
    private static final String ADMIN_PASSWORD = initAdminPassword();

    private static String initAdminUsername() {
        String username = AppConfig.get("admin.username");
        if (username != null && !username.isEmpty()) {
            return username;
        }
        System.err.println("[AdminAuth] 未配置 admin.username，管理后台将无法登录。请在 application.yml 中设置 admin.username 和 admin.password。");
        return "";
    }

    private static String initAdminPassword() {
        String password = AppConfig.get("admin.password");
        if (password != null && !password.isEmpty()) {
            return password;
        }
        System.err.println("[AdminAuth] 未配置 admin.password，管理后台将无法登录。请在 application.yml 中设置 admin.username 和 admin.password。");
        return "";
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();

        if (!path.startsWith("admin/")) {
            return;
        }

        String method = ctx.getMethod();
        System.out.println("[AdminAuth] " + method + " " + path);

        if (path.equals("admin/login") && "POST".equalsIgnoreCase(method)) {
            return;
        }

        // admin/energy/* 路径支持双鉴权（admin JWT 或 用户 JWT + admin.status 权限）
        boolean isDualAuthPath = path.startsWith("admin/energy/");

        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (isDualAuthPath) {
                return; // 放行给 JwtAuthFilter / UnifiedRestResource 处理
            }
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"请先登录管理后台\"}")
                    .build());
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(ADMIN_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String role = claims.get("role", String.class);
            if (!"admin".equals(role)) {
                ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"code\":403,\"message\":\"无管理权限\"}")
                        .build());
                return;
            }
            // 标记 admin JWT 鉴权成功
            ctx.setProperty("adminAuthenticated", true);
        } catch (Exception e) {
            if (isDualAuthPath) {
                return; // admin JWT 无效，放行给 JwtAuthFilter 尝试用户 JWT
            }
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"管理Token无效或已过期\"}")
                    .build());
        }
    }

    public static String generateAdminToken(String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + ADMIN_TOKEN_EXPIRY);

        return Jwts.builder()
                .setSubject("admin")
                .claim("username", username)
                .claim("role", "admin")
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(ADMIN_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static boolean validateCredentials(String username, String password) {
        return ADMIN_USERNAME.equals(username) && ADMIN_PASSWORD.equals(password);
    }
}
