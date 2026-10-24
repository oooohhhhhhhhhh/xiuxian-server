package com.mtxgdn.service;

import com.google.gson.JsonObject;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.common.GameMessage;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.entity.User;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.JwtUtil;
import jakarta.ws.rs.core.Response;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import com.mtxgdn.i18n.I18n;

/**
 * 账号中心的业务逻辑：注册校验、BCrypt 哈希与校验、JWT 签发、登录锁定策略、
 * 错误码与文案。
 *
 * <p>所有 SQL 都在 {@link UserServiceDAO} 里。本类刻意<b>不</b>碰
 * {@code Connection} / {@code PreparedStatement} / {@code SQLException}：
 * 一是这是安全代码，改判定与改语句必须能分别 review；
 * 二是"哪条语句在事务里"必须一眼看得出来 —— 现在本类里一条语句都没有，
 * 因此不存在"顺手开个短连接"的可能。</p>
 */
public class UserService {

    /**
     * {@code users} 表的数据访问入口。
     *
     * <p>无状态、线程安全，因此做成 {@code static final} 常量：
     * 每个 {@code UserService} 实例都新建一个 DAO 只会白白多出对象，
     * 也让"DAO 有没有藏状态"这件事变得可疑。</p>
     */
    private static final UserServiceDAO USER_DAO = new UserServiceDAO();

    /** 登录连续失败多少次后锁定账号。 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** 锁定时长（分钟）。 */
    private static final long LOCK_DURATION_MINUTES = 15;

