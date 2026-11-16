package com.mtxgdn.qq;

import com.mtxgdn.TestBase;
import com.mtxgdn.identity.QqBindTokenStore;
import com.mtxgdn.onebot.OneBotSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 账号流程的「下发方式」：先私聊，私聊不通就发网页链接。
 *
 * <p>这一层是新机器人（没有单聊权限）能不能用 {@code /绑定}、{@code /注册} 的关键。
 * 测试钉的是三条：</p>
 * <ol>
 *   <li>私聊发不出去时，必须改发链接（而不是静默失败）；</li>
 *   <li>网页也不可用时，必须明确提示（而不是发一条打不开的链接）；</li>
 *   <li>菜单里只出现当前真的可用的方式（邮箱没配就不出现 3）。</li>
 * </ol>
 *
 * <p>sender 用手写 fake：真实环境里"私聊发不出去"是常态（新机器人没有 C2C 权限），
 * 而测试环境没有网络、没有 access_token，无法区分"发不出去"和"发出去了"。
 * Mockito 5.8 在 Java 25 上无法改写类，所以这里直接子类化。</p>
 */
class QqBindLinkFlowTest extends TestBase {

    /** 可编程的 sender：控制私聊 / 来源会话是否"发得出去"。 */
    private static final class FakeSender extends QqMessageSender {
        boolean privateChatWorks = true;
        boolean sourceWorks = true;

        FakeSender() {
            super(new QqApiClient(new QqAdapterContext()));
        }

        @Override
        boolean sendPrivateMsgResult(String targetQq, String message) {
            return privateChatWorks;
        }

        @Override
        boolean replyToSourceResult(OneBotSession session, String selfId, String senderQq,
                                    Long sourceGroupId, String message) {
            return sourceWorks;
        }
    }

    private FakeSender sender;
    private QqBindLinkFlow flow;

    @BeforeEach
    void setUp() {
        sender = new FakeSender();
        flow = new QqBindLinkFlow(sender);
        QqBindTokenStore.clear();
        System.clearProperty("server.public-url");
    }

    @AfterEach
    void tearDown() {
        QqBindTokenStore.clear();
        System.clearProperty("server.public-url");
    }

    @Test
    @DisplayName("私聊能发 → MENU_SENT，不签发票据")
    void menuSentWhenPrivateChatWorks() {
        sender.privateChatWorks = true;

        QqBindLinkFlow.Delivery delivery = flow.deliver(null, "", "openid_menu", null,
                "app1", QqBindTokenStore.Intent.BIND, null);

        assertEquals(QqBindLinkFlow.Delivery.MENU_SENT, delivery);
        assertEquals(0, QqBindTokenStore.size(), "菜单路径不该签发票据");
    }

    @Test
    @DisplayName("私聊不通 + 网页可用 → LINK_SENT，且票据已签发")
    void fallsBackToLinkWhenPrivateChatUnavailable() {
        System.setProperty("server.public-url", "https://game.example.com");
        sender.privateChatWorks = false;
        sender.sourceWorks = true;

        QqBindLinkFlow.Delivery delivery = flow.deliver(null, "", "openid_link", 12345L,
                "app1", QqBindTokenStore.Intent.BIND, null);

        assertEquals(QqBindLinkFlow.Delivery.LINK_SENT, delivery,
                "私聊发不出去时必须改发网页链接");
        assertEquals(1, QqBindTokenStore.size(), "链接里的票据必须已签发");
    }

    @Test
    @DisplayName("私聊不通 + 网页不可用 → UNREACHABLE，不签发票据")
    void unreachableWhenNeitherPathAvailable() {
        // 默认 server.host=127.0.0.1 且未配 public-url → 网页不可用
        sender.privateChatWorks = false;
        sender.sourceWorks = true;

        QqBindLinkFlow.Delivery delivery = flow.deliver(null, "", "openid_none", 12345L,
                "app1", QqBindTokenStore.Intent.BIND, null);

        assertEquals(QqBindLinkFlow.Delivery.UNREACHABLE, delivery);
        assertEquals(0, QqBindTokenStore.size(), "两条路都不可用时不该签发票据");
    }

