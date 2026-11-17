package com.mtxgdn.onebot.command.account;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 账号类指令不再「私聊专用」。
 *
 * <p><b>背景</b>：新申请的 QQ 官方机器人常常没有单聊（C2C）权限。这些指令若标成私聊专用，
 * 群里发会被直接拒绝，而私聊又发不出去 —— 玩家在两个方向都走不通。现在的策略：</p>
 * <ul>
 *   <li>指令本身可在群里发起；</li>
 *   <li>需要输入密码的流程先试私聊，私聊不通就回群里的「网页自助」入口；</li>
 *   <li>凭据永远不出现在群里。</li>
 * </ul>
 */
class AccountCommandsPrivacyTest {

    @Test
    @DisplayName("/解绑 可在群里发起（结果不在群里收密码）")
    void unbindIsNotPrivateOnly() {
        assertFalse(new UnbindCommand().isPrivateOnly());
    }

    @Test
    @DisplayName("/改密 可在群里发起（密码仍走私聊或网页）")
    void changePasswordIsNotPrivateOnly() {
        assertFalse(new ChangePasswordCommand().isPrivateOnly());
    }

    @Test
    @DisplayName("/注销 可在群里发起（密码仍走私聊或网页）")
    void deleteAccountIsNotPrivateOnly() {
        assertFalse(new DeleteAccountCommand().isPrivateOnly());
    }
}
