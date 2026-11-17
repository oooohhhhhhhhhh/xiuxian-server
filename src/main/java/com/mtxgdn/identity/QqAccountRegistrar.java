package com.mtxgdn.identity;

import com.mtxgdn.common.service.ServiceLookup;
import com.mtxgdn.db.UserAccountDAO;
import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.EmailRules;
import org.mindrot.jbcrypt.BCrypt;

/**
 * QQ 官方通道的「建号 + 建角色 + 绑定身份」，聊天与网页两条入口共用这一份。
 *
 * <p><b>为什么不是复用 {@link UserService#register}</b>：那个方法是给 REST/管理后台
 * 用的，规则更严（用户名只允许字母数字下划线、3–32 位，密码 ≥8 位）。QQ 官方通道
 * 一直允许中文角色名、密码 ≥6 位，直接把网页注册接到 {@code UserService.register}
 * 上，等于让老玩家"在群里能注册、在网页上不行" —— 同一个游戏、同一条通道，
 * 两套规则只会让人以为是 bug。所以规则以 QQ 通道的现状为准，写在这里。</p>
 *
 * <h3>顺序与回滚</h3>
 * <p>先建 users 行，再绑定 openid，最后建角色。任何一步失败都要把前面的成果撤掉：
 * 半截账号（有 users 行、没角色、没绑定）会让玩家重试时撞上"角色名已被占用"，
 * 而他自己根本没用上那个名字。这就是 {@code rollback} 存在的理由。</p>
 *
 * <h3>邮箱</h3>
 * <p>邮箱是<b>可选</b>的：QQ 官方身份本身不需要邮箱验证，聊天注册一直传 null。
 * 传了才校验格式、域名白名单、唯一性和验证码 —— 网页注册在
 * {@code verify_code.enabled && EmailService.isConfigured()} 时会要求填。</p>
 */
public final class QqAccountRegistrar {

    /** 角色名上限（与聊天注册的提示文案一致）。 */
    public static final int MAX_NAME_LENGTH = 16;

    /** 密码下限（与聊天注册一致）。 */
    public static final int MIN_PASSWORD_LENGTH = 6;

    private static final UserServiceDAO USER_SERVICE_DAO = new UserServiceDAO();

    private QqAccountRegistrar() {
    }

    /**
     * 绑定钩子：由调用方决定"哪个 adapter 的哪个 openid"。
     *
     * <p>聊天入口传 {@code QqOfficialBindingService} 的实例方法，网页入口传的也是同一份 ——
     * 这样"绑定失败要回滚账号"的逻辑只有一处，不会两边写法不同。</p>
     */
    public interface BindHook {
        /** 绑定 openid 到 userId；失败抛 {@link RuntimeException}（消息会回给玩家）。 */
        void bind(long userId);

        /** 回滚绑定（尽力而为，失败不应掩盖原始错误）。 */
        void unbind(long userId);
    }

    /**
     * 结果。
     *
     * @param userId 成功时的用户 ID
     * @param error  失败原因（面向玩家）
     */
    public record Result(Long userId, String error) {

        public boolean ok() {
            return userId != null;
        }

        static Result success(long userId) {
            return new Result(userId, null);
        }

        static Result failure(String error) {
            return new Result(null, error);
        }
    }

    /**
     * 注册角色并绑定身份。
     *
     * @param services      服务查询面（由调用方提供：适配器用自己的容器引用，REST 用 BaseResource 的）
     * @param username     角色名（聊天里已收集）
     * @param rawPassword  明文密码
     * @param email        邮箱；null / 空白表示不做邮箱验证（聊天通道的行为）
     * @param code         邮箱验证码（email 非空时必填）
     * @param hook         绑定钩子
     */
    public static Result register(ServiceLookup services, String username, String rawPassword,
                                  String email, String code, BindHook hook) {
        if (username == null || username.trim().isEmpty()) {
            return Result.failure("角色名不能为空。");
        }
        String name = username.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            return Result.failure("角色名不能超过" + MAX_NAME_LENGTH + "个字。");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            return Result.failure("密码不少于" + MIN_PASSWORD_LENGTH + "位。");
        }
        if (UserAccountDAO.isUsernameExists(name)) {
            return Result.failure("角色名【" + name + "】已被占用，请换一个。");
        }