    // 用户名格式：3-32字符，只允许字母、数字和下划线
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,32}$");
    // 邮箱格式验证
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public Response register(String username, String rawPassword, String email, String code) {
        if (username == null || username.trim().isEmpty()) {
            return buildError(400, "用户名不能为空");
        }
        
        String trimmedUsername = username.trim();
        
        // 长度检查：3-32字符
        if (trimmedUsername.length() < 3) {
            return buildError(400, "用户名长度不能少于3个字符");
        }
        if (trimmedUsername.length() > 32) {
            return buildError(400, "用户名长度不能超过32个字符");
        }
        
        // 格式检查：只允许字母、数字和下划线
        if (!USERNAME_PATTERN.matcher(trimmedUsername).matches()) {
            return buildError(400, "用户名只能包含字母、数字和下划线");
        }
        
        // 密码强度检查：至少8位
        if (rawPassword == null || rawPassword.length() < 8) {
            return buildError(400, "密码长度不能少于8位");
        }
        if (rawPassword.length() > 128) {
            return buildError(400, "密码长度不能超过128位");
        }

        if (USER_DAO.existsByUsername(trimmedUsername)) {
            return buildError(409, GameErrorCode.AUTH_USERNAME_EXISTS.getMessage());
        }

        boolean verifyCodeEnabled = AppConfig.getBoolean("verify_code.enabled", true);
        String trimmedEmail = null;

        if (verifyCodeEnabled) {
            if (email == null || email.trim().isEmpty()) {
                return buildError(400, "邮箱不能为空");
            }
            trimmedEmail = email.trim().toLowerCase();

            if (!isValidEmail(trimmedEmail)) {
                return buildError(400, "邮箱格式不正确");
            }

            if (!isAllowedEmailDomain(trimmedEmail)) {
                return buildError(400, "不支持的邮箱类型");
            }

            if (USER_DAO.existsByEmail(trimmedEmail)) {
                return buildError(409, "该邮箱已被注册");
            }

            if (code == null || code.trim().isEmpty()) {
                return buildError(400, "验证码不能为空");
            }

            VerificationCodeService verificationCodeService = new VerificationCodeService();
            if (!verificationCodeService.verifyCode(trimmedEmail, code.trim())) {
                return buildError(400, "验证码错误或已过期");
            }
        }

        String hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        User user = USER_DAO.insertUser(trimmedUsername, hashedPassword, trimmedEmail);
        if (user == null) {
            return buildError(500, "注册失败，请稍后重试");
        }

        PermissionService.assignDefaultRole(user.getId());

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        JsonObject data = new JsonObject();
        data.addProperty("id", user.getId());
        data.addProperty("username", user.getUsername());
        data.addProperty("token", token);

        return Response.ok(GameMessage.restOk(I18n.t("auth.register_success"), data).toString()).build();
    }

    private boolean isValidEmail(String email) {
        return EMAIL_PATTERN.matcher(email).matches();
    }

    private boolean isAllowedEmailDomain(String email) {
        String allowedDomains = AppConfig.get("verify_code.allowed_domains", "qq.com,163.com,126.com,gmail.com,outlook.com,hotmail.com");
        List<String> domains = Arrays.asList(allowedDomains.split(","));
        int atIndex = email.lastIndexOf('@');
        if (atIndex <= 0 || atIndex >= email.length() - 1) {
            return false;
        }
        String domain = email.substring(atIndex + 1).toLowerCase();
        return domains.contains(domain);
    }

    public Response login(String username, String rawPassword) {
        if (username == null || username.trim().isEmpty()) {
            return buildError(400, "用户名不能为空");
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            return buildError(400, "密码不能为空");
        }

        String trimmedUsername = username.trim();

        User user = USER_DAO.findByUsernameWithLockInfo(trimmedUsername);
        if (user == null) {
            return buildError(401, GameErrorCode.AUTH_WRONG_PASSWORD.getMessage());
        }

        if (isAccountLocked(user)) {
            long remainingSeconds = (user.getLockedUntil().getTime() - System.currentTimeMillis()) / 1000;
            return buildError(423, "账号已被锁定，请 " + remainingSeconds + " 秒后再试");
        }

        if (!BCrypt.checkpw(rawPassword, user.getPassword())) {
            incrementFailedAttempts(user.getId());
            return buildError(401, GameErrorCode.AUTH_WRONG_PASSWORD.getMessage());
        }

        resetFailedAttempts(user.getId());

        String token = JwtUtil.generateToken(user.getId(), user.getUsername());

        // 插件事件：玩家登录（REST 通道）
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.PLAYER_LOGIN)
                        .data("userId", user.getId())
                        .data("username", user.getUsername())
                        .build());

        JsonObject data = new JsonObject();
        data.addProperty("id", user.getId());
        data.addProperty("username", user.getUsername());
        data.addProperty("token", token);

        return Response.ok(GameMessage.restOk(I18n.t("auth.login_success"), data).toString()).build();
    }

    public User authenticate(String username, String rawPassword) {
        String trimmedUsername = username.trim();
        if (trimmedUsername.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            return null;
        }
        User user = USER_DAO.findByUsername(trimmedUsername);
        if (user == null || !BCrypt.checkpw(rawPassword, user.getPassword())) {
            return null;
        }
        return user;
    }

    private boolean isAccountLocked(User user) {
        Timestamp lockedUntil = user.getLockedUntil();
        if (lockedUntil == null) {
            return false;
        }
        return lockedUntil.getTime() > System.currentTimeMillis();
    }

    /**
     * 记一次登录失败，达到阈值时锁定账号。
     *
     * <p>阈值与锁定时长是<b>认证策略</b>，留在本类；具体怎么落库（在 SQL 里做加法、
     * 用 {@code COALESCE} 保住原有锁定时刻）属于数据访问，在 DAO 里。</p>
     */
    private void incrementFailedAttempts(long userId) {
        int maxAttempts = MAX_FAILED_ATTEMPTS;
        long lockDurationMinutes = LOCK_DURATION_MINUTES;
        USER_DAO.recordFailedAttempt(userId, maxAttempts,
                new Timestamp(System.currentTimeMillis() + lockDurationMinutes * 60 * 1000));
    }

    private void resetFailedAttempts(long userId) {
        USER_DAO.clearFailedAttempts(userId);
    }

    private Response buildError(int httpStatus, String message) {
        JsonObject body = GameMessage.restError(httpStatus, message);
        return Response.status(httpStatus).entity(body.toString()).build();
    }

    public void updateUserEmail(long userId, String email) {
        USER_DAO.updateEmail(userId, email);
    }

    public String getUserEmail(long userId) {
        return USER_DAO.findEmailById(userId);
    }

    public Response changePassword(long userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) {
            return buildError(400, "原密码不能为空");
        }
        if (newPassword == null || newPassword.length() < 8) {
            return buildError(400, "新密码长度不能少于8位");
        }
        if (newPassword.length() > 128) {
            return buildError(400, "新密码长度不能超过128位");
        }
        if (oldPassword.equals(newPassword)) {
            return buildError(400, "新密码不能与原密码相同");
        }

        UserServiceDAO.CredentialRow credential = USER_DAO.findCredentialById(userId);
        if (credential == null) {
            return buildError(404, "用户不存在");
        }
        if (!BCrypt.checkpw(oldPassword, credential.hashedPassword())) {
            return buildError(401, "原密码错误");
        }

        String newHashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        USER_DAO.updatePasswordHash(userId, newHashedPassword);

        JsonObject data = new JsonObject();
        data.addProperty("message", I18n.t("user.password_changed_successfully"));
        return Response.ok(GameMessage.restOk(I18n.t("auth.password_changed"), data).toString()).build();
    }

    /**
     * V13：校验某个用户的密码是否正确（用于删号等不可逆操作的二次确认）。
     *
     * <p>与登录路径不同，这里<b>不</b>累加失败计数、不触发账号锁定 ——
     * 避免攻击者用一个窃取来的 token 反复试探密码把受害者账号锁死。
     * 调用方（REST/QQ 通道）需自行做限流。</p>
     *
     * @return 密码正确返回 true；用户不存在、密码为空或哈希异常返回 false
     */
    public boolean verifyPassword(long userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            return false;
        }
        String hashed = USER_DAO.findPasswordHashById(userId);
        if (hashed == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            // bcrypt 哈希格式异常
            return false;
        }
    }

    public Response deleteUser(long userId) {
        if (!USER_DAO.deleteUserById(userId)) {
            return buildError(404, "用户不存在");
        }
        JsonObject data = new JsonObject();
        data.addProperty("message", I18n.t("user.account_deleted_successfully"));
        return Response.ok(GameMessage.restOk(I18n.t("auth.account_deleted"), data).toString()).build();
    }

    public Response resetPasswordByEmail(String email, String newPassword) {
        UserServiceDAO.EmailOwnerRow owner = USER_DAO.findIdAndUsernameByEmail(email);
        if (owner == null) {
            return buildError(404, "该邮箱未注册");
        }

        String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        USER_DAO.resetPassword(owner.id(), hashedPassword);

        JsonObject data = new JsonObject();
        data.addProperty("message", I18n.t("user.password_reset_successfully"));
        data.addProperty("username", owner.username());
        return Response.ok(GameMessage.restOk(I18n.t("auth.password_reset"), data).toString()).build();
    }
}
