package com.mtxgdn.identity;

import com.mtxgdn.db.UserServiceDAO;
import com.mtxgdn.entity.User;
import com.mtxgdn.util.RateLimiter;
import org.mindrot.jbcrypt.BCrypt;

import java.sql.Timestamp;

/**
 * 用户名 / 密码校验的<strong>唯一实现</strong>。
 *
 * <p>它原本长在 QQ 官方适配器的账号流程里（私聊里「请输入密码」那一轮）。现在
 * 「网页绑定」也要校验同一个密码 —— 如果两边各写一遍，迟早会出现
 * "网页端把账号锁了、聊天端不知道"或"一边限流 5 次、另一边不限"这种分裂。
 * 所以策略（限流、锁定判定、失败计数、BCrypt 比对）集中到这里，
 * 两个入口只是传不同的限流前缀。</p>
 *
 * <h3>两条不可省的防护</h3>
 * <ol>
 *   <li><b>按用户名限流</b>：同一用户名 60 秒内最多 5 次。前缀由调用方给
 *       （聊天 {@code qq_official_login_}、网页 {@code qq_web_login_}），
 *       这样一条通道被刷时不会误伤另一条；</li>
 *   <li><b>账号锁定</b>：连续 5 次密码错误锁 15 分钟（{@code users.failed_attempts} /
 *       {@code users.locked_until}，两条通道共用同一份计数 —— 这正是为了
 *       "换个入口继续试"这个绕过方式）。</li>
 * </ol>
 *
 * <p><b>失败原因显式返回</b>：限流与锁定都必须能和"密码错了"区分开。
 * 历史上这里一律返回 null，上层就一律显示「用户名或密码错误」，
 * 玩家拿着正确密码也会反复看到"密码错误"，完全不知道自己在被限流。</p>
 *
 * <p><b>SQL 不在这里</b>：{@code users} 表归 {@link UserServiceDAO}，
 * 本类只做认证策略。QQ 包内 {@code DatabaseManager.getConnection()} 的调用点
 * 必须保持为 0（{@code QqOfficialAdapterSplitTest} 钉着这一点）。</p>
 */
public final class CredentialAuthService {

    /** 同一用户名每分钟允许的尝试次数。 */
    static final int ATTEMPTS_PER_MINUTE = 5;

    /** 连续错误多少次触发锁定。 */
    static final int MAX_FAILED_ATTEMPTS = 5;

    /** 锁定时长（分钟）。 */
    static final long LOCK_MINUTES = 15;

    private static final UserServiceDAO USER_DAO = new UserServiceDAO();

    private CredentialAuthService() {
    }

    /**
     * 校验结果。
     *
     * <p>{@code error} 是<b>面向玩家</b>的具体原因（限流 / 锁定 / 密码错），
     * 聊天通道会原样回给玩家（他正卡在这一步，需要知道自己在被限流）。
     * 网页通道则统一换成泛化文案 —— 那条通道对任何人都开放，具体原因
     * 会变成"这个用户名存不存在"的探针。</p>
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
     * 校验用户名与密码。
     *
     * @param username        用户名（不允许为空）
     * @param password        明文密码
     * @param rateLimitPrefix 限流 key 前缀，须以 {@code _} 结尾，例如 {@code "qq_official_login_"}
     */
    public static Result check(String username, String password, String rateLimitPrefix) {
        if (username == null || username.isEmpty()) {
            return Result.failure("用户名不能为空。");
        }
        String rateLimitKey = rateLimitPrefix + username.toLowerCase();
        if (!RateLimiter.allow(rateLimitKey, ATTEMPTS_PER_MINUTE, 60)) {
            return Result.failure("尝试过于频繁，请 1 分钟后再试。");
        }

        User user;
        try {
            user = USER_DAO.findByUsernameWithLockInfo(username);
        } catch (RuntimeException e) {
            return Result.failure("用户名或密码错误。");
        }

        if (user != null) {
            Timestamp lockedUntil = user.getLockedUntil();
            long now = System.currentTimeMillis();
            if (lockedUntil != null && lockedUntil.getTime() > now) {
                long minutes = Math.max(1, (lockedUntil.getTime() - now + 59_999) / 60_000);
                return Result.failure("该账号因多次输错密码已被临时锁定，请约 " + minutes
                        + " 分钟后再试（不是密码错误）。");
            }

            if (password != null && BCrypt.checkpw(password, user.getPassword())) {
                try {
                    USER_DAO.clearFailedAttempts(user.getId());
                } catch (RuntimeException e) {
                    // 计数清零失败不该让一次成功的登录变成失败：密码已经验对了
                }
                return Result.success(user.getId());
            }

            incrementFailedAttempts(user.getId());
        }
        return Result.failure("用户名或密码错误。");
    }

    /**
     * 记录一次密码错误。
     *
     * <p>计数在 SQL 里做加法（{@code failed_attempts = failed_attempts + 1}）：
     * 读内存值再写绝对值，会在两条通道同时输错密码时丢一次计数。</p>
     */
    private static void incrementFailedAttempts(long userId) {
        Timestamp lockedUntil = new Timestamp(System.currentTimeMillis() + LOCK_MINUTES * 60 * 1000);
        try {
            USER_DAO.recordFailedAttempt(userId, MAX_FAILED_ATTEMPTS, lockedUntil);
        } catch (RuntimeException e) {
            // 计数失败不影响本次"密码错误"的结论
        }
    }
}
