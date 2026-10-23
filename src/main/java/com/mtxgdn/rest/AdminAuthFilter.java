package com.mtxgdn.rest;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.mindrot.jbcrypt.BCrypt;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AdminAuthFilter implements ContainerRequestFilter {

    /**
     * 用 GameLogger 而不是 System.out/err。
     *
     * <p>这里记录的是<b>管理员鉴权</b>相关事件：密钥来源、明文口令升级、
     * 连续登录失败锁定、bcrypt 校验失败、数据库校验失败。原来这些全部走
     * {@code System.out/err} —— 只到控制台、<b>不进日志文件</b>，
     * 事后排查"谁被锁了/为什么登录不上"时根本找不到任何痕迹。</p>
     */
    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(AdminAuthFilter.class);

    /** 管理员密钥：优先 admin.jwt_secret，其次 jwt.secret；均为空则随机生成（重启后 admin token 失效）。 */
    public static final SecretKey ADMIN_KEY = initAdminKey();
    /** 登录失败锁定：连续失败 5 次锁定 15 分钟 */
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000L;
    private static final ConcurrentHashMap<String, long[]> LOGIN_FAILURES = new ConcurrentHashMap<>(); // key=username -> {failCount, lockUntilMs}
    private static final long ADMIN_TOKEN_EXPIRY = 12 * 60 * 60 * 1000L;

    /**
     * 管理员 JWT 签名密钥。
     *
     * <p><b>V6 修复</b>：原实现在 {@code admin.jwt_secret} 未配置时直接回退到
     * 玩家密钥 {@code jwt.secret}。这会造成两个密钥域混同：任何能拿到玩家签名密钥
     * 的一方（或读到配置文件的人）都可以自签一个 {@code role=admin} 的 token 提权，
     * 玩家 token 也能被管理端解析成功（下方 role 校验分支正是为此打的补丁）。</p>
     *
     * <p>现在改为：</p>
     * <ol>
     *   <li>优先使用独立配置的 {@code admin.jwt_secret}（推荐，且要求 ≥32 字节）；</li>
     *   <li>未配置时，<b>从玩家密钥做域分离派生</b>（HMAC-SHA256(玩家密钥, 固定标签)），
     *       得到的密钥与玩家密钥不同，但仍是<b>确定性的</b> —— 重启后管理 token 依然有效，
     *       不需要重新登录，也避免了旧的"随机生成→重启失效"问题；</li>
     *   <li>玩家密钥也为空时（安装向导之前）才退化为随机密钥。</li>
     * </ol>
     */
    private static SecretKey initAdminKey() {
        String configured = AppConfig.get("admin.jwt_secret", "");
        String playerSecret = AppConfig.get("jwt.secret", "");

        // 1) 显式配置了独立的管理员密钥：直接使用
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                LOG.warn("admin.jwt_secret 长度不足 32 字节（至少 256 位），已改为按域分离方式派生。");
            } else {
                LOG.info("✓ 管理员 JWT 密钥已从 admin.jwt_secret 加载（与玩家密钥隔离）");
                return Keys.hmacShaKeyFor(bytes);
            }
        }

        // 2) 未配置独立密钥：用玩家密钥派生，保证与玩家密钥不同但重启稳定
        if (playerSecret != null && !playerSecret.isBlank()) {
            byte[] derived = deriveAdminKey(playerSecret.getBytes(StandardCharsets.UTF_8));
            if (derived.length < 32) {
                // 派生结果理论上是 32 字节 SHA-256，这里仅作防御性兜底
                byte[] padded = new byte[32];
                System.arraycopy(derived, 0, padded, 0, Math.min(derived.length, 32));
                derived = padded;
            }
            LOG.info("未配置 admin.jwt_secret，已从 jwt.secret 按域分离派生管理员密钥"
                    + "（与玩家 token 密钥不同）。建议显式配置 admin.jwt_secret。");
            return Keys.hmacShaKeyFor(derived);
        }

        // 3) 玩家密钥也没有（安装向导尚未完成）：随机密钥，重启后管理 token 失效
        byte[] randomBytes = new byte[64];
        new SecureRandom().nextBytes(randomBytes);
        LOG.warn("既无 admin.jwt_secret 也无 jwt.secret，已生成随机管理员密钥（重启后管理 Token 失效）。");
        return Keys.hmacShaKeyFor(randomBytes);
    }

    /**
     * 域分离派生：adminKey = HMAC-SHA256(key = 玩家密钥, message = 固定标签)。
     *
     * <p>单向派生保证了「拿到 adminKey 也反推不出玩家密钥」，同时 adminKey
     * 与玩家密钥在密码学上无关联，玩家 token 无法通过管理端校验。</p>
     */
    private static byte[] deriveAdminKey(byte[] playerKeyBytes) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(playerKeyBytes, "HmacSHA256"));
            return mac.doFinal(ADMIN_KEY_DERIVATION_LABEL);
        } catch (Exception e) {
            throw new IllegalStateException("[AdminAuth] 派生管理员密钥失败", e);
        }
    }

    /** 域分离标签：修改此值会使所有既有管理 token 失效。 */
    private static final byte[] ADMIN_KEY_DERIVATION_LABEL =
            "xiuxian-admin-jwt-v1".getBytes(StandardCharsets.UTF_8);

    private static final String ADMIN_USERNAME = initAdminUsername();
    /**
     * S3 修复：admin 密码统一以 bcrypt 哈希存储（admin.password_hash）。若用户仍在使用
     * 旧的明文 admin.password，则自动升级为 bcrypt 并写回配置（一次性）。
     */
    private static final String ADMIN_PASSWORD_HASH = initAdminPasswordHash();

    private static String initAdminUsername() {
        String username = AppConfig.get("admin.username");
        if (username != null && !username.isEmpty()) {
            return username;
        }
        LOG.info("未配置 admin.username，管理后台将使用数据库用户认证。");
        return "";
    }

    private static String initAdminPasswordHash() {
        String hash = AppConfig.get("admin.password_hash");
        if (hash != null && !hash.isEmpty()) {
            return hash;
        }
        // 兼容旧的明文 admin.password：若存在则视为首次升级，自动哈希后写回配置并清除明文项
        String plain = AppConfig.get("admin.password");
        if (plain != null && !plain.isEmpty()) {
            String generated = BCrypt.hashpw(plain, BCrypt.gensalt(10));
            try {
                // 一次性写回：写入 bcrypt 哈希，并用 null 删除旧明文键（保留文件其余注释与排版）
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("admin.password_hash", generated);
                map.put("admin.password", null);
                com.mtxgdn.util.AppConfig.update(map);
                LOG.warn("已将 admin.password (明文) 升级为 admin.password_hash (bcrypt)，请删除旧配置项");
            } catch (Exception e) {
                LOG.error("升级 admin.password 失败: " + e.getMessage());
            }
            return generated;
        }
        LOG.info("未配置 admin.password_hash / admin.password，管理后台将使用数据库用户认证。");
        return "";
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();

        if (!path.startsWith("admin/")) {
            return;
        }

        String method = ctx.getMethod();
        // 每个管理请求一行：DEBUG 级别，默认 INFO 下不输出（避免刷屏且仍可开启）
        LOG.debug(method + " " + path);
        if (path.equals("admin/login") && "POST".equalsIgnoreCase(method)) {
            return;
        }

        boolean isDualAuthPath = path.startsWith("admin/energy/");

        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (isDualAuthPath) {
                return;
            }
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"请先登录管理后台\"}")
                    .build());
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(ADMIN_KEY)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String role = claims.get("role", String.class);
            if (role == null || (!"admin".equals(role) && !"super_admin".equals(role) && !"moderator".equals(role))) {
                // V6 之后 admin 密钥与玩家密钥已完全隔离，能解析成功却无 role 的
                // 只可能是伪造/畸形 token。仍保留玩家自我端点放行以兼容旧客户端
                // （该分支会走 JwtUtil 独立校验玩家 token，不授予任何 admin 权限）。
                if (tryAcceptPlayerSelf(ctx, path, token)) {
                    return;
                }
                ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"code\":403,\"message\":\"无管理权限\"}")
                        .build());
                return;
            }
            ctx.setProperty("adminAuthenticated", true);
            ctx.setProperty("adminUserId", claims.get("userId", Long.class));
            ctx.setProperty("adminRole", role);
        } catch (Exception e) {
            if (isDualAuthPath) {
                return;
            }
            // 玩家中心：允许玩家 token（游戏 JWT，密钥不同时解析失败）读取自己的信息（/me、/whoami）。
            if (tryAcceptPlayerSelf(ctx, path, token)) {
                return;
            }
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"code\":401,\"message\":\"管理Token无效或已过期\"}")
                    .build());
        }
    }

    /**
     * 玩家中心自我服务端点放行：仅当请求路径是玩家自我端点（admin/me、admin/whoami）
     * 且 token 为合法游戏 JWT 时，以玩家身份放行（不授予任何 admin 权限）。
     *
     * @return 是否已按玩家身份放行
     */
    private static boolean tryAcceptPlayerSelf(ContainerRequestContext ctx, String path, String token) {
        boolean playerSelfPath = "admin/me".equals(path) || "admin/whoami".equals(path);
        if (!playerSelfPath) {
            return false;
        }
        if (!JwtUtil.validateToken(token)) {
            return false;
        }
        Long playerUserId = JwtUtil.extractUserId(token);
        ctx.setProperty("userId", playerUserId);
        ctx.setProperty("playerAuthenticated", true);
        ctx.setProperty("adminAuthenticated", false);
        return true;
    }

    public static String generateAdminToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + ADMIN_TOKEN_EXPIRY);

        return Jwts.builder()
                .setSubject("admin")
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", role.toLowerCase())
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(ADMIN_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static boolean validateCredentials(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long[] record = LOGIN_FAILURES.get(username);
        if (record != null && record[1] > now) {
            LOG.warn("账号 " + username + " 因多次登录失败已锁定，拒绝登录");
            return false;
        }
        boolean ok;
        // S3 修复：admin 凭据统一走 bcrypt；明文比较仅作为兼容路径
        if (!ADMIN_USERNAME.isEmpty() && !ADMIN_PASSWORD_HASH.isEmpty()) {
            if (!ADMIN_USERNAME.equals(username)) {
                ok = false;
            } else {
                // ADMIN_USERNAME 通过后用 bcrypt 校验密码
                try {
                    ok = BCrypt.checkpw(password, ADMIN_PASSWORD_HASH);
                } catch (Exception e) {
                    LOG.error("bcrypt 校验失败: " + e.getMessage());
                    ok = false;
                }
            }
        } else {
            ok = validateDbCredentials(username, password);
        }
        if (ok) {
            LOGIN_FAILURES.remove(username);
        } else {
            recordLoginFailure(username);
        }
        return ok;
    }

    /** 记录一次登录失败；连续失败达到阈值则锁定 15 分钟。 */
    private static void recordLoginFailure(String username) {
        long now = System.currentTimeMillis();
        LOGIN_FAILURES.compute(username, (k, old) -> {
            long[] rec = old;
            if (rec == null || rec[1] > now) {
                rec = new long[]{0, 0};
            }
            rec[0]++;
            if (rec[0] >= MAX_LOGIN_FAILURES) {
                rec[1] = now + LOCK_DURATION_MS;
                rec[0] = 0;
                LOG.warn("账号 " + username + " 连续登录失败 " + MAX_LOGIN_FAILURES + " 次，已锁定 15 分钟");
            }
            return rec;
        });
    }

    /** 登录成功后清除失败记录（供登录端点调用）。 */
    public static void resetLoginFailures(String username) {
        if (username != null) {
            LOGIN_FAILURES.remove(username);
        }
    }

    private static boolean validateDbCredentials(String username, String password) {
        String sql = "SELECT id, password FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hashedPassword = rs.getString("password");
                    if (BCrypt.checkpw(password, hashedPassword)) {
                        long userId = rs.getLong("id");
                        return hasAdminLoginPermission(userId);
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error("数据库验证失败: " + e.getMessage());
        }
        return false;
    }

    private static boolean hasAdminLoginPermission(long userId) {
        if (PermissionService.hasPermission(userId, PermissionCode.ADMIN_LOGIN.getCode())) {
            return true;
        }
        List<String> roles = PermissionService.getUserRoles(userId);
        for (String role : roles) {
            if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role) || "MODERATOR".equals(role)) {
                return true;
            }
        }
        return false;
    }

    public static String getAdminRole(Long userId) {
        if (!ADMIN_USERNAME.isEmpty()) {
            return "super_admin";
        }
        String highestRole = PermissionService.getHighestRole(userId);
        if (highestRole == null) {
            if (PermissionService.hasPermission(userId, PermissionCode.ADMIN_LOGIN.getCode())) {
                return "admin";
            }
            return "player";
        }
        return highestRole.toLowerCase();
    }

    public static Long getUserIdByUsername(String username) {
        String sql = "SELECT id FROM users WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            LOG.error("查询用户ID失败: " + e.getMessage());
        }
        return null;
    }
}
