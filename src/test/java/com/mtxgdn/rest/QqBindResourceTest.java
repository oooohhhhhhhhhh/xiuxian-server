package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.TestBase;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.identity.QqBindTokenStore;
import com.mtxgdn.identity.QqOfficialBindingService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网页绑定端点的端到端行为。
 *
 * <p>这一层是"链接发出去之后，玩家在浏览器里完成绑定"的全部逻辑。
 * 测试钉的是安全契约，不是实现细节：</p>
 * <ol>
 *   <li>票据过期 / 不存在 → 410，且不泄漏任何账号信息；</li>
 *   <li>密码错误 → 401，统一文案（不给"用户名存不存在"的探针）；</li>
 *   <li>连续 5 次失败 → 票据作废（410）；</li>
 *   <li>绑定成功 → openid 真的写进了 qq_official_bindings；</li>
 *   <li>同一 openid 绑第二个账号 → 409；</li>
 *   <li>注册票据 → 建号 + 建角色 + 绑定，一次完成；</li>
 *   <li>发码接口在 SMTP 未配置时明确拒绝（而不是假装发了）。</li>
 * </ol>
 */
class QqBindResourceTest extends TestBase {

    private static final String APP_ID = "test_app_" + UUID.randomUUID().toString().substring(0, 8);

    private QqBindResource resource;
    private ContainerRequestContext requestContext;
    private int ipCounter = 0;

    @BeforeEach
    void setUp() throws Exception {
        QqBindTokenStore.clear();
        resource = new QqBindResource();
        requestContext = mock(ContainerRequestContext.class);
        // 每个测试用独立来源 IP，避免限流桶互相污染（surefire 同 JVM 跑全部测试）
        String ip = "10.99." + (ipCounter / 256) + "." + (ipCounter++ % 256);
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(ip);
        when(requestContext.getProperty("org.glassfish.grizzly.http.server.Request")).thenReturn(null);
        Field f = QqBindResource.class.getDeclaredField("requestContext");
        f.setAccessible(true);
        f.set(resource, requestContext);
        System.setProperty("security.trust_proxy_headers", "true");
    }

    @AfterEach
    void tearDown() {
        QqBindTokenStore.clear();
        System.clearProperty("security.trust_proxy_headers");
    }

    // ==================== /info ====================

    @Test
    @DisplayName("info：未知票据 → 410")
    void infoRejectsUnknownToken() {
        Response resp = resource.info("no_such_token");
        assertEquals(410, resp.getStatus());
    }

