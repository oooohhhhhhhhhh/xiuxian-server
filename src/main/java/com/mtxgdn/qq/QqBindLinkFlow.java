package com.mtxgdn.qq;

import com.mtxgdn.identity.QqBindTokenStore;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.PublicUrl;

import com.mtxgdn.onebot.OneBotSession;

import static com.mtxgdn.qq.QqSupport.log;

/**
 * 「账号绑定/注册」的下发方式：先私聊，不行就发网页链接。
 *
 * <h3>为什么需要这一层</h3>
 * <p>QQ 官方机器人有两个现实约束叠在一起：</p>
 * <ol>
 *   <li><b>密码不能出现在群里</b> —— 群聊是公开的，一旦在这里收密码，
 *       等于让全群围观；</li>
 *   <li><b>新申请的机器人往往没有单聊（C2C）权限</b> —— 私聊消息发出去会被平台直接拒绝，
 *       而原来的实现只会把「请输入用户名 / 密码」发进单聊，玩家那边永远等不到下文，
 *       表现为"发 /绑定 没反应"。</li>
 * </ol>
 *
 * <p>所以策略是：能私聊就照旧走多轮对话（体验最好，密码也不出私聊）；
 * 私聊发不出去时，改在来源会话发一条<b>一次性网页链接</b>，玩家在浏览器里输入密码
 * 或邮箱验证码，聊天里只出现链接本身。链接的安全性由
 * {@link QqBindTokenStore} 保证（单次、10 分钟、不存原文）。</p>
 *
 * <p>网页本身不可用（{@code server.host} 是回环且没配 {@code server.public-url}）时，
 * 这里会给出明确提示而不是发一条打不开的链接 —— 那种链接只会让玩家以为机器人坏了。</p>
 */
final class QqBindLinkFlow {

    private final QqMessageSender sender;

    QqBindLinkFlow(QqMessageSender sender) {
        this.sender = sender;
    }

    /**
     * 下发结果：菜单已发（会话要保留）/ 链接已发（会话可移除）/ 两条路都不可达。
     *
     * <p>{@link #UNREACHABLE} 的提示文案由调用方决定：注册可以退化为「直接建号」，
     * 绑定只能提示玩家联系管理员 —— 两者需要的措辞不同。</p>
     */
    enum Delivery {
        MENU_SENT,
        LINK_SENT,
        UNREACHABLE
    }

    /** 网页方式当前是否可用（回环监听且没配 public-url 时为 false）。 */
    boolean webAvailable() {
        return PublicUrl.available();
    }

    /** 邮箱验证码方式当前是否可用（SMTP 账号与授权码都配了才可用）。 */
    boolean emailAvailable() {
        return EmailService.isConfigured();
    }

    /**
     * 下发绑定/注册的下一步：私聊菜单优先，私聊失败则改发网页链接。
     *
     * @param session       来源会话（
     *                      {@code null} 表示没有来源上下文，例如拆分测试直接调门面）
     * @param sourceGroupId 来源群；为 null 表示玩家是在私聊里发起
     * @param appId         机器人 AppID（票据里要带，绑定写库按 AppID 隔离）
     * @param intent        {@link QqBindTokenStore.Intent#BIND} 或 {@code REGISTER}
     * @param pendingName   注册用途的角色名；绑定用途传 null
     * @return 下发结果：{@link Delivery#MENU_SENT} 表示私聊菜单已发出（会话要保留）；
     *         {@link Delivery#LINK_SENT} 表示改发了网页链接（会话可移除）；
     *         {@link Delivery#UNREACHABLE} 表示两条路都不可用（已记日志，提示由调用方决定）
     */
    Delivery deliver(OneBotSession session, String selfId, String senderQq, Long sourceGroupId,
                     String appId, QqBindTokenStore.Intent intent, String pendingName) {
        String menu = menu(intent, pendingName);
        if (sender.sendPrivateMsgResult(senderQq, menu)) {
            return Delivery.MENU_SENT;
        }

        // 私聊不通：能发链接就发链接；网页也没有就交给调用方处理（注册会退化为直接建号）
        if (!webAvailable()) {
            log.warn("账号流程需要私聊或网页，但两者都不可用: openid=" + senderQq
                    + "（机器人无单聊权限，且 server.public-url 未配置 / server.host 为回环）");
            return Delivery.UNREACHABLE;
        }
        return sender.replyToSourceResult(session, selfId, senderQq, sourceGroupId,
                linkMessage(appId, senderQq, intent, pendingName))
                ? Delivery.LINK_SENT
                : Delivery.UNREACHABLE;
    }

