package com.mtxgdn.util;

import com.mtxgdn.db.TokenBlacklistDAO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 吊销名单。
 *
 * <p><b>V4 修复</b>：原实现只把 token 放在内存 {@code ConcurrentHashMap} 里，
 * 进程重启后名单清空 —— 玩家"登出"过的 token（有效期 7 天）在服务器重启后会
 * 全部复活，攻击者只要持有旧 token 就能一直用到自然过期。</p>
 *
 * <p>现在采用 <b>内存缓存 + 数据库持久化</b> 双层结构：</p>
 * <ul>
 *   <li>{@link #invalidate(String)} 同时写内存与 DB（DB 写失败不回滚内存，
 *       保证当次进程内吊销立即生效，并在日志中告警）；</li>
 *   <li>{@link #isBlacklisted(String)} 先查内存命中则短路，未命中再查 DB，
 *       查到后回填内存缓存（避免每次鉴权都打库）；</li>
 *   <li>{@link #loadFromDatabase()} 在服务启动时调用，把历史吊销记录载入内存。</li>
 * </ul>
 *
 * <p>隐私与容量考虑：DB 中只存 token 的 <b>SHA-256 摘要</b>而非明文 token，
 * 数据库泄露无法直接拿去冒用；{@code expires_at} 记录 token 自然过期时间，
 * {@link #cleanupExpired(long)} 据此清理，表中不会有无限增长的历史数据。</p>
 *
 * <p><b>SQL 已抽到 {@link TokenBlacklistDAO}</b>：本类只保留哈希、
 * 过期时间解析与"内存优先、DB 兜底"的判定策略 —— 那才是吊销语义本身。
 * 登出与令牌吊销的行为不能因为这次抽取而变化，尤其是"DB 挂了也要能登出"
 * 这条降级路径。</p>
 */
public class TokenBlacklist {

    /** 内存缓存：token 摘要 -> token 自然过期时间（epoch ms）。 */
    private static final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    /** 未取到 exp 时的兜底保留时长（应大于等于 JWT 有效期）。 */
    private static final long DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    /** {@code token_blacklist} 表的读写入口。 */
    private static final TokenBlacklistDAO DAO = new TokenBlacklistDAO();

    private TokenBlacklist() {
    }

    /** token 摘要（SHA-256 hex）。落库与缓存统一使用摘要，不保存明文。 */
    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，理论上不可达；退化为明文 hash 保证功能不中断
            return Integer.toHexString(token.hashCode());
        }
    }

    /**
     * 吊销一个 token。
     *
     * <p>优先从 JWT 自身解析真实过期时间（这样清理时机精确）；
     * 解析失败（如 token 已损坏）则按 {@link #DEFAULT_RETENTION_MS} 兜底保留。</p>
     */
    public static void invalidate(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        long expiresAt = resolveExpiry(token);
        String tokenHash = hash(token);

        // 先写内存：保证本进程内立即生效，不依赖 DB 可用性
        blacklist.put(tokenHash, expiresAt);

        // 再落库：保证重启后依然失效
        persist(tokenHash, expiresAt);
    }

    /**
     * 从 token 解析真实过期时间；无法解析时返回"现在 + 兜底时长"。
     */
    private static long resolveExpiry(String token) {
        try {
            java.util.Date exp = com.mtxgdn.util.JwtUtil.extractExpiration(token);
            if (exp != null) {
                return exp.getTime();
            }
        } catch (Throwable ignored) {
            // token 非法/无 exp：走兜底
        }
        return System.currentTimeMillis() + DEFAULT_RETENTION_MS;
    }

    private static void persist(String tokenHash, long expiresAt) {
        // DB 不可用不应阻断登出：内存已生效，仅告警（重启后该 token 会复活，
        // 但这是极端降级场景，不能因为落库失败就让玩家无法登出）。
        // 降级与告警都在 DAO 里，这里只保留"先内存后落库"的顺序。
        DAO.insert(tokenHash, expiresAt);
    }

    /**
     * 判断 token 是否已被吊销。内存未命中时查库并回填缓存。
     */
    public static boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String tokenHash = hash(token);

        Long expiresAt = blacklist.get(tokenHash);
        if (expiresAt != null) {
            if (expiresAt < System.currentTimeMillis()) {
                // 已自然过期，顺手清理，避免缓存膨胀
                blacklist.remove(tokenHash);
                return false;
            }
            return true;
        }

        // 内存未命中：查库（可能是重启前吊销的）
        Long dbExpiry = queryFromDatabase(tokenHash);
        if (dbExpiry != null) {
            if (dbExpiry < System.currentTimeMillis()) {
                return false;
            }
            blacklist.put(tokenHash, dbExpiry); // 回填缓存
            return true;
        }
        return false;
    }

    private static Long queryFromDatabase(String tokenHash) {
        // 查询失败时保守放行（不误伤正常用户），仅告警 —— 降级在 DAO 里
        return DAO.findExpiresAt(tokenHash);
    }

    /**
     * 服务启动时把 DB 中的吊销记录载入内存缓存。
     * 建议在 {@code DatabaseManager.init()} 之后调用。
     *
     * @return 载入的条目数
     */
    public static int loadFromDatabase() {
        long now = System.currentTimeMillis();
        List<TokenBlacklistDAO.BlacklistEntry> entries = DAO.findUnexpired(now);
        if (entries.isEmpty()) {
            return 0;
        }
        for (TokenBlacklistDAO.BlacklistEntry entry : entries) {
            blacklist.put(entry.tokenHash(), entry.expiresAt());
        }
        int loaded = entries.size();
        System.out.println("[TokenBlacklist] 已载入 " + loaded + " 条历史吊销记录");
        return loaded;
    }

    /**
     * 清理已自然过期的吊销记录（内存 + DB）。
     *
     * @param maxAgeMs 兼容旧签名：保留早于该时长的记录会被清理
     */
    public static void cleanupExpired(long maxAgeMs) {
        long now = System.currentTimeMillis();
        blacklist.entrySet().removeIf(entry -> entry.getValue() < now);
        // DB 中按 expires_at 精确清理；清理失败静默（不影响主流程）
        DAO.deleteExpiredBefore(now);
    }

    /** 清空内存缓存（不删库，测试与调试用）。 */
    public static void clear() {
        blacklist.clear();
    }

    /** 清空内存与数据库（测试用）。 */
    public static void clearAll() {
        blacklist.clear();
        DAO.deleteAll();
    }

    public static int size() {
        return blacklist.size();
    }
}