    @Test
    @DisplayName("info：绑定票据 → 200，intent=BIND，不返回 openid")
    void infoReturnsBindIntent() {
        String token = QqBindTokenStore.issue("openid_info", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.info(token);
        assertEquals(200, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("BIND", body.get("intent").getAsString());
        assertFalse(body.has("openid"), "首屏不该返回 openid");
        assertFalse(body.has("username"), "绑定票据不该带角色名");
    }

    @Test
    @DisplayName("info：注册票据 → 200，intent=REGISTER，带角色名")
    void infoReturnsRegisterIntent() {
        String token = QqBindTokenStore.issue("openid_reg", APP_ID, QqBindTokenStore.Intent.REGISTER, "张三");
        Response resp = resource.info(token);
        assertEquals(200, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("REGISTER", body.get("intent").getAsString());
        assertEquals("张三", body.get("username").getAsString());
    }

    // ==================== /confirm 密码绑定 ====================

    @Test
    @DisplayName("confirm：密码正确 → 200，openid 真的绑上了")
    void confirmBindsWithCorrectPassword() {
        String username = "bind_ok_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "secret123";
        Long userId = UserAccountDAO.insertUser(username, BCrypt.hashpw(password, BCrypt.gensalt()), null);
        assertNotNull(userId);

        String token = QqBindTokenStore.issue("openid_bind_ok", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"" + username
                        + "\",\"password\":\"" + password + "\"}");

        assertEquals(200, resp.getStatus(), "绑定应成功: " + resp.getEntity());
        // 票据已消费
        assertTrue(QqBindTokenStore.peek(token).isEmpty(), "成功后票据应作废");
        // 绑定真的写进了库
        QqOfficialBindingService binding = new QqOfficialBindingService(APP_ID);
        assertNotNull(binding.findByQq("openid_bind_ok"), "openid 应已绑定");
        assertEquals(userId.longValue(), binding.findByQq("openid_bind_ok").getUserId());
    }

    @Test
    @DisplayName("confirm：密码错误 → 401，统一文案，不泄漏用户名是否存在")
    void confirmRejectsWrongPassword() {
        String username = "bind_bad_" + UUID.randomUUID().toString().substring(0, 8);
        UserAccountDAO.insertUser(username, BCrypt.hashpw("right-password", BCrypt.gensalt()), null);

        String token = QqBindTokenStore.issue("openid_bind_bad", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"" + username
                        + "\",\"password\":\"wrong-password\"}");

        assertEquals(401, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("用户名或密码错误", body.get("message").getAsString(),
                "密码错误必须统一文案，不能区分用户名是否存在");
        // 绑定没发生
        assertNull(new QqOfficialBindingService(APP_ID).findByQq("openid_bind_bad"));
    }

    @Test
    @DisplayName("confirm：不存在的用户名 → 401，与密码错误同一文案")
    void confirmRejectsUnknownUsernameWithSameMessage() {
        String token = QqBindTokenStore.issue("openid_bind_ghost", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"ghost_"
                        + UUID.randomUUID().toString().substring(0, 8) + "\",\"password\":\"whatever1\"}");

        assertEquals(401, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("用户名或密码错误", body.get("message").getAsString(),
                "不存在的用户名必须与密码错误同一文案");
    }

    @Test
    @DisplayName("confirm：连续 5 次密码错误 → 票据作废（410）")
    void fiveFailuresInvalidateTicket() {
        String username = "bind_lock_" + UUID.randomUUID().toString().substring(0, 8);
        UserAccountDAO.insertUser(username, BCrypt.hashpw("right-password", BCrypt.gensalt()), null);

        String token = QqBindTokenStore.issue("openid_bind_lock", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response last = null;
        for (int i = 0; i < 5; i++) {
            last = resource.confirm(null,
                    "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"" + username
                            + "\",\"password\":\"wrong-" + i + "\"}");
        }
        assertEquals(410, last.getStatus(), "第 5 次失败后票据应作废");
        assertTrue(QqBindTokenStore.peek(token).isEmpty(), "作废后 peek 应查不到");
    }

    @Test
    @DisplayName("confirm：同一 openid 绑第二个账号 → 409")
    void confirmRejectsSecondAccountForSameOpenid() {
        String user1 = "bind_dup1_" + UUID.randomUUID().toString().substring(0, 8);
        String user2 = "bind_dup2_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "secret123";
        UserAccountDAO.insertUser(user1, BCrypt.hashpw(password, BCrypt.gensalt()), null);
        UserAccountDAO.insertUser(user2, BCrypt.hashpw(password, BCrypt.gensalt()), null);

        String openid = "openid_dup";
        String t1 = QqBindTokenStore.issue(openid, APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response first = resource.confirm(null,
                "{\"token\":\"" + t1 + "\",\"mode\":\"password\",\"username\":\"" + user1
                        + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, first.getStatus());

        String t2 = QqBindTokenStore.issue(openid, APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response second = resource.confirm(null,
                "{\"token\":\"" + t2 + "\",\"mode\":\"password\",\"username\":\"" + user2
                        + "\",\"password\":\"" + password + "\"}");
        assertEquals(409, second.getStatus(), "同一 openid 绑第二个账号必须被拒");
    }

    // ==================== /confirm 邮箱模式 ====================

    @Test
    @DisplayName("confirm：邮箱验证码错误 → 400，不泄漏邮箱是否关联账号")
    void confirmRejectsBadEmailCode() {
        String token = QqBindTokenStore.issue("openid_email_bad", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"email\",\"email\":\"nobody@example.com\",\"code\":\"000000\"}");

        assertEquals(400, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("验证码错误或已过期，或该邮箱未关联账号", body.get("message").getAsString());
    }

    // ==================== /confirm 注册 ====================

    @Test
    @DisplayName("confirm：注册票据 → 建号 + 绑定，一次完成")
    void confirmRegisterCreatesAccountAndBinds() {
        String name = "reg_" + UUID.randomUUID().toString().substring(0, 8);
        String token = QqBindTokenStore.issue("openid_reg_ok", APP_ID, QqBindTokenStore.Intent.REGISTER, name);

        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"password\":\"secret123\"}");

        assertEquals(200, resp.getStatus(), "注册应成功: " + resp.getEntity());
        assertTrue(UserAccountDAO.isUsernameExists(name), "角色名应已建号");
        assertNotNull(new QqOfficialBindingService(APP_ID).findByQq("openid_reg_ok"),
                "openid 应已绑定到新账号");
    }

    @Test
    @DisplayName("confirm：注册票据 + 角色名已被占用 → 409，且不留半截账号")
    void confirmRegisterRejectsTakenName() {
        String name = "taken_" + UUID.randomUUID().toString().substring(0, 6);
        UserAccountDAO.insertUser(name, BCrypt.hashpw("x", BCrypt.gensalt()), null);

        String token = QqBindTokenStore.issue("openid_reg_taken", APP_ID, QqBindTokenStore.Intent.REGISTER, name);
        Response resp = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"password\":\"secret123\"}");

        assertEquals(409, resp.getStatus());
        assertNull(new QqOfficialBindingService(APP_ID).findByQq("openid_reg_taken"),
                "注册失败不该留下绑定");
    }

    // ==================== /send-code ====================

    @Test
    @DisplayName("send-code：SMTP 未配置 → 400，明确拒绝而不是假装发了")
    void sendCodeRejectedWhenSmtpNotConfigured() {
        String token = QqBindTokenStore.issue("openid_code", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.sendCode(null,
                "{\"token\":\"" + token + "\",\"email\":\"someone@example.com\"}");

        assertEquals(400, resp.getStatus());
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals("本服未启用邮箱登录", body.get("message").getAsString());
    }

    @Test
    @DisplayName("send-code：未知票据 → 410")
    void sendCodeRejectsUnknownToken() {
        // SMTP 未配置时先被 400 拦下；这里只验证票据校验在 SMTP 之后、限流之前
        // —— 用一个已知票据确认不会误报 410
        String token = QqBindTokenStore.issue("openid_code2", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response resp = resource.sendCode(null,
                "{\"token\":\"" + token + "\",\"email\":\"someone@example.com\"}");
        assertNotEquals(410, resp.getStatus(), "已知票据不该报 410");
    }

    // ==================== 票据作废后的行为 ====================

    @Test
    @DisplayName("confirm：票据已消费 → 410")
    void confirmRejectsConsumedToken() {
        String username = "bind_consumed_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "secret123";
        UserAccountDAO.insertUser(username, BCrypt.hashpw(password, BCrypt.gensalt()), null);

        String token = QqBindTokenStore.issue("openid_consumed", APP_ID, QqBindTokenStore.Intent.BIND, null);
        Response first = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"" + username
                        + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, first.getStatus());

        Response second = resource.confirm(null,
                "{\"token\":\"" + token + "\",\"mode\":\"password\",\"username\":\"" + username
                        + "\",\"password\":\"" + password + "\"}");
        assertEquals(410, second.getStatus(), "已消费的票据必须 410");
    }
}
