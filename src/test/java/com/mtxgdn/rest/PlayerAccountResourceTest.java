package com.mtxgdn.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.identity.QqOfficialBindingService;
import com.mtxgdn.service.UserService;
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
 * 玩家自助账号管理：解绑 QQ / 改密 / 注销。
 *
 * <p>这条路径是给<b>没有单聊权限</b>的机器人玩家用的：聊天里的 /解绑、/改密、/注销
 * 无法私聊收密码，只能在网页登录后自助操作。测试钉的是安全契约：</p>
 * <ol>
 *   <li>未登录（上下文没有 userId）→ 401，任何操作都不执行；</li>
 *   <li>只能操作自己的账号（userId 来自登录态，不接受请求体传入）；</li>
 *   <li>改密/注销必须验原密码，错误密码不清数据；</li>
 *   <li>注销是级联的：角色、绑定、账号都要删干净，不留孤儿行。</li>
 * </ol>
 */
class PlayerAccountResourceTest extends TestBase {

    private static final String APP_ID = "acct_app_" + UUID.randomUUID().toString().substring(0, 8);

    private PlayerAccountResource resource;
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() throws Exception {
        resource = new PlayerAccountResource();
        requestContext = mock(ContainerRequestContext.class);
        Field f = PlayerAccountResource.class.getDeclaredField("requestContext");
        f.setAccessible(true);
        f.set(resource, requestContext);
    }

    @AfterEach
    void tearDown() {
        // 每个测试自己建账号，无需额外清理（测试库每个测试类重建）
    }

    private void loginAs(long userId) {
        when(requestContext.getProperty("userId")).thenReturn(userId);
    }

    private long newUser(String password) {
        String username = "acct_" + UUID.randomUUID().toString().substring(0, 8);
        Long id = UserAccountDAO.insertUser(username, BCrypt.hashpw(password, BCrypt.gensalt()), null);
        assertNotNull(id);
        return id;
    }

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("未登录 → 所有端点 401，且不做任何操作")
    void requiresLogin() {
        when(requestContext.getProperty("userId")).thenReturn(null);
        long userId = newUser("secret123");
        new QqOfficialBindingService(APP_ID).bind("openid_noauth", userId);

        assertEquals(401, resource.getQqBinding().getStatus());
        assertEquals(401, resource.unbindQq().getStatus());
        assertEquals(401, resource.changePassword("{\"oldPassword\":\"secret123\",\"newPassword\":\"newpass123\"}").getStatus());
        assertEquals(401, resource.deleteAccount("{\"password\":\"secret123\"}").getStatus());

        // 什么都没发生
        assertNotNull(new QqOfficialBindingService(APP_ID).findByQq("openid_noauth"));
        assertTrue(UserAccountDAO.isUsernameExists(UserAccountDAO.getUsernameByUserId(userId)));
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询绑定：未绑定 → bound=false；已绑定 → bound=true 且只回显尾部")
    void reportsBindingStatus() {
        long userId = newUser("secret123");
        loginAs(userId);

        JsonObject notBound = JsonParser.parseString((String) resource.getQqBinding().getEntity()).getAsJsonObject();
        assertFalse(notBound.getAsJsonObject("data").get("bound").getAsBoolean());

        new QqOfficialBindingService(APP_ID).bind("openid_1ABCDEF", userId);
        JsonObject bound = JsonParser.parseString((String) resource.getQqBinding().getEntity()).getAsJsonObject();
        assertTrue(bound.getAsJsonObject("data").get("bound").getAsBoolean());
        String tail = bound.getAsJsonObject("data").get("openidTail").getAsString();
        assertTrue(tail.endsWith("ABCDEF"), "只回显尾部: " + tail);
        assertFalse(tail.contains("openid_1"), "不该暴露完整 openid");
    }

    // ==================== 解绑 ====================

    @Test
    @DisplayName("解绑：已绑定 → 200 且绑定被删除")
    void unbindRemovesBinding() {
        long userId = newUser("secret123");
        loginAs(userId);
        new QqOfficialBindingService(APP_ID).bind("openid_unbind_me", userId);

        Response resp = resource.unbindQq();
        assertEquals(200, resp.getStatus());
        assertNull(new QqOfficialBindingService(APP_ID).findByQq("openid_unbind_me"));
    }

    @Test
    @DisplayName("解绑：未绑定 → 404")
    void unbindWhenNotBoundIs404() {
        long userId = newUser("secret123");
        loginAs(userId);
        assertEquals(404, resource.unbindQq().getStatus());
    }

    // ==================== 改密 ====================

    @Test
    @DisplayName("改密：原密码错误 → 401，密码不变")
    void changePasswordRejectsWrongOldPassword() {
        long userId = newUser("secret123");
        loginAs(userId);

        Response resp = resource.changePassword(
                "{\"oldPassword\":\"wrong-pass\",\"newPassword\":\"newpass123\"}");
        assertEquals(401, resp.getStatus());
        assertTrue(new UserService().verifyPassword(userId, "secret123"),
                "错误的原密码不能改动任何东西");
    }

    @Test
    @DisplayName("改密：原密码正确 → 成功，新密码可用、旧密码失效")
    void changePasswordWorks() {
        long userId = newUser("secret123");
        loginAs(userId);

        Response resp = resource.changePassword(
                "{\"oldPassword\":\"secret123\",\"newPassword\":\"newpass123\"}");
        // UserService.changePassword 成功回 code=0
        JsonObject body = JsonParser.parseString((String) resp.getEntity()).getAsJsonObject();
        assertEquals(0, body.get("code").getAsInt(), "改密应成功: " + resp.getEntity());
        assertTrue(new UserService().verifyPassword(userId, "newpass123"));
        assertFalse(new UserService().verifyPassword(userId, "secret123"));
    }

    // ==================== 注销 ====================

    @Test
    @DisplayName("注销：密码错误 → 401，账号与角色都还在")
    void deleteRejectsWrongPassword() {
        long userId = newUser("secret123");
        loginAs(userId);
        ServiceRegistry.container().getPlayerService().createPlayer(userId, "注销测试甲");
        new QqOfficialBindingService(APP_ID).bind("openid_del_bad", userId);

        Response resp = resource.deleteAccount("{\"password\":\"wrong-pass\"}");
        assertEquals(401, resp.getStatus());
        assertTrue(UserAccountDAO.isUsernameExists(UserAccountDAO.getUsernameByUserId(userId)));
        assertNotNull(ServiceRegistry.container().getPlayerService().getPlayerByUserId(userId));
    }

    @Test
    @DisplayName("注销：密码正确 → 200，角色 / 绑定 / 账号全部删除")
    void deleteCascadesEverything() {
        long userId = newUser("secret123");
        String username = UserAccountDAO.getUsernameByUserId(userId);
        loginAs(userId);
        PlayerInfo player = ServiceRegistry.container().getPlayerService().createPlayer(userId, "注销测试乙");
        new QqOfficialBindingService(APP_ID).bind("openid_del_ok", userId);

        Response resp = resource.deleteAccount("{\"password\":\"secret123\"}");
        assertEquals(200, resp.getStatus(), "注销应成功: " + resp.getEntity());

        assertFalse(UserAccountDAO.isUsernameExists(username), "账号应已删除");
        assertNull(ServiceRegistry.container().getPlayerService().getPlayerByUserId(userId), "角色应已删除");
        assertNull(new QqOfficialBindingService(APP_ID).findByQq("openid_del_ok"), "绑定应已删除");
    }
}
