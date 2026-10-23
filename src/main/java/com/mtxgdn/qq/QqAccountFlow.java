package com.mtxgdn.qq;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.onebot.OneBotSession;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.EmailService;
import com.mtxgdn.util.RateLimiter;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import static com.mtxgdn.qq.QqSupport.actionLog;
import static com.mtxgdn.qq.QqSupport.log;

/**
 * 账号对话流程域：注册 / 绑定 / 解绑 / 改密 / 注销，以及它们共用的"待处理会话"状态机。
 *
 * <p>这条流程是<b>多轮对话</b>：{@code /注册} 收角色名 → 私聊收密码 → 建号；
 * {@code /改密} 收旧密码 → 收新密码；{@code /注销} 收密码确认。状态存在
 * {@link QqAdapterContext#pendingSessions}（按 openid 索引），由 {@link QqMessageRouter}
 * 在把消息交给指令管线之前先探一次。拆分时整条状态机（含四个 state 分支与
 * 五个 handler）都留在同一个类里 —— 拆开就会让"谁推进状态"变得看不出来。</p>
 *
 * <p><b>原样保留的防坑逻辑</b>（一行未改）：</p>
 * <ul>
 *   <li><b>注册结果只走私聊回执</b>：历史 bug 是注册可从群里发起、密码在私聊输入、
 *       回执却发回群里，复用了 {@code /注册} 那条消息的被动回复上下文而被平台去重拒收，
 *       玩家永远等不到结果，还会把账号信息暴露在群里；</li>
 *   <li><b>凭据校验的限流与锁定</b>：同一用户名 5 次/分钟（{@code qq_official_login_} 前缀），
 *       连续 5 次错误锁 15 分钟；失败原因显式带回给玩家（{@link CredentialCheck}），
 *       而不是一律显示"用户名或密码错误"；</li>
 *   <li><b>注册失败回滚</b>：建号异常时解绑并删掉刚插入的 users 行，避免留下半截账号；</li>
 *   <li><b>QQ 官方身份不做邮箱验证</b>：注册完成时 {@code email} 传 null（跳过 verify_code 流程），
 *       绑定成功时也不再伪造 {@code <openid>@qq.com} 邮箱去覆盖用户已有邮箱。</li>
 * </ul>
 *
 * <p><b>SQL 一行未改</b>：{@code SELECT id, password, locked_until FROM users WHERE username = ?}、
 * {@code UPDATE users SET failed_attempts = failed_attempts + 1, ...}、
 * {@code UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?}
 * 都是从拆分前的方法体逐字搬过来的（连 {@code catch} 里的日志文案都没动）。</p>
 */
final class QqAccountFlow {

    private final QqAdapterContext ctx;
    private final QqMessageSender sender;

    QqAccountFlow(QqAdapterContext ctx, QqMessageSender sender) {
        this.ctx = ctx;
        this.sender = sender;
    }

    // ==================== OneBotAccountFlowClient ====================

