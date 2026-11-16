package com.mtxgdn.qq;

import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.identity.CredentialAuthService;
import com.mtxgdn.identity.QqAccountRegistrar;
import com.mtxgdn.identity.QqBindTokenStore;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.identity.QqBinding;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.RateLimiter;
import org.mindrot.jbcrypt.BCrypt;


import static com.mtxgdn.qq.QqSupport.actionLog;

/**
 * 账号对话流程域：注册 / 绑定 / 解绑 / 改密 / 注销，以及它们共用的"待处理会话"状态机。
 *
 * <p>这条流程是<b>多轮对话</b>：{@code /注册} 收角色名 → 选方式 → 私聊收密码或网页设密码 → 建号；
 * {@code /绑定} 选方式 → 收用户名密码 / 网页登录 / 邮箱验证码；{@code /改密} 收旧密码 → 收新密码；
 * {@code /注销} 收密码确认。状态存在 {@link QqAdapterContext#pendingSessions}（按 openid 索引），
 * 由 {@link QqMessageRouter} 在把消息交给指令管线之前先探一次。</p>
 *
 * <h3>为什么多出「网页」这一条路</h3>
 * <p>新申请的 QQ 官方机器人通常没有单聊（C2C）权限，原来"在私聊里输入密码"的多轮流程
 * 在那种部署下完全走不通（提示发不出去，玩家只看到没反应）。现在每条流程都先试私聊，
 * 发不出去就改发一次性网页链接（{@link QqBindLinkFlow}）——聊天里只出现链接，
 * 密码与验证码都在浏览器里输入。</p>
 *
 * <p><b>原样保留的防坑逻辑</b>：</p>
 * <ul>
 *   <li><b>注册结果只走私聊回执</b>：历史 bug 是注册可从群里发起、密码在私聊输入、
 *       回执却发回群里，复用了 {@code /注册} 那条消息的被动回复上下文而被平台去重拒收，
 *       玩家永远等不到结果，还会把账号信息暴露在群里；</li>
 *   <li><b>凭据校验的限流与锁定</b>：策略本体在 {@link CredentialAuthService}
 *       （两条通道共用一份，避免"换入口继续试"），这里只传聊天通道的前缀；</li>
 *   <li><b>注册失败回滚</b>：建号/绑定/建角色任何一步失败都会解绑并删掉刚插入的 users 行，
 *       由 {@link QqAccountRegistrar} 统一保证；</li>
 *   <li><b>QQ 官方身份不做邮箱验证</b>：聊天注册 {@code email} 传 null（跳过 verify_code 流程），
 *       绑定成功时也不伪造 {@code <openid>@qq.com} 邮箱去覆盖用户已有邮箱。</li>
 * </ul>
 */
final class QqAccountFlow {
    /**
     * 服务查询面：本类是启动期创建的长生命周期对象（由 {@code QqOfficialAdapter} 装配），
     * 装配时容器已就绪；这里是该适配器边界唯一的一处容器引用。
     */
    private final com.mtxgdn.common.service.ServiceLookup services =
            com.mtxgdn.common.service.ServiceRegistry.container();

    /** 聊天通道的登录限流前缀（改了这个等于把已有计数清零）。 */
    private static final String LOGIN_RATE_LIMIT_PREFIX = "qq_official_login_";

    private final QqAdapterContext ctx;
    private final QqMessageSender sender;
    private final QqBindLinkFlow bindLink;
    private final UserServiceDAO USER_DAO = new UserServiceDAO();

    QqAccountFlow(QqAdapterContext ctx, QqMessageSender sender, QqBindLinkFlow bindLink) {
        this.ctx = ctx;
        this.sender = sender;
        this.bindLink = bindLink;
    }

    // ==================== OneBotAccountFlowClient ====================

