package com.mtxgdn.qq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「发送成功」的判定：HTTP 状态码 + body 里的业务错误码。
 *
 * <p><b>为什么需要这组测试</b>：QQ 官方 API 在「无单聊权限」时可能回 <b>HTTP 200</b>，
 * 错误码在 body 里（如 {@code {"code":11244,"message":"无权限"}}）。只看状态码会把失败
 * 当成功，调用方以为菜单发出去了，永远不降级到网页链接 —— 新机器人（无 C2C 权限）的
 * {@code /绑定}、{@code /注册} 就会"发了但没反应"。这正是线上踩到的坑。</p>
 */
class QqApiClientResponseTest {

    @Test
    @DisplayName("HTTP 200 + 正常消息体 → 成功")
    void successWithMessageBody() {
        assertTrue(QqApiClient.apiCallSucceeded(200, "{\"id\":\"abc\",\"timestamp\":\"2026-01-01T00:00:00Z\"}"));
    }

    @Test
    @DisplayName("HTTP 200 + code=0 → 成功")
    void successWithZeroCode() {
        assertTrue(QqApiClient.apiCallSucceeded(200, "{\"code\":0}"));
    }

    @Test
    @DisplayName("HTTP 200 + 非零 code（无权限）→ 失败（不能当成功）")
    void http200WithNonZeroCodeIsFailure() {
        assertFalse(QqApiClient.apiCallSucceeded(200, "{\"code\":11244,\"message\":\"无权限\"}"),
                "无单聊权限时 API 回 200 + 错误码，必须判为失败才能触发网页链接降级");
    }

    @Test
    @DisplayName("HTTP 4xx/5xx → 失败（不论 body）")
    void httpErrorIsFailure() {
        assertFalse(QqApiClient.apiCallSucceeded(400, "{}"));
        assertFalse(QqApiClient.apiCallSucceeded(401, "{\"code\":0}"));
        assertFalse(QqApiClient.apiCallSucceeded(500, ""));
    }

    @Test
    @DisplayName("body 不是 JSON / code 不是数字 → 按成功处理，不误杀")
    void nonJsonBodyIsNotTreatedAsFailure() {
        assertTrue(QqApiClient.apiCallSucceeded(200, ""));
        assertTrue(QqApiClient.apiCallSucceeded(200, "ok"));
        assertTrue(QqApiClient.apiCallSucceeded(200, "{\"code\":\"0\"}"));
        assertTrue(QqApiClient.apiCallSucceeded(200, "{\"code\":null}"));
    }
}