    void handleRegister(OneBotSession session, String selfId, String senderQq, String arg, Long sourceGroupId) {
        QqBinding existingBinding = ctx.bindingService.findByQq(senderQq);
        if (existingBinding != null) {
            if (ServiceRegistry.getPlayerService().getPlayerByUserId(existingBinding.getUserId()) == null) {
                try {
                    PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(
                            existingBinding.getUserId(), arg.trim());
                    String guideMsg = NewbieGuideService.getWelcomeMessage(player);
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
                    "用法: /register <角色名>\n密码将在下一步通过私聊安全发送");
            return;
        }
        String[] parts = name.split("\\s+", 2);
        name = parts[0].trim();
        if (name.length() > 16) {
            sender.replyToSource(session, selfId, senderQq, sourceGroupId, "角色名不能超过16个字");
            return;
        }
        if (UserAccountDAO.isUsernameExists(name)) {
            sender.replyToSource(session, selfId, senderQq, sourceGroupId, "角色名已被占用，请换一个。");
            return;
        }
        QqPendingSession ps = new QqPendingSession("register", sourceGroupId);
        ps.username = name;
        ps.state = "WAITING_PASSWORD";
        ctx.pendingSessions.put(senderQq, ps);
        sender.replyToSource(session, selfId, senderQq, sourceGroupId,
                "角色名【" + name + "】可用！\n请在私聊中发送你的密码（不少于6位）\n(输入 /cancel 取消)");
    }

    void handleBind(OneBotSession session, String selfId, String senderQq) {
        if (ctx.bindingService.findByQq(senderQq) != null) {
            QqBinding b = ctx.bindingService.findByQq(senderQq);
            sender.sendPrivateMsg(session, selfId, senderQq,
                    "你已绑定账号 (用户ID: " + b.getUserId() + ")，/unbind 可解绑。");
            return;
        }
        QqPendingSession ps = new QqPendingSession();
        ctx.pendingSessions.put(senderQq, ps);
        sender.sendPrivateMsg(session, selfId, senderQq,
                "===== 账号绑定 =====\n请输入游戏用户名：\n(输入 /cancel 取消)");
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
            case "WAITING_PASSWORD":
                String passwordInput = message.trim();
                if (passwordInput.length() < 6) {
                    sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
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
        CredentialCheck check = checkCredentials(username, oldPassword);
        if (check.userId == null) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, check.error + "\n请重新输入当前密码：\n(输入 /cancel 取消)");
            return;
        }
        ps.state = "WAITING_NEW_PASSWORD";
        sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "请输入新密码（不少于6位）：\n(输入 /cancel 取消)");
    }

    private void handleNewPasswordInput(String senderQq, QqPendingSession ps, String newPassword) {
        if (newPassword.length() < 6) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "新密码不少于6位，请重新输入：\n(输入 /cancel 取消)");
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
        CredentialCheck check = checkCredentials(username, password);
        if (check.userId == null) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, check.error + "\n请重新输入密码：\n(输入 /cancel 取消)");
            return;
        }
        try {
            PlayerInfo player = ServiceRegistry.getPlayerService().getPlayerByUserId(check.userId);
            if (player != null) {
                ServiceRegistry.getPlayerService().deletePlayer(player.getId());
            }
            UserAccountDAO.deleteUser(check.userId);
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
     */
    private void doRegisterComplete(String senderQq, QqPendingSession ps,
                                    String password, String email, String code) {
        if (UserAccountDAO.isUsernameExists(ps.username)) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "角色名【" + ps.username + "】已被占用，请重新注册。");
            return;
        }
        if (ctx.bindingService.findByQq(senderQq) != null) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "你已注册角色，无需重复注册。");
            return;
        }
        Long userId = null;
        try {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            userId = UserAccountDAO.insertUser(ps.username, hashedPassword, email);
            if (userId == null) {
                sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "注册失败，请稍后重试。");
                return;
            }
            PermissionService.assignDefaultRole(userId);
            ctx.bindingService.bind(senderQq, userId);
            PlayerInfo player = ServiceRegistry.getPlayerService().createPlayer(userId, ps.username);
            String guideMsg = NewbieGuideService.getWelcomeMessage(player);
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, guideMsg);
            actionLog.logCreatePlayer(userId, ps.username);
        } catch (RuntimeException e) {
            if (userId != null) {
                try { ctx.bindingService.unbindByQq(senderQq); } catch (Exception ignored) {}
                try { UserAccountDAO.deleteUser(userId); } catch (Exception ignored) {}
            }
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "注册失败: " + e.getMessage());
        }
    }

    private void doBindComplete(String senderQq, QqPendingSession ps, String password) {
        CredentialCheck check = checkCredentials(ps.username, password);
        if (check.userId == null) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, check.error + "\n重试请发送 /bind。");
            return;
        }
        try {
            ctx.bindingService.bind(senderQq, check.userId);
            // 绑定成功：不再伪造 <openid>@qq.com 邮箱（QQ官方身份与邮箱无关，且不应覆盖用户已有邮箱）
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq,
                    "绑定成功！\n用户名: " + ps.username + "\n用户ID: " + check.userId);
        } catch (RuntimeException e) {
            sender.sendPrivateMsg(ctx.self, ctx.selfId, senderQq, "绑定失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 凭据校验结果。
     *
     * <p>历史 bug：限流（同一用户名 5 次/分钟）与账号锁定（连续 5 次错误，锁 15 分钟）都直接返回
     * {@code null}，被上层一律显示成「用户名或密码错误」，玩家在正确密码下也会反复看到「密码错误」，
     * 且完全不知道自己在被限流/锁定。这里把原因显式带回给调用方。</p>
     */
    private static final class CredentialCheck {
        /** 校验通过时的用户 ID；失败为 null */
        final Long userId;
        /** 面向玩家的失败提示（成功时为 null） */
        final String error;

        CredentialCheck(Long userId, String error) {
            this.userId = userId;
            this.error = error;
        }
    }

    private CredentialCheck checkCredentials(String username, String password) {
        if (username == null || username.isEmpty()) {
            return new CredentialCheck(null, "用户名不能为空。");
        }
        String rateLimitKey = "qq_official_login_" + username.toLowerCase();
        if (!RateLimiter.allow(rateLimitKey, 5, 60)) {
            log.warn("QQ官方登录尝试过于频繁，已限流: username=" + username);
            return new CredentialCheck(null, "尝试过于频繁，请 1 分钟后再试。");
        }

        String sql = "SELECT id, password, locked_until FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Timestamp lockedUntil = rs.getTimestamp("locked_until");
                    long now = System.currentTimeMillis();
                    if (lockedUntil != null && lockedUntil.getTime() > now) {
                        long minutes = Math.max(1, (lockedUntil.getTime() - now + 59_999) / 60_000);
                        log.warn("QQ官方登录被拒：账号已锁定 " + minutes + " 分钟, username=" + username);
                        return new CredentialCheck(null,
                                "该账号因多次输错密码已被临时锁定，请约 " + minutes + " 分钟后再试（不是密码错误）。");
                    }

                    if (BCrypt.checkpw(password, rs.getString("password"))) {
                        resetFailedAttempts(rs.getLong("id"));
                        return new CredentialCheck(rs.getLong("id"), null);
                    } else {
                        incrementFailedAttempts(rs.getLong("id"));
                    }
                }
            }
        } catch (SQLException e) { log.error("验证用户凭据失败", e); }
        return new CredentialCheck(null, "用户名或密码错误。");
    }

    private void incrementFailedAttempts(long userId) {
        int maxAttempts = 5;
        long lockDurationMinutes = 15;
        String sql = "UPDATE users SET failed_attempts = failed_attempts + 1, " +
                "locked_until = COALESCE(" +
                "CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE NULL END, " +
                "locked_until) " +
                "WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxAttempts);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis() + lockDurationMinutes * 60 * 1000));
            ps.setLong(3, userId);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("更新失败次数失败", e); }
    }

    private void resetFailedAttempts(long userId) {
        String sql = "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("重置失败次数失败", e); }
    }
}