    /**
     * 只在来源会话发一条网页链接（玩家在私聊菜单里主动选了「网页登录」时用）。
     */
    /**
     * 发一条一次性链接到来源会话。
     *
     * @return {@code true} 表示链接已发出（调用方可以移除待处理会话）；
     *         {@code false} 表示没发出去（网页不可用或来源会话发送失败），调用方应保留会话
     */
    boolean sendLink(OneBotSession session, String selfId, String senderQq, Long sourceGroupId,
                     String appId, QqBindTokenStore.Intent intent, String pendingName) {
        if (!webAvailable()) {
            // 不发消息：调用方（菜单分支）会统一提示"链接发送失败，请改用密码方式"
            return false;
        }
        return sender.replyToSourceResult(session, selfId, senderQq, sourceGroupId,
                linkMessage(appId, senderQq, intent, pendingName));
    }

    /**
     * 私聊/群里展示的方式菜单。
     *
     * @param intent      {@code BIND} 时必须带 {@code 1/2/3} 三个选项；{@code REGISTER} 时只有 {@code 1/2}
     * @param pendingName 注册角色名（展示用，让玩家确认名字没错）
     */
    String menu(QqBindTokenStore.Intent intent, String pendingName) {
        boolean web = webAvailable();
        boolean email = emailAvailable();
        StringBuilder sb = new StringBuilder();
        if (intent == QqBindTokenStore.Intent.REGISTER) {
            sb.append("===== 注册角色 =====\n");
            if (pendingName != null && !pendingName.isEmpty()) {
                sb.append("角色名：").append(pendingName).append("\n");
            }
            sb.append("选择设置密码的方式（回复数字）：\n");
            sb.append("1. 在这里设置密码（私聊输入）\n");
            if (web) {
                sb.append("2. 网页设置密码（浏览器里输入，更安全）\n");
            }
            if (!web) {
                sb.append("（本服未开放网页注册）\n");
            }
        } else {
            sb.append("===== 账号绑定 =====\n");
            sb.append("选择登录方式（回复数字）：\n");
            sb.append("1. 用户名 + 密码（私聊输入）\n");
            if (web) {
                sb.append("2. 网页登录（浏览器里输入，支持密码 / 邮箱验证码）\n");
            }
            if (email) {
                sb.append("3. 邮箱验证码登录（私聊输入）\n");
            }
            if (!web) {
                sb.append("（本服未开放网页登录）\n");
            }
            if (!email) {
                sb.append("（本服未启用邮箱登录）\n");
            }
        }
        sb.append("(输入 /cancel 取消)");
        return sb.toString();
    }

    /**
     * 拼一次性链接文案。
     *
     * <p>点明"10 分钟"与"只能用一次"：链接会留在群聊记录里，
     * 玩家需要知道它不会一直有效，也需要知道别人点了也抢不走他的账号作废。</p>
     */
    String linkMessage(String appId, String senderQq, QqBindTokenStore.Intent intent, String pendingName) {
        String token = QqBindTokenStore.issue(senderQq, appId, intent, pendingName);
        String link = PublicUrl.bindLink(token);
        boolean email = emailAvailable();
        StringBuilder sb = new StringBuilder();
        if (intent == QqBindTokenStore.Intent.REGISTER) {
            sb.append("===== 网页注册 =====\n");
            if (pendingName != null && !pendingName.isEmpty()) {
                sb.append("角色名：").append(pendingName).append("\n");
            }
            sb.append("机器人无法私聊你，请在浏览器打开下面的链接设置密码：\n");
        } else {
            sb.append("===== 网页绑定 =====\n");
            sb.append("机器人无法私聊你，请在浏览器打开下面的链接完成绑定：\n");
        }
        sb.append(link).append("\n");
        sb.append("链接 ").append(QqBindTokenStore.TTL_MS / 60_000).append(" 分钟内有效，且只能用一次。\n");
        if (intent == QqBindTokenStore.Intent.BIND) {
            sb.append(email
                    ? "支持用户名密码，也支持邮箱验证码。\n"
                    : "支持用户名密码登录。\n");
        } else {
            sb.append(email
                    ? "需要填写密码与邮箱验证码。\n"
                    : "只需要填写密码。\n");
        }
        sb.append("完成后回到 QQ 直接发指令即可。");
        return sb.toString();
    }
}
