package com.mtxgdn.onebot.command.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /绑定} 不再是私聊专用。
 *
 * <p>新申请的 QQ 官方机器人常常没有单聊（C2C）权限，群里发 {@code /绑定}
 * 若被直接拒绝，玩家在这个部署下就永远绑定不了账号。现在两种通道都不在群里收凭据：
 * OneBot 把"请输入用户名/密码"发到私聊，QQ 官方通道先试私聊、发不出去就回一条
 * 一次性网页链接 —— 群里只会出现链接本身，不会出现密码。</p>
 */
class BindCommandTest {

    @Test
    @DisplayName("/绑定 不再是私聊专用（群里可发起，凭据不在群里收）")
    void bindIsNotPrivateOnly() {
        BindCommand cmd = new BindCommand();
        assertFalse(cmd.isPrivateOnly(),
                "新机器人没有单聊权限时，私聊专用等于永远绑定不了");
    }

    @Test
    @DisplayName("/绑定 的别名与分类保持不变")
    void bindAliasesUnchanged() {
        BindCommand cmd = new BindCommand();
        assertArrayEquals(new String[]{"绑定", "bind"}, cmd.getNames());
        assertEquals("账号", cmd.getCategory());
    }
}