        String normalizedEmail = null;
        String trimmedCode = code == null ? null : code.trim();
        if (email != null && !email.isBlank()) {
            normalizedEmail = email.trim().toLowerCase();
            if (!EmailRules.isValidEmail(normalizedEmail)) {
                return Result.failure("邮箱格式不正确。");
            }
            if (!EmailRules.isAllowedEmailDomain(normalizedEmail)) {
                return Result.failure("不支持的邮箱类型。");
            }
            if (USER_SERVICE_DAO.existsByEmail(normalizedEmail)) {
                return Result.failure("该邮箱已被注册。");
            }
            if (trimmedCode == null || trimmedCode.isEmpty()) {
                return Result.failure("验证码不能为空。");
            }
            if (!new VerificationCodeService().verifyCode(normalizedEmail, trimmedCode)) {
                return Result.failure("验证码错误或已过期。");
            }
        }

        String hashed = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
        return create(services, name, hashed, normalizedEmail, false, hook);
    }

    /**
     * 无密码注册：私聊与网页<b>都不可用</b>时的兜底。
     *
     * <p><b>为什么允许</b>：新申请的 QQ 官方机器人常常没有单聊权限，管理员也可能还没配置
     * {@code server.public-url}。两个条件同时成立时，玩家在群里发 {@code /注册} 会彻底卡死 ——
     * 既收不到私聊提示，也没有网页可以输入密码。相比"注册失败"，先建号让玩家能玩更合理。</p>
     *
     * <p><b>为什么不是空密码</b>：空密码意味着任何人都能拿「用户名 + 空密码」登录网页，
     * 或通过聊天通道 {@code /绑定} 抢走这个账号。这里给账号一个 128 位的随机密码，
     * 明文<b>直接丢弃</b>：账号只能通过已绑定的 QQ 身份使用；需要网页登录时，
     * 由管理员在后台「重置密码」设置一个玩家知道的新密码。</p>
     *
     * @param services 服务查询面
     * @param username 角色名
     * @param hook     绑定钩子
     */
    public static Result registerWithoutPassword(ServiceLookup services, String username, BindHook hook) {
        if (username == null || username.trim().isEmpty()) {
            return Result.failure("角色名不能为空。");
        }
        String name = username.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            return Result.failure("角色名不能超过" + MAX_NAME_LENGTH + "个字。");
        }
        if (UserAccountDAO.isUsernameExists(name)) {
            return Result.failure("角色名【" + name + "】已被占用，请换一个。");
        }
        // 随机密码：不可猜、不展示、丢弃明文。账号的可用性来自 QQ 身份绑定。
        String randomPassword = java.util.UUID.randomUUID().toString()
                + java.util.UUID.randomUUID();
        return create(services, name, BCrypt.hashpw(randomPassword, BCrypt.gensalt()), null, true, hook);
    }

    /** 建号 + 绑身份 + 建角色（注册的两条路径共用；任何一步失败都回滚）。 */
    private static Result create(ServiceLookup services, String name, String passwordHash,
                                 String email, boolean passwordPlaceholder, BindHook hook) {
        Long userId = UserAccountDAO.insertUser(name, passwordHash, email);
        if (userId == null) {
            return Result.failure("注册失败，请稍后重试。");
        }

        try {
            if (passwordPlaceholder) {
                // 标记"还没设过密码"：/改密 见到它跳过原密码校验
                USER_SERVICE_DAO.setPasswordPlaceholder(userId, true);
            }
            PermissionService.assignDefaultRole(userId);
            hook.bind(userId);

            PlayerInfo player = services.getPlayerService().createPlayer(userId, name);
            if (player == null) {
                throw new IllegalStateException("角色创建失败");
            }
            return Result.success(userId);
        } catch (RuntimeException e) {
            rollback(userId, hook);
            return Result.failure(e.getMessage() == null ? "注册失败，请稍后重试。" : e.getMessage());
        }
    }

    /** 撤销半截账号：解绑 + 删 users 行，两步都尽力而为。 */
    private static void rollback(long userId, BindHook hook) {
        try {
            hook.unbind(userId);
        } catch (RuntimeException ignored) {
            // 可能压根没绑上：这里失败不影响删除 users 行
        }
        try {
            UserAccountDAO.deleteUser(userId);
        } catch (RuntimeException ignored) {
            // 删不掉只能留给运维：至少绑定已经撤销，不会占着别人的 openid
        }
    }
}