    void handleRegister(OneBotSession session, String selfId, String senderQq, String arg, Long sourceGroupId) {
        QqBinding existingBinding = ctx.bindingService.findByQq(senderQq);
        if (existingBinding != null) {
            if (services.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                try {
                    PlayerInfo player = services.getPlayerService().createPlayer(
                            existingBinding.getUserId(), arg.trim());
                    String guideMsg = services.getGuideService().buildCreateMessage(player);
                    sender.replyToSource(session, selfId, senderQq, sourceGroupId, guideMsg);
                    actionLog.logCreatePlayer(existingBinding.getUserId(), player.getName());
                } catch (RuntimeException e) {
                    sender.replyToSource(session, selfId, senderQq, sourceGroupId,
                            "创建角色失败: " + e.getMessage());
                }
                return;
            }
            sender.replyToSource(session, selfId, senderQq, sourceGroupId,
                    "你已注册角色，无需重复注册。如需重来请先用 /unbind 解绑。");
            return;
        }
        String name = arg.trim();
        if (name.isEmpty()) {
            sender.replyToSource(session, selfId, senderQq, sourceGroupId,
                    "用法: /register <角色名>\n设置密码的方式会在下一步选择（私聊或网页）");
            return;
        }
        String[] parts = name.split("\\s+", 2);
        name = parts[0].trim();
        if (name.length() > QqAccountRegistrar.MAX_NAME_LENGTH) {
            sender.replyToSource(session, selfId, senderQq, sourceGroupId,
                    "角色名不能超过" + QqAccountRegistrar.MAX_NAME_LENGTH + "个字");
            return;
        }
        if (UserAccountDAO.isUsernameExists(name)) {
            sender.replyToSource(session, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }
        QqPendingSession ps = new QqPendingSession("register", sourceGroupId);
        ps.username = name;
        ps.state = "WAITING_REGISTER_METHOD";
        ctx.pendingSessions.put(senderQq, ps);
        // 先试私聊菜单；没有单聊权限时改发网页链接（聊天里不出现密码）。
        // 链接发出去后会话就没用了（网页流程独立），菜单发出去则要保留等玩家回复 1/2。
        if (bindLink.deliver(session, selfId, senderQq, sourceGroupId, ctx.appId,
                QqBindTokenStore.Intent.REGISTER, name) == QqBindLinkFlow.Delivery.LINK_SENT) {
            ctx.pendingSessions.remove(senderQq);
        }
    }

    void handleBind(OneBotSession session, String selfId, String senderQq, Long sourceGroupId) {
        QqBinding existing = ctx.bindingService.findByQq(senderQq);
        if (existing != null) {
            sender.sendPrivateMsg(session, selfId, senderQq,
                    "你已绑定账号 (用户ID: " + existing.getUserId() + ")，/unbind 可解绑。");
            return;
        }
        QqPendingSession ps = new QqPendingSession("bind", sourceGroupId);
        ps.state = "WAITING_BIND_METHOD";
        ctx.pendingSessions.put(senderQq, ps);
        // 先试私聊菜单；没有单聊权限时改发网页链接到来源会话（群里不会出现密码）。
        // 链接发出去后会话就没用了（网页流程独立），菜单发出去则要保留等玩家回复 1/2/3。
        if (bindLink.deliver(session, selfId, senderQq, sourceGroupId, ctx.appId,
                QqBindTokenStore.Intent.BIND, null) == QqBindLinkFlow.Delivery.LINK_SENT) {
            ctx.pendingSessions.remove(senderQq);
        }
    }

    void handleUnbind(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            sender.sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号。");
            return;
        }
        try {
            ctx.bindingService.unbindByQq(senderQq);
            sender.sendPrivateMsg(session, selfId, senderQq, "解绑成功！");
        } catch (RuntimeException e) {
            sender.sendPrivateMsg(session, selfId, senderQq, "解绑失败: " + e.getMessage());
        }
    }

