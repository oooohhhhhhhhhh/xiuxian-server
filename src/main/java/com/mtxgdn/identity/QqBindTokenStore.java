package com.mtxgdn.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QQ 官方机器人的「一次性绑定票据」。
 *
 * <p><b>要解决什么问题</b>：新申请的 QQ 官方机器人往往没有单聊（C2C）权限，
 * {@code /绑定}、{@code /注册} 里「请输入用户名 / 密码」这类多轮对话根本发不出去 ——
 * 消息发出去平台就拒收，玩家永远等不到下一步。于是流程改成：在群里回一条链接，
 * 玩家在浏览器里完成密码（或邮箱验证码）输入，然后把这台 QQ 身份绑定到账号上。</p>
 *
 * <p>链接里带的 {@code qq_bind=<token>} 就是本类签发的票据。它必须满足三条：</p>
 * <ol>
 *   <li><b>单次</b> —— {@link #consume} 先 {@code remove} 再校验。先校验后删除会留下
 *       "两个并发请求都能通过"的窗口，那个窗口正好够抢占一次绑定；</li>
 *   <li><b>短暂</b> —— {@link #TTL_MS} 10 分钟。玩家通常几十秒内就点了，
 *       给 10 分钟足够；票据在浏览器地址栏、聊天记录、代理日志里都会留痕，
 *       活得越久越危险；</li>
 *   <li><b>不存原文</b> —— 内存里只留 SHA-256。票据等同于"半张凭据"，
 *       进程被 dump 时不该把它一起泄出去。</li>
 * </ol>
 *
 * <p><b>为什么放在内存</b>：它比密码更短命，且签发方（QQ 适配器）与消费方
 * （REST 端点）在同一个进程里。重启会让未使用的链接失效 —— 玩家重发
 * {@code /绑定} 拿一条新的即可，这个代价远小于为它加一张表、一套清理任务。</p>
 *
 * <p><b>失败计数</b>：同一条票据允许输错密码（人总会打错），但累计
 * {@link #MAX_FAILURES} 次就作废，避免有人拿着一条泄漏的链接慢慢试。
 * 密码本身的撞库防护在 {@link CredentialAuthService}（按用户名限流 + 账号锁定），
 * 两层互不替代。</p>
 */
public final class QqBindTokenStore {

    /** 票据有效期：10 分钟。 */
    public static final long TTL_MS = 10 * 60 * 1000L;

    /** 同一条票据允许的失败次数，达到后作废。 */
    public static final int MAX_FAILURES = 5;

    /** 票据用途。 */
    public enum Intent {
        /** 绑定已有账号 */
        BIND,
        /** 注册新角色并绑定（角色名已在聊天里给出，这里只补密码/邮箱） */
        REGISTER
    }

    /**
     * 一条待消费的票据。
     *
     * <p>{@code pendingUsername} 只在 {@link Intent#REGISTER} 时有值：角色名在群里
     * 由玩家当面输入（不是秘密），随票据一起带过来，网页上那一栏就是只读的。</p>
     */
    public static final class Pending {
        private final String openid;
        private final String appId;
        private final Intent intent;
        private final String pendingUsername;
        private final long expiresAt;
        private final AtomicInteger failures = new AtomicInteger(0);

        Pending(String openid, String appId, Intent intent, String pendingUsername, long expiresAt) {
            this.openid = openid;
            this.appId = appId;
            this.intent = intent;
            this.pendingUsername = pendingUsername;
            this.expiresAt = expiresAt;
        }

        public String openid() {
            return openid;
        }

        public String appId() {
            return appId;
        }

        public Intent intent() {
            return intent;
        }

        /** 注册用途的角色名；绑定用途为 null。 */
        public String pendingUsername() {
            return pendingUsername;
        }

        public long expiresAt() {
            return expiresAt;
        }

        /** 已失败次数（测试与提示文案用）。 */
        public int failures() {
            return failures.get();
        }

        boolean expired(long now) {
            return now > expiresAt;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** token 的 SHA-256 → 票据。key 是哈希，原文只存在于链接与玩家的浏览器里。 */
    private static final Map<String, Pending> TICKETS = new ConcurrentHashMap<>();

    private QqBindTokenStore() {
    }

    /**
     * 签发一条票据。
     *
     * @param openid          QQ 官方身份（平台内唯一，按 AppID 隔离）
     * @param appId           机器人 AppID（绑定写库时要带，见 {@link QqOfficialBindingService}）
     * @param intent          用途
     * @param pendingUsername 注册用途的角色名；绑定用途传 null
     * @return 票据原文（只在这一次返回）
     */
    public static String issue(String openid, String appId, Intent intent, String pendingUsername) {
        if (openid == null || openid.isBlank()) {
            throw new IllegalArgumentException("openid 不能为空");
        }
        purgeExpired();
        String token = randomToken();
        TICKETS.put(sha256(token), new Pending(openid, appId == null ? "" : appId.trim(),
                intent, pendingUsername, System.currentTimeMillis() + TTL_MS));
        return token;
    }

    /**
     * 查看票据但<strong>不消费</strong>（网页首屏渲染用：要知道是绑定还是注册、角色名是什么）。
     *
     * @return 不存在、已过期、失败次数已满时为空
     */
    public static Optional<Pending> peek(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Pending pending = TICKETS.get(sha256(token));
        if (pending == null || pending.expired(System.currentTimeMillis())
                || pending.failures() >= MAX_FAILURES) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /**
     * 消费票据（成功后调用）。
     *
     * <p>先移除再校验：保证"一条票据只能完成一次绑定"，即使并发请求同时到达，
     * 也只有一个能拿到 {@link Pending}。</p>
     *
     * @return 不存在 / 已过期 / 失效时为空
     */
    public static Optional<Pending> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Pending pending = TICKETS.remove(sha256(token));
        if (pending == null || pending.expired(System.currentTimeMillis())
                || pending.failures() >= MAX_FAILURES) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    /**
     * 记一次失败（密码错、验证码错、账号已绑定等）。
     *
     * @return 剩余可用次数；票据不存在时返回 0
     */
    public static int recordFailure(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        String hash = sha256(token);
        Pending pending = TICKETS.get(hash);
        if (pending == null) {
            return 0;
        }
        int used = pending.failures.incrementAndGet();
        if (used >= MAX_FAILURES) {
            TICKETS.remove(hash);
            return 0;
        }
        return MAX_FAILURES - used;
    }

    /** 当前待消费票据数量（监控 / 测试）。 */
    public static int size() {
        purgeExpired();
        return TICKETS.size();
    }

    /** 清空（测试用，避免用例之间互相影响）。 */
    public static void clear() {
        TICKETS.clear();
    }

    /** 清理过期条目，避免长跑积累。 */
    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        TICKETS.entrySet().removeIf(e -> e.getValue().expired(now)
                || e.getValue().failures() >= MAX_FAILURES);
    }

    /** 32 字节随机数，URL 安全 Base64（不含 {@code + / =}，可直接进查询串）。 */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256，无法安全处理绑定票据", e);
        }
    }
}
