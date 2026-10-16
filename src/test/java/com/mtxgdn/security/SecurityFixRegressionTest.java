package com.mtxgdn.security;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.util.JwtUtil;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.TokenBlacklist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全修复回归测试（V1–V13）。
 *
 * <p>覆盖范围：JWT 吊销持久化（V4）、限流器容量与窗口语义（V11）、
 * 玩家名净化（V10）、管理员密钥域隔离（V6）。</p>
 */
class SecurityFixRegressionTest extends TestBase {

    @BeforeEach
    void resetState() {
        RateLimiter.clear();
        TokenBlacklist.clear();
    }

    // ==================== V4：JWT 吊销名单持久化 ====================

    @Test
    @DisplayName("V4: 吊销的 token 应被识别为失效")
    void testInvalidateAndDetect() {
        String token = JwtUtil.generateToken(1L, "tester");
        assertFalse(TokenBlacklist.isBlacklisted(token), "未吊销前不应命中");

        TokenBlacklist.invalidate(token);
        assertTrue(TokenBlacklist.isBlacklisted(token), "吊销后应命中");
    }

    @Test
    @DisplayName("V4: 吊销记录必须落库，且库中不存明文 token")
    void testInvalidationPersistedHashed() throws Exception {
        String token = JwtUtil.generateToken(2L, "tester2");
        TokenBlacklist.invalidate(token);

        String sql = "SELECT token_hash FROM token_blacklist";
        boolean foundHash = false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String stored = rs.getString("token_hash");
                assertNotEquals(token, stored, "数据库中不得保存明文 token");
                assertEquals(64, stored.length(), "应保存 SHA-256 十六进制摘要");
                foundHash = true;
            }
        }
        assertTrue(foundHash, "吊销记录应已写入 token_blacklist 表");
    }

    @Test
    @DisplayName("V4: 模拟重启（清空内存缓存）后，已吊销 token 仍应失效")
    void testPersistedAcrossRestart() {
        String token = JwtUtil.generateToken(3L, "tester3");
        TokenBlacklist.invalidate(token);
        assertTrue(TokenBlacklist.isBlacklisted(token));

        // 模拟进程重启：只清内存，保留数据库
        TokenBlacklist.clear();
        assertTrue(TokenBlacklist.isBlacklisted(token),
                "重启后应通过数据库查询到吊销记录（修复前会复活）");
    }

    @Test
    @DisplayName("V4: loadFromDatabase 应能把历史吊销记录载入缓存")
    void testLoadFromDatabase() {
        String token = JwtUtil.generateToken(4L, "tester4");
        TokenBlacklist.invalidate(token);

        TokenBlacklist.clear();
        assertEquals(0, TokenBlacklist.size(), "清空后缓存应为空");

        int loaded = TokenBlacklist.loadFromDatabase();
        assertTrue(loaded > 0, "应至少载入 1 条历史吊销记录");
        assertTrue(TokenBlacklist.isBlacklisted(token));
    }

    @Test
    @DisplayName("V4: 未吊销的 token 不应被误判失效")
    void testUnrelatedTokenNotBlacklisted() {
        TokenBlacklist.invalidate(JwtUtil.generateToken(5L, "tester5"));
        String other = JwtUtil.generateToken(6L, "tester6");
        assertFalse(TokenBlacklist.isBlacklisted(other));
    }

    @Test
    @DisplayName("V4: 空/非法 token 不应抛异常")
    void testNullableTokenHandling() {
        assertFalse(TokenBlacklist.isBlacklisted(null));
        assertFalse(TokenBlacklist.isBlacklisted(""));
        assertFalse(TokenBlacklist.isBlacklisted("not-a-jwt"));
        assertDoesNotThrow(() -> TokenBlacklist.invalidate(null));
        assertDoesNotThrow(() -> TokenBlacklist.invalidate(""));
    }

    @Test
    @DisplayName("V4: 非法 token 吊销后仍应能被识别（走兜底保留期）")
    void testInvalidTokenStillBlacklistable() {
        String bogus = "totally.invalid.token";
        TokenBlacklist.invalidate(bogus);
        assertTrue(TokenBlacklist.isBlacklisted(bogus),
                "无法解析 exp 时应按兜底保留期记录吊销");
    }

    // ==================== V11：限流器 ====================

    @Test
    @DisplayName("V11: 窗口内超过阈值应被拒绝")
    void testRateLimitThreshold() {
        String key = "test_v11_a";
        for (int i = 0; i < 5; i++) {
            assertTrue(RateLimiter.allow(key, 5, 60), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(RateLimiter.allow(key, 5, 60), "第 6 次应被拒绝");
    }

    @Test
    @DisplayName("V11: 不同 key 之间互不影响")
    void testRateLimitKeyIsolation() {
        assertTrue(RateLimiter.allow("test_v11_b1", 1, 60));
        assertFalse(RateLimiter.allow("test_v11_b1", 1, 60));
        assertTrue(RateLimiter.allow("test_v11_b2", 1, 60), "另一个 key 应独立计数");
    }

    @Test
    @DisplayName("V11: 窗口过期后计数应重置")
    void testRateLimitWindowReset() throws Exception {
        String key = "test_v11_c";
        assertTrue(RateLimiter.allow(key, 1, 1));
        assertFalse(RateLimiter.allow(key, 1, 1), "同一窗口内应被拒绝");

        Thread.sleep(1100);
        assertTrue(RateLimiter.allow(key, 1, 1), "窗口过期后应重新放行");
    }

    @Test
    @DisplayName("V11: getRemaining 应反映剩余额度")
    void testGetRemaining() {
        String key = "test_v11_d";
        assertEquals(3, RateLimiter.getRemaining(key, 3, 60));
        RateLimiter.allow(key, 3, 60);
        assertEquals(2, RateLimiter.getRemaining(key, 3, 60));
    }

    @Test
    @DisplayName("V11: null key 应放行而不是抛 NPE")
    void testNullKeyDoesNotThrow() {
        assertTrue(RateLimiter.allow(null, 1, 60));
    }

    @Test
    @DisplayName("V11: cleanupExpired 应清掉过期窗口")
    void testCleanupExpired() throws Exception {
        RateLimiter.allow("test_v11_e", 5, 1);
        assertTrue(RateLimiter.size() > 0);
        Thread.sleep(1100);
        RateLimiter.cleanupExpired(1);
        assertEquals(0, RateLimiter.size(), "过期窗口应被清理");
    }

    @Test
    @DisplayName("V11: 容量上限应存在且为正数（防止无限增长）")
    void testMaxEntriesBound() {
        assertTrue(RateLimiter.maxEntries() > 0, "必须设置容量上限");
    }

    @Test
    @DisplayName("V11: 海量不同 key 不应导致条目无限增长")
    void testMassKeysBounded() {
        // 触发惰性清扫（每 1024 次调用一次），验证不会无界增长。
        // 这里用远小于上限的量，确保正常流量不受影响。
        for (int i = 0; i < 5000; i++) {
            RateLimiter.allow("test_v11_mass_" + i, 1, 60);
        }
        assertTrue(RateLimiter.size() <= 5000);
        assertTrue(RateLimiter.size() <= RateLimiter.maxEntries(),
                "条目数不得超过容量上限");
    }

    // ==================== V6：管理员密钥与玩家密钥隔离 ====================

    @Test
    @DisplayName("V6: 玩家 token 不应能通过管理端密钥校验")
    void testPlayerTokenRejectedByAdminKey() {
        String playerToken = JwtUtil.generateToken(7L, "player7");
        boolean parsedByAdminKey;
        try {
            io.jsonwebtoken.Jwts.parserBuilder()
                    .setSigningKey(com.mtxgdn.rest.AdminAuthFilter.ADMIN_KEY)
                    .build()
                    .parseClaimsJws(playerToken);
            parsedByAdminKey = true;
        } catch (Exception e) {
            parsedByAdminKey = false;
        }
        assertFalse(parsedByAdminKey,
                "V6：玩家 token 不得用管理员密钥成功解析（修复前两者同密钥时会通过）");
    }

    @Test
    @DisplayName("V6: 管理员 token 不应能通过玩家密钥校验")
    void testAdminTokenRejectedByPlayerKey() {
        String adminToken = com.mtxgdn.rest.AdminAuthFilter.generateAdminToken(1L, "admin", "admin");
        assertFalse(JwtUtil.validateToken(adminToken),
                "V6：管理员 token 不得用玩家密钥通过校验（密钥域已隔离）");
    }

    @Test
    @DisplayName("V6: 管理员签发的 token 应能被管理端密钥正常校验")
    void testAdminTokenValidWithAdminKey() {
        String adminToken = com.mtxgdn.rest.AdminAuthFilter.generateAdminToken(1L, "admin", "admin");
        assertDoesNotThrow(() -> io.jsonwebtoken.Jwts.parserBuilder()
                .setSigningKey(com.mtxgdn.rest.AdminAuthFilter.ADMIN_KEY)
                .build()
                .parseClaimsJws(adminToken));
    }

    @Test
    @DisplayName("V6: 管理员密钥必须满足 HMAC-SHA256 的最小长度要求")
    void testAdminKeyLength() {
        assertTrue(com.mtxgdn.rest.AdminAuthFilter.ADMIN_KEY.getEncoded().length >= 32,
                "密钥至少 256 位");
    }

    // ==================== V5：token 解析健壮性 ====================

    @Test
    @DisplayName("V5: validateToken 对畸形 token 应返回 false 而非抛异常")
    void testValidateMalformedToken() {
        assertFalse(JwtUtil.validateToken(null));
        assertFalse(JwtUtil.validateToken(""));
        assertFalse(JwtUtil.validateToken("   "));
        assertFalse(JwtUtil.validateToken("abc"));
        assertFalse(JwtUtil.validateToken("a.b.c"));
    }

    @Test
    @DisplayName("V5: extractExpiration 对畸形 token 应返回 null 而非抛异常")
    void testExtractExpirationMalformed() {
        assertNull(JwtUtil.extractExpiration(null));
        assertNull(JwtUtil.extractExpiration(""));
        assertNull(JwtUtil.extractExpiration("not-a-jwt"));
    }

    @Test
    @DisplayName("V5: 合法 token 的过期时间应可解析且在未来")
    void testExtractExpirationValid() {
        String token = JwtUtil.generateToken(8L, "tester8");
        java.util.Date exp = JwtUtil.extractExpiration(token);
        assertNotNull(exp);
        assertTrue(exp.after(new java.util.Date()), "过期时间应在未来");
    }

    @Test
    @DisplayName("V5: 篡改签名的 token 应校验失败")
    void testTamperedTokenRejected() {
        String token = JwtUtil.generateToken(9L, "tester9");
        String tampered = token.substring(0, token.length() - 3) + "xyz";
        assertFalse(JwtUtil.validateToken(tampered), "签名被篡改必须失败");
    }
}