    void handleChangePassword(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            sender.sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        QqPendingSession ps = new QqPendingSession("changePassword", null);
        ps.state = "WAITING_OLD_PASSWORD";
        ctx.pendingSessions.put(senderQq, ps);
        sender.sendPrivateMsg(session, selfId, senderQq,
                "===== 修改密码 =====\n请输入当前密码：\n(输入 /cancel 取消)");
    }

    void handleDeleteAccount(OneBotSession session, String selfId, String senderQq) {
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            sender.sendPrivateMsg(session, selfId, senderQq, "你尚未绑定账号，请先 /绑定。");
            return;
        }
        QqPendingSession ps = new QqPendingSession("deleteAccount", null);
        ps.state = "WAITING_DELETE_CONFIRM";
        ctx.pendingSessions.put(senderQq, ps);
        sender.sendPrivateMsg(session, selfId, senderQq,
                "===== 注销账号 =====\n⚠ 此操作不可逆！\n注销后将删除你的角色、物品、灵石等全部数据！\n\n请输入账号密码确认注销：\n(输入 /cancel 取消)");
    }

    // ==================== Pending Flow ====================

    void handlePendingFlow(String senderQq, QqPendingSession ps, String message) {
        if ("/cancel".equalsIgnoreCase(message.trim())) {
            ctx.pendingSessions.remove(senderQq);
            // 向导流程只会在私聊里推进，因此回执也走私聊（群里那条消息的被动回复窗口多半已过期）
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "已取消。");
            return;
        }
        switch (ps.state) {
            case "WAITING_BIND_METHOD":
                handleBindMethodChoice(senderQq, ps, message.trim());
                break;
            case "WAITING_REGISTER_METHOD":
                handleRegisterMethodChoice(senderQq, ps, message.trim());
                break;
            case "WAITING_USERNAME":
                ps.username = message.trim();
                ps.state = "WAITING_PASSWORD";
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "请输入密码：\n(输入 /cancel 取消)");
                break;
            case "WAITING_OLD_PASSWORD":
                handleOldPasswordInput(senderQq, ps, message.trim());
                break;
            case "WAITING_NEW_PASSWORD":
                handleNewPasswordInput(senderQq, ps, message.trim());
                break;
            case "WAITING_DELETE_CONFIRM":
                handleDeleteConfirm(senderQq, ps, message.trim());
                break;
            case "WAITING_EMAIL_LOGIN":
                handleEmailLoginInput(senderQq, ps, message.trim());
                break;
            case "WAITING_EMAIL_CODE":
                handleEmailCodeInput(senderQq, ps, message.trim());
                break;
            case "WAITING_PASSWORD":
                String passwordInput = message.trim();
                if (passwordInput.length() < QqAccountRegistrar.MIN_PASSWORD_LENGTH) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "密码不少于" + QqAccountRegistrar.MIN_PASSWORD_LENGTH
                                    + "位，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if ("register".equals(ps.type)) {
                    ps.password = passwordInput;
                    // QQ 官方用户以 openid 身份进入，注册无需邮箱验证（跳过 verify_code 流程）
                    doRegisterComplete(senderQq, ps, passwordInput, null, null);
                    ctx.pendingSessions.remove(senderQq);
                } else {
                    doBindComplete(senderQq, ps, passwordInput);
                    ctx.pendingSessions.remove(senderQq);
                }
                break;
            case "WAITING_EMAIL":
                String emailInput = message.trim();
                if (emailInput.isEmpty()) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "邮箱不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                if (!UserAccountDAO.isValidEmail(emailInput)) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "邮箱格式不正确，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                ps.email = emailInput.toLowerCase();
                try {
                    VerificationCodeService verificationCodeService = new VerificationCodeService();
                    if (!verificationCodeService.canSendCode(ps.email)) {
                        sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发送过于频繁，请稍后再试：\n(输入 /cancel 取消)");
                        return;
                    }
                    String code = verificationCodeService.generateAndStoreCode(ps.email);
                    EmailService.sendVerificationCode(ps.email, code);
                    ps.state = "WAITING_CODE";
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "验证码已发送至邮箱，请输入验证码：\n(输入 /cancel 取消)");
                } catch (Exception e) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发送验证码失败: " + e.getMessage() + "\n请重新输入邮箱：\n(输入 /cancel 取消)");
                }
                break;
            case "WAITING_CODE":
                String codeInput = message.trim();
                if (codeInput.isEmpty()) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "验证码不能为空，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                VerificationCodeService vcService = new VerificationCodeService();
                if (!vcService.verifyCode(ps.email, codeInput)) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "验证码错误或已过期，请重新输入：\n(输入 /cancel 取消)");
                    return;
                }
                doRegisterComplete(senderQq, ps, ps.password, ps.email, codeInput);
                ctx.pendingSessions.remove(senderQq);
                break;
            default:
                break;
        }
    }

    /** 绑定方式选择：1 用户名密码 / 2 网页 / 3 邮箱验证码。 */
    private void handleBindMethodChoice(String senderQq, QqPendingSession ps, String choice) {
        switch (choice) {
            case "1":
                ps.state = "WAITING_USERNAME";
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 /cancel 取消)");
                break;
            case "2":
                if (!bindLink.webAvailable()) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "本服未开放网页登录（管理员尚未配置 server.public-url）。\n请回复 1 使用用户名密码。");
                    return;
                }
                // 链接发在来源会话里：玩家可能是从群里发起的，私聊窗口未必还在。
                // 发出去了才移除会话（网页流程独立于本会话）；发失败则保留，玩家还能改选 1/3。
                if (bindLink.sendLink(ctx.self, ctx.selfId, senderQq, ps.sourceGroupId, ctx.appId,
                        QqBindTokenStore.Intent.BIND, null)) {
                    ctx.pendingSessions.remove(senderQq);
                } else {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "链接发送失败，请稍后重试，或回复 1 使用用户名密码。");
                }
                break;
            case "3":
                if (!bindLink.emailAvailable()) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "本服未启用邮箱登录（管理员尚未配置 SMTP）。\n请回复 1 使用用户名密码。");
                    return;
                }
                ps.state = "WAITING_EMAIL_LOGIN";
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "===== 邮箱验证码登录 =====\n请输入账号绑定的邮箱：\n(输入 /cancel 取消)");
                break;
            default:
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "请回复数字选择：1 用户名密码"
                                + (bindLink.webAvailable() ? "｜2 网页登录" : "")
                                + (bindLink.emailAvailable() ? "｜3 邮箱验证码" : ""));
                break;
        }
    }

    /** 注册方式选择：1 私聊设密码 / 2 网页设密码。 */
    private void handleRegisterMethodChoice(String senderQq, QqPendingSession ps, String choice) {
        switch (choice) {
            case "1":
                ps.state = "WAITING_PASSWORD";
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "角色名【" + ps.username + "】可用！\n请在私聊中发送你的密码（不少于"
                                + QqAccountRegistrar.MIN_PASSWORD_LENGTH + "位）\n(输入 /cancel 取消)");
                break;
            case "2":
                if (!bindLink.webAvailable()) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "本服未开放网页注册（管理员尚未配置 server.public-url）。\n请回复 1 在私聊里设置密码。");
                    return;
                }
                if (bindLink.sendLink(ctx.self, ctx.selfId, senderQq, ps.sourceGroupId, ctx.appId,
                        QqBindTokenStore.Intent.REGISTER, ps.username)) {
                    ctx.pendingSessions.remove(senderQq);
                } else {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                            "链接发送失败，请稍后重试，或回复 1 在私聊里设置密码。");
                }
                break;
            default:
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "请回复数字选择：1 私聊设置密码"
                                + (bindLink.webAvailable() ? "｜2 网页设置密码" : ""));
                break;
        }
    }

    /**
     * 邮箱验证码登录：收邮箱 → 发码。
     *
     * <p><b>不暴露邮箱是否注册</b>：无论有没有这个邮箱，都回同一句话并照常发码。
     * 否则这个接口就成了"某邮箱是否玩过本服"的查询器。真正是否关联账号，
     * 要等验证码输入正确之后才揭晓（那时对方已经证明了邮箱所有权）。</p>
     */
    private void handleEmailLoginInput(String senderQq, QqPendingSession ps, String emailInput) {
        if (!UserAccountDAO.isValidEmail(emailInput)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "邮箱格式不正确，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        String email = emailInput.toLowerCase();
        ps.email = email;
        try {
            VerificationCodeService verificationCodeService = new VerificationCodeService();
            if (!verificationCodeService.canSendCode(email)) {
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                        "发送过于频繁，请稍后再试：\n(输入 /cancel 取消)");
                return;
            }
            String code = verificationCodeService.generateAndStoreCode(email);
            EmailService.sendVerificationCode(email, code);
            ps.state = "WAITING_EMAIL_CODE";
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "验证码已发送（若该邮箱已注册）。\n请输入验证码：\n(输入 /cancel 取消)");
        } catch (Exception e) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "发送验证码失败: " + e.getMessage() + "\n请重新输入邮箱：\n(输入 /cancel 取消)");
        }
    }

    /** 邮箱验证码登录：验码 → 找账号 → 绑定。 */
    private void handleEmailCodeInput(String senderQq, QqPendingSession ps, String codeInput) {
        if (!RateLimiter.allow("qq_email_login_" + senderQq, 5, 60)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "尝试过于频繁，请 1 分钟后再试。");
            return;
        }
        if (!new VerificationCodeService().verifyCode(ps.email, codeInput)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "验证码错误或已过期，或该邮箱未关联账号。\n请重新输入验证码：\n(输入 /cancel 取消)");
            return;
        }
        UserServiceDAO.EmailOwnerRow owner = USER_DAO.findIdAndUsernameByEmail(ps.email);
        if (owner == null) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "该邮箱未关联任何账号。\n可以改用 /绑定 的用户名密码方式，或先 /注册。");
            ctx.pendingSessions.remove(senderQq);
            return;
        }
        try {
            ctx.bindingService.bind(senderQq, owner.id());
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "绑定成功！\n用户名: " + owner.username() + "\n用户ID: " + owner.id());
        } catch (RuntimeException e) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "绑定失败: " + e.getMessage());
        }
    }

    private void handleOldPasswordInput(String senderQq, QqPendingSession ps, String oldPassword) {
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = UserAccountDAO.getUsernameByUserId(b.getUserId());
        if (username == null) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        CredentialAuthService.Result check = checkCredentials(username, oldPassword);
        if (!check.ok()) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    check.error() + "\n请重新输入当前密码：\n(输入 /cancel 取消)");
            return;
        }
        ps.state = "WAITING_NEW_PASSWORD";
        sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "请输入新密码（不少于6位）：\n(输入 /cancel 取消)");
    }

    private void handleNewPasswordInput(String senderQq, QqPendingSession ps, String newPassword) {
        if (newPassword.length() < QqAccountRegistrar.MIN_PASSWORD_LENGTH) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "新密码不少于" + QqAccountRegistrar.MIN_PASSWORD_LENGTH + "位，请重新输入：\n(输入 /cancel 取消)");
            return;
        }
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        try {
            String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            UserAccountDAO.updatePassword(b.getUserId(), hashedPassword);
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "密码修改成功！");
            actionLog.logCustom(b.getUserId(), "系统", "修改密码", "成功");
        } catch (RuntimeException e) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "密码修改失败: " + e.getMessage());
        }
    }

    private void handleDeleteConfirm(String senderQq, QqPendingSession ps, String password) {
        QqBinding b = ctx.bindingService.findByQq(senderQq);
        if (b == null) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        String username = UserAccountDAO.getUsernameByUserId(b.getUserId());
        if (username == null) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "发生错误，请重新操作。");
            return;
        }
        CredentialAuthService.Result check = checkCredentials(username, password);
        if (!check.ok()) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    check.error() + "\n请重新输入密码：\n(输入 /cancel 取消)");
            return;
        }
        try {
            PlayerInfo player = services.getPlayerService().getPlayerByUserId(check.userId());
            if (player != null) {
                services.getPlayerService().deletePlayer(player.getId());
            }
            UserAccountDAO.deleteUser(check.userId());
            ctx.bindingService.unbindByQq(senderQq);
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "账号已注销。感谢您的陪伴，修仙之路后会有期！");
        } catch (RuntimeException e) {
            ctx.pendingSessions.remove(senderQq);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "注销失败: " + e.getMessage());
        }
    }

    /**
     * 完成注册：仅在<b>私聊</b>上下文调用（密码只能私聊输入），因此结果一律私聊回执。
     *
     * <p>历史 bug：注册可以从群里 {@code /注册} 发起，密码却在私聊输入，完成回执却发回群里，
     * 复用了 {@code /注册} 那条消息的被动回复上下文（同一 msg_id/msg_seq）而被平台去重拒收，
     * 玩家永远等不到注册结果；同时也会把账号信息暴露在群里。</p>
     *
     * <p>建号、绑定、建角色的顺序与失败回滚都在 {@link QqAccountRegistrar} 里 ——
     * 网页注册走的是同一份实现，不会出现"两条入口回滚方式不一样"。</p>
     */
    private void doRegisterComplete(String senderQq, QqPendingSession ps,
                                    String password, String email, String code) {
        if (UserAccountDAO.isUsernameExists(ps.username)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "角色名【" + ps.username + "】已被占用，请重新注册。");
            return;
        }
        QqAccountRegistrar.Result result = QqAccountRegistrar.register(
                services, ps.username, password, email, code,
                new QqAccountRegistrar.BindHook() {
                    @Override
                    public void bind(long userId) {
                        ctx.bindingService.bind(senderQq, userId);
                    }

                    @Override
                    public void unbind(long userId) {
                        try {
                            ctx.bindingService.unbindByQq(senderQq);
                        } catch (RuntimeException ignored) {
                            // 没绑上就没有可解绑的
                        }
                    }
                });
        if (!result.ok()) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "注册失败: " + result.error());
            return;
        }
        String reply;
        try {
            PlayerInfo player = services.getPlayerService().getPlayerByUserId(result.userId());
            reply = player == null ? "注册成功！" : services.getGuideService().buildCreateMessage(player);
        } catch (RuntimeException e) {
            reply = "注册成功！";
        }
        sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, reply);
        actionLog.logCreatePlayer(result.userId(), ps.username);
    }

    private void doBindComplete(String senderQq, QqPendingSession ps, String password) {
        CredentialAuthService.Result check = checkCredentials(ps.username, password);
        if (!check.ok()) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, check.error() + "\n重试请发送 /bind。");
            return;
        }
        try {
            ctx.bindingService.bind(senderQq, check.userId());
            // 绑定成功：不再伪造 <openid>@qq.com 邮箱（QQ官方身份与邮箱无关，且不应覆盖用户已有邮箱）
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "绑定成功！\n用户名: " + ps.username + "\n用户ID: " + check.userId());
        } catch (RuntimeException e) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "绑定失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 校验凭据。
     *
     * <p>策略本体在 {@link CredentialAuthService}：聊天与网页两条入口共用同一份限流、
     * 锁定与失败计数，否则"网页端被锁了、聊天端还能继续试"就等于把锁定绕过去了。</p>
     */
    private CredentialAuthService.Result checkCredentials(String username, String password) {
        return CredentialAuthService.check(username, password, LOGIN_RATE_LIMIT_PREFIX);
    }
}
