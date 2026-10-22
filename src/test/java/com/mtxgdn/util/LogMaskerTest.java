package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天日志不得留下明文口令。
 *
 * <p><b>缺陷形态</b>：所有聊天通道都会把收到的原始文本整条写进日志
 * （{@code OneBotWebSocketServer} 的私聊/群聊、{@code QqOfficialAdapter} 的私聊、
 * Minecraft 公屏、WebSocket 聊天），而玩家经常直接在聊天框里打
 * {@code /注册 我的密码123}、{@code /绑定 账号 密码}。于是明文口令落进了
 * {@code log/} 下的日志文件与 OneBot 消息日志里，任何能读日志的人都能直接拿去登录。</p>
 *
 * <p>本测试同时守住"别过度打码"：正常聊天必须原样保留，否则日志就没法用来排查问题了。</p>
 */
class LogMaskerTest {

    @Test
    @DisplayName("注册/登录/绑定指令的口令参数必须打码")
    void masksCredentialArguments() {
        assertMasked("/注册 mySecret123", "mySecret123");
        assertMasked("/注册 我的密码123", "我的密码123");
        assertMasked("/login hunter2", "hunter2");
        assertMasked("!bind 账号 hunter2", "hunter2");
        assertMasked("/登录 张三 abcdef", "abcdef");
        assertMasked("/改密 oldpass newpass", "oldpass");
        assertMasked("/绑定 123456", "123456");
        assertMasked("register myPass!2024", "myPass!2024");
    }

    @Test
    @DisplayName("key=value / key: value 形式的凭据必须打码")
    void masksKeyValuePairs() {
        assertMasked("GET /api/login?username=a&password=secret99", "secret99");
        assertMasked("{\"password\":\"secret99\"}", "secret99");
        assertMasked("password: secret99", "secret99");
        assertMasked("access_token=abcdef123456", "abcdef123456");
        assertMasked("client_secret=abcdef123456", "abcdef123456");
    }

    @ParameterizedTest
    @DisplayName("正常聊天不得被改动")
    @ValueSource(strings = {
            "/决斗 张三",
            "/背包",
            "/使用 回气丹 3",
            "你好啊，今天天气不错",
            "/交易 @李四 100",
            "help",
            "/排行",
    })
    void keepsNormalChatIntact(String message) {
        assertEquals(message, LogMasker.mask(message), "正常聊天不应被打码: " + message);
    }

    @Test
    @DisplayName("打码后仍然保留指令本身，便于排查问题")
    void keepsCommandNameVisible() {
        String masked = LogMasker.mask("/注册 mySecret123");
        assertTrue(masked.startsWith("/注册"), "指令名应保留，实际: " + masked);
        assertTrue(masked.contains("***"), "口令位置应显示 ***，实际: " + masked);
    }

    @Test
    @DisplayName("null 与空串原样返回")
    void handlesNullAndEmpty() {
        assertNull(LogMasker.mask(null));
        assertEquals("", LogMasker.mask(""));
    }

    private static void assertMasked(String input, String secret) {
        String masked = LogMasker.mask(input);
        assertFalse(masked.contains(secret),
                "日志里仍能看到明文凭据「" + secret + "」: " + masked);
        assertTrue(masked.contains("***"), "应打码为 ***，实际: " + masked);
    }
}