    @Test
    @DisplayName("私聊不通 + 网页可用 + 来源会话也发不出去 → UNREACHABLE")
    void unreachableWhenSourceAlsoFails() {
        System.setProperty("server.public-url", "https://game.example.com");
        sender.privateChatWorks = false;
        sender.sourceWorks = false;

        QqBindLinkFlow.Delivery delivery = flow.deliver(null, "", "openid_srcfail", 12345L,
                "app1", QqBindTokenStore.Intent.BIND, null);

        assertEquals(QqBindLinkFlow.Delivery.UNREACHABLE, delivery);
    }

    @Test
    @DisplayName("session 为 null 时不抛异常（拆分测试会直接调门面）")
    void nullSessionDoesNotThrow() {
        sender.privateChatWorks = false;
        assertDoesNotThrow(() -> flow.deliver(null, "", "openid_null", null,
                "app1", QqBindTokenStore.Intent.REGISTER, "张三"));
    }

    @Test
    @DisplayName("菜单：网页不可用时不出现「网页登录」，邮箱未配时不出现「邮箱验证码」")
    void menuHidesUnavailableOptions() {
        String menu = flow.menu(QqBindTokenStore.Intent.BIND, null);
        assertTrue(menu.contains("1. 用户名 + 密码"), "密码方式始终可用");
        assertFalse(menu.contains("2. 网页登录"), "网页不可用时不该出现");
        assertFalse(menu.contains("3. 邮箱验证码"), "邮箱未配时不该出现");
        assertTrue(menu.contains("本服未开放网页登录"), "应明确说明网页不可用");
        assertTrue(menu.contains("本服未启用邮箱登录"), "应明确说明邮箱不可用");
    }

    @Test
    @DisplayName("菜单：网页可用时出现「网页登录」")
    void menuShowsWebWhenAvailable() {
        System.setProperty("server.public-url", "https://game.example.com");
        String menu = flow.menu(QqBindTokenStore.Intent.BIND, null);
        assertTrue(menu.contains("2. 网页登录"), "网页可用时应出现");
        assertFalse(menu.contains("本服未开放网页登录"), "网页可用时不该再提示不可用");
    }

    @Test
    @DisplayName("注册菜单：带角色名，只有 1/2 两个选项")
    void registerMenuCarriesName() {
        System.setProperty("server.public-url", "https://game.example.com");
        String menu = flow.menu(QqBindTokenStore.Intent.REGISTER, "张三");
        assertTrue(menu.contains("张三"), "注册菜单应展示角色名");
        assertTrue(menu.contains("1. 在这里设置密码"));
        assertTrue(menu.contains("2. 网页设置密码"));
        assertFalse(menu.contains("3."), "注册没有邮箱验证码选项");
    }

    @Test
    @DisplayName("链接文案：含一次性说明与有效期，不含密码")
    void linkMessageIsSafe() {
        System.setProperty("server.public-url", "https://game.example.com");
        String msg = flow.linkMessage("app1", "openid_msg", QqBindTokenStore.Intent.BIND, null);
        assertTrue(msg.contains("https://game.example.com/admin/?qq_bind="), "链接应指向 /admin 绑定页");
        assertTrue(msg.contains("10 分钟"), "应说明有效期");
        assertTrue(msg.contains("只能用一次"), "应说明单次");
        assertFalse(msg.toLowerCase().contains("password"), "链接文案不该出现密码字样");
    }

    @Test
    @DisplayName("sendLink：网页不可用时返回 false 且不签发票据")
    void sendLinkFailsWhenWebUnavailable() {
        boolean sent = flow.sendLink(null, "", "openid_sl", null,
                "app1", QqBindTokenStore.Intent.BIND, null);
        assertFalse(sent);
        assertEquals(0, QqBindTokenStore.size());
    }
}
