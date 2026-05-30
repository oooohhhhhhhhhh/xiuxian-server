package com.mtxgdn.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

public class JwtUtil {

    private static final SecretKey SECRET_KEY = initSecretKey();

    private static SecretKey initSecretKey() {
        String configured = AppConfig.get("jwt.secret");
        if (configured != null && !configured.isEmpty()) {
            return Keys.hmacShaKeyFor(configured.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] randomBytes = new byte[64];
        new SecureRandom().nextBytes(randomBytes);
        SecretKey key = Keys.hmacShaKeyFor(randomBytes);
        System.err.println("[JwtUtil] 未配置 jwt.secret，已生成随机密钥。重启后所有 Token 将失效，请尽快在 application.yml 中设置 jwt.secret。");
        return key;
    }

    private static final long EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L;

    public static String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + EXPIRATION_MS);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(SECRET_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(SECRET_KEY)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Long extractUserId(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return Long.parseLong(claims.getSubject());
    }

    public static String extractUsername(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(SECRET_KEY)
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claims.get("username", String.class);
    }
}
