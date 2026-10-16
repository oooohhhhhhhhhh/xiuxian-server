package com.mtxgdn.security;

import com.mtxgdn.TestBase;
import com.mtxgdn.util.RateLimiter;
import com.mtxgdn.util.TextSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 / V3 / V10 回归测试：输入净化与多层限流语义。
 */
class InputAndRateLimitTest extends TestBase {

    @BeforeEach
    void resetState() {
        RateLimiter.clear();
    }

    // ==================== V10：角色名净化 ====================

    @Test
    @DisplayName("V10: 超长角色名应被截断到 16 字符")
    void testLongNameTruncated() {
        String longName = "这是一个非常非常非常非常长的角色名字测试";
        String sanitized = TextSanitizer.sanitizePlayerName(longName);
        assertTrue(sanitized.length() <= 16, "应被截断到 16 字符以内，实际: " + sanitized.length());
    }

    @Test
    @DisplayName("V10: 角色名中的 HTML/注入字符应被剥离")
    void testDangerousCharsStripped() {
        String dirty = "<script>alert('x')</script>";
        String sanitized = TextSanitizer.sanitizePlayerName(dirty);
        // 该净化器按字符剥离（不是 HTML 解析），因此标签字符消失、
        // 标签内文字会以纯文本残留 —— 这是安全的（无法构成标签）。
        assertFalse(sanitized.contains("<"), "不应含 <");
        assertFalse(sanitized.contains(">"), "不应含 >");
        assertFalse(sanitized.contains("'"), "不应含 '");
        assertFalse(sanitized.contains("\""), "不应含 \"");
        assertFalse(sanitized.contains("&"), "不应含 &");
        assertFalse(sanitized.contains("<script>"), "不应保留可执行的标签形态");
    }

    @Test
    @DisplayName("V10: 净化后长度仍受 16 字符上限约束")
    void testSanitizedLengthBoundedEvenWithDangerousChars() {
        // 即便输入大量危险字符，净化结果也不得超过上限
        String dirty = "<>\"'&".repeat(50);
        String sanitized = TextSanitizer.sanitizePlayerName(dirty);
        assertTrue(sanitized.length() <= 16);
    }

    @Test
    @DisplayName("V10: 换行/制表符应被剥离（防止日志注入与显示错乱）")
    void testControlCharsStripped() {
        String sanitized = TextSanitizer.sanitizePlayerName("坏\n名\r字\t");
        assertFalse(sanitized.contains("\n"));
        assertFalse(sanitized.contains("\r"));
        assertFalse(sanitized.contains("\t"));
    }

    @Test
    @DisplayName("V10: 纯特殊字符的名字应被识别为空（调用方须拒绝）")
    void testAllSpecialCharsBecomesEmpty() {
        assertEquals("", TextSanitizer.sanitizePlayerName("<>\"'&"));
        assertEquals("", TextSanitizer.sanitizePlayerName("   "));
        assertEquals("", TextSanitizer.sanitizePlayerName(null));
    }

    @Test
    @DisplayName("V10: 正常中文/英文角色名应保持不变")
    void testNormalNamesUnchanged() {
        assertEquals("萧炎", TextSanitizer.sanitizePlayerName("萧炎"));
        assertEquals("PlayerOne", TextSanitizer.sanitizePlayerName("PlayerOne"));
        assertEquals("云逸", TextSanitizer.sanitizePlayerName("  云逸  "), "应去除首尾空白");
    }

    // ==================== V2/V3：多层限流语义 ====================

    @Test
    @DisplayName("V3: 按来源限流应独立于按用户名限流（可叠加拦截撞库）")
    void testPerSourceLimitIndependentOfUsername() {
        // 模拟攻击者用 100 个不同用户名撞库：按用户名桶每次都放行，
        // 但按来源桶（20/60s）必须拦住。
        String sourceBucket = "login_ip:203.0.113.7";
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            // 每个用户名都是新的 → 按用户名桶总是放行
            boolean perUsername = RateLimiter.allow("login_user_" + i, 5, 60);
            assertTrue(perUsername, "每个新用户名首次尝试都会被用户名桶放行");
            // 来源桶正确拦截
            if (RateLimiter.allow(sourceBucket, 20, 60)) {
                allowed++;
            }
        }
        assertEquals(20, allowed,
                "V3：来源桶必须把撞库速率压到 20 次/分钟，无论换多少用户名");
    }

    @Test
    @DisplayName("V2: 邮件发送多层桶（分/时/全局）应逐级生效")
    void testSendCodeMultiLayerBuckets() {
        String bucket = "198.51.100.9";

        // 第一层：来源 10/分
        int perMinute = 0;
        for (int i = 0; i < 15; i++) {
            if (RateLimiter.allow("send_code_ip:" + bucket, 10, 60)) perMinute++;
        }
        assertEquals(10, perMinute, "分桶应限制为 10");

        // 第二层：来源 30/时（独立于分桶）
        int perHour = 0;
        for (int i = 0; i < 40; i++) {
            if (RateLimiter.allow("send_code_hour:" + bucket, 30, 3600)) perHour++;
        }
        assertEquals(30, perHour, "时桶应限制为 30");

        // 第三层：全局兜底（多来源共享）
        int global = 0;
        for (int i = 0; i < 400; i++) {
            if (RateLimiter.allow("send_code_global", 300, 3600)) global++;
        }
        assertEquals(300, global, "全局桶应限制为 300，封顶分布式轰炸成本");
    }

    @Test
    @DisplayName("V2: 不同来源应有各自独立的邮件配额")
    void testSendCodeDifferentSourcesIndependent() {
        assertTrue(RateLimiter.allow("send_code_ip_iso:1.1.1.1", 10, 60));
        assertTrue(RateLimiter.allow("send_code_ip_iso:2.2.2.2", 10, 60));
        assertTrue(RateLimiter.allow("send_code_ip_iso:3.3.3.3", 10, 60));
    }

    // ==================== 聊天内容净化（既有行为的回归保护） ====================

    @Test
    @DisplayName("聊天内容应做 HTML 转义并限制长度")
    void testChatContentSanitized() {
        String sanitized = TextSanitizer.sanitizeChatContent("<b>hi</b>");
        assertTrue(sanitized.contains("&lt;"), "应转义 <");
        assertTrue(sanitized.contains("&gt;"), "应转义 >");

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) huge.append("字");
        assertTrue(TextSanitizer.sanitizeChatContent(huge.toString()).length() <= 500,
                "聊天内容应截断到 500 字符");
    }

    @Test
    @DisplayName("null 输入不应导致 NPE")
    void testSanitizerNullSafety() {
        assertEquals("", TextSanitizer.sanitizeContent(null));
        assertEquals("", TextSanitizer.sanitizeChatContent(null));
        assertEquals("", TextSanitizer.sanitizePlayerName(null));
    }
}
