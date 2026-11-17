package com.mtxgdn.qq;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.identity.QqBinding;
import com.mtxgdn.identity.QqOfficialBindingService;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无密码注册兜底：私聊与网页都不可用时，{@code /注册} 直接建号。
 *
 * <p><b>要解决的问题</b>：新机器人没有单聊权限、管理员又没配 {@code server.public-url}
 * 时，{@code /注册} 原来会彻底卡死 —— 菜单收不到、链接也发不出。
 * 现在直接建号（随机密码，玩家不知道也不需要知道），账号通过 QQ 身份使用。</p>
 *
 * <p><b>安全约束</b>：随机密码永远不出现在任何提示里；账号带上「未设置密码」标记，
 * 玩家之后在私聊里 {@code /改密} 可以直接设一个新密码（身份已由绑定的 openid 证明）。</p>
 */
class QqPasswordlessRegisterTest extends TestBase {

    private static final String APP_ID = "pwless_" + UUID.randomUUID().toString().substring(0, 8);

    /** 可编程 sender：模拟"私聊能不能发出去"。 */
    private static final class FakeSender extends QqMessageSender {
        boolean privateChatWorks = false;

        FakeSender(QqAdapterContext ctx) {
            super(new QqApiClient(ctx));
        }

        @Override
        boolean sendPrivateMsgResult(String targetQq, String message) {
            return privateChatWorks;
        }

        @Override
        boolean replyToSourceResult(OneBotSession session, String selfId, String senderQq,
                                    Long sourceGroupId, String message) {
            return true;
        }
    }

    private QqAdapterContext ctx;
    private FakeSender sender;
    private QqAccountFlow flow;

    @BeforeEach
    void setUp() {
        System.clearProperty("server.public-url"); // 网页不可用
        ctx = new QqAdapterContext();
        ctx.appId = APP_ID;
        ctx.bindingService = new QqOfficialBindingService(APP_ID);
        sender = new FakeSender(ctx);
        flow = new QqAccountFlow(ctx, sender, new QqBindLinkFlow(sender));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("server.public-url");
    }

    private static String uniqueName() {
        return "无密码" + UUID.randomUUID().toString().substring(0, 4);
    }

    @Test
    @DisplayName("私聊+网页都不可用 → 直接建号：账号、角色、绑定都在，且标记未设密码")
    void registersWithoutPassword() {
        String name = uniqueName();
        flow.handleRegister(null, "", "openid_pwless", name, 123L);

        assertTrue(UserAccountDAO.isUsernameExists(name), "账号应已创建");
        QqBinding binding = new QqOfficialBindingService(APP_ID).findByQq("openid_pwless");
        assertNotNull(binding, "openid 应已绑定");
        Long userId = binding.getUserId();
        assertNotNull(ServiceRegistry.container().getPlayerService().getPlayerByUserId(userId),
                "角色应已创建");
        assertTrue(new UserServiceDAO().isPasswordPlaceholder(userId),
                "账号应标记为尚未设置密码");
    }

    @Test
    @DisplayName("随机密码不可登录：任何密码都验不过")
    void randomPasswordIsUnusable() {
        String name = uniqueName();
        flow.handleRegister(null, "", "openid_pwless_login", name, 123L);
        Long userId = new QqOfficialBindingService(APP_ID).findByQq("openid_pwless_login").getUserId();

        assertFalse(new UserService().verifyPassword(userId, ""), "空密码不能登录");
        assertFalse(new UserService().verifyPassword(userId, name), "角色名也不该是密码");
        assertNull(new UserService().authenticate(name, name), "密码登录必须失败");
    }

    @Test
    @DisplayName("之后私聊可用：/改密 跳过原密码，直接设新密码并清除标记")
    void changePasswordSkipsOldPasswordForPlaceholder() {
        String name = uniqueName();
        flow.handleRegister(null, "", "openid_pwless_chpwd", name, 123L);
        Long userId = new QqOfficialBindingService(APP_ID).findByQq("openid_pwless_chpwd").getUserId();

        // 私聊恢复：/改密 应该直接问新密码
        sender.privateChatWorks = true;
        flow.handleChangePassword(null, "", "openid_pwless_chpwd", 1L);

        QqPendingSession ps = ctx.pendingSessions.get("openid_pwless_chpwd");
        assertNotNull(ps, "应建立待处理会话");
        assertEquals("WAITING_NEW_PASSWORD", ps.state, "无密码账号应跳过原密码，直接收新密码");

        flow.handlePendingFlow("openid_pwless_chpwd", ps, "mynewpass1");

        assertTrue(new UserService().verifyPassword(userId, "mynewpass1"), "新密码应生效");
        assertFalse(new UserServiceDAO().isPasswordPlaceholder(userId), "设置后应清除占位标记");
    }

    @Test
    @DisplayName("注册提示不包含随机密码（群里绝不能出现密码）")
    void replyNeverContainsPassword() throws Exception {
        // 用一个记录消息的 sender 捕捉回复
        StringBuilder captured = new StringBuilder();
        QqAccountFlow recordingFlow = new QqAccountFlow(ctx, new QqMessageSender(new QqApiClient(ctx)) {
            @Override
            boolean replyToSourceResult(OneBotSession session, String selfId, String senderQq,
                                        Long sourceGroupId, String message) {
                captured.append(message);
                return true;
            }

            @Override
            boolean sendPrivateMsgResult(String targetQq, String message) {
                return false;
            }
        }, new QqBindLinkFlow(new QqMessageSender(new QqApiClient(ctx))));

        String name = uniqueName();
        recordingFlow.handleRegister(null, "", "openid_pwless_msg", name, 123L);

        String reply = captured.toString();
        assertTrue(reply.contains("创建成功") || reply.contains("注册成功"), "应告知成功: " + reply);
        assertTrue(reply.contains("未设置密码"), "应说明尚未设置密码: " + reply);
        assertFalse(reply.contains("密码是"), "不能把密码写进消息: " + reply);
    }
}
