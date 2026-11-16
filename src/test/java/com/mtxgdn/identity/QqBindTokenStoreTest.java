package com.mtxgdn.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一次性绑定票据的安全契约。
 *
 * <p>这条票据等同于"半张凭据"：拿到它的人可以把任意 QQ 身份绑到任意账号上。
 * 所以它的每条约束都对应一种真实攻击，测试钉的是这些约束本身，而不是实现细节。</p>
 */
class QqBindTokenStoreTest {

    @AfterEach
    void tearDown() {
        QqBindTokenStore.clear();
    }

    @Test
    @DisplayName("签发 → 查看 → 消费：一次性，消费后再查不到")
    void issuePeekConsume() {
        String token = QqBindTokenStore.issue("openid_a", "app1", QqBindTokenStore.Intent.BIND, null);
        assertNotNull(token);
        assertFalse(token.isBlank());

        // peek 不消费
        assertTrue(QqBindTokenStore.peek(token).isPresent());
        assertTrue(QqBindTokenStore.peek(token).isPresent(), "peek 不该消费票据");

        // consume 一次成功
        QqBindTokenStore.Pending pending = QqBindTokenStore.consume(token).orElseThrow();
        assertEquals("openid_a", pending.openid());
        assertEquals("app1", pending.appId());
        assertEquals(QqBindTokenStore.Intent.BIND, pending.intent());

        // 再消费 → 空（单次）
        assertTrue(QqBindTokenStore.consume(token).isEmpty(), "票据只能用一次");
        assertTrue(QqBindTokenStore.peek(token).isEmpty(), "消费后 peek 也该查不到");
    }

    @Test
    @DisplayName("注册用途的票据带角色名；绑定用途不带")
    void registerCarriesPendingUsername() {
        String reg = QqBindTokenStore.issue("openid_r", "app1", QqBindTokenStore.Intent.REGISTER, "张三");
        assertEquals("张三", QqBindTokenStore.peek(reg).orElseThrow().pendingUsername());

        String bind = QqBindTokenStore.issue("openid_b", "app1", QqBindTokenStore.Intent.BIND, null);
        assertNull(QqBindTokenStore.peek(bind).orElseThrow().pendingUsername());
    }

    @Test
    @DisplayName("内存里只存哈希，不存原文")
    void storesOnlyHash() throws Exception {
        String token = QqBindTokenStore.issue("openid_h", "app1", QqBindTokenStore.Intent.BIND, null);

        Field field = QqBindTokenStore.class.getDeclaredField("TICKETS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> tickets = (Map<String, ?>) field.get(null);

        assertFalse(tickets.containsKey(token), "原文票据不该出现在内存 key 里");
        assertTrue(tickets.containsKey(QqBindTokenStore.sha256(token)), "应以 SHA-256 为 key");
    }

    @Test
    @DisplayName("失败 5 次后票据作废")
    void fiveFailuresInvalidate() {
        String token = QqBindTokenStore.issue("openid_f", "app1", QqBindTokenStore.Intent.BIND, null);

        for (int i = 1; i <= QqBindTokenStore.MAX_FAILURES; i++) {
            int remaining = QqBindTokenStore.recordFailure(token);
            if (i < QqBindTokenStore.MAX_FAILURES) {
                assertTrue(remaining > 0, "第 " + i + " 次失败后应还有剩余次数");
                assertTrue(QqBindTokenStore.peek(token).isPresent(), "未到上限时票据仍可用");
            } else {
                assertEquals(0, remaining, "第 " + i + " 次失败后应作废");
                assertTrue(QqBindTokenStore.peek(token).isEmpty(), "达到上限后 peek 应查不到");
                assertTrue(QqBindTokenStore.consume(token).isEmpty(), "达到上限后 consume 应失败");
            }
        }
    }

    @Test
    @DisplayName("未知 / 空票据：peek 与 consume 都返回空，不抛异常")
    void unknownTokenIsSafe() {
        assertTrue(QqBindTokenStore.peek("no_such_token").isEmpty());
        assertTrue(QqBindTokenStore.consume("no_such_token").isEmpty());
        assertTrue(QqBindTokenStore.peek(null).isEmpty());
        assertTrue(QqBindTokenStore.consume(null).isEmpty());
        assertTrue(QqBindTokenStore.peek("").isEmpty());
        assertEquals(0, QqBindTokenStore.recordFailure("no_such_token"));
    }

    @Test
    @DisplayName("openid 为空时拒绝签发")
    void blankOpenidRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> QqBindTokenStore.issue("", "app1", QqBindTokenStore.Intent.BIND, null));
        assertThrows(IllegalArgumentException.class,
                () -> QqBindTokenStore.issue(null, "app1", QqBindTokenStore.Intent.BIND, null));
    }

    @Test
    @DisplayName("票据互不影响：消费一条不影响另一条")
    void ticketsAreIndependent() {
        String t1 = QqBindTokenStore.issue("openid_1", "app1", QqBindTokenStore.Intent.BIND, null);
        String t2 = QqBindTokenStore.issue("openid_2", "app1", QqBindTokenStore.Intent.BIND, null);

        QqBindTokenStore.consume(t1);
        assertTrue(QqBindTokenStore.peek(t2).isPresent(), "消费 t1 不该影响 t2");
    }
}
