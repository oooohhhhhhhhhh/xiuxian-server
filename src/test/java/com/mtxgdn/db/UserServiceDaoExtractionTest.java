package com.mtxgdn.db;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.GameErrorCode;
import com.mtxgdn.entity.User;
import com.mtxgdn.service.UserService;
import com.mtxgdn.service.VerificationCodeService;
import com.mtxgdn.util.AppConfig;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护「{@code UserService} 里的裸 JDBC 抽到 {@link UserServiceDAO}」这次纯结构重构。
 *
 * <p><b>缺陷形态</b>：{@code UserService} 一个类里 488 行、13 处
 * {@code DatabaseManager.getConnection()}，注册校验、BCrypt 哈希/比对、JWT 签发、
 * 登录失败计数与锁定、改密、注销，全都和 {@code PreparedStatement} /
 * {@code ResultSet} / {@code SQLException} 逐行交织。它是<b>安全代码</b>，
 * 所以混在一起格外糟：</p>
 * <ol>
 *   <li>改一条 SQL 与改一条判定（查重顺序、锁定阈值、错误码）会出现在同一份 diff 里，
 *       review 时无法区分"只是搬了语句"与"放宽了判定"；</li>
 *   <li>"哪条语句在事务里"看不出来：{@code changePassword} / {@code resetPasswordByEmail}
 *       都是"先读后写"，读与写共用一条短连接，但<b>没有</b>事务；</li>
 *   <li>SQLite 上最致命的一条：事务里若用<b>另一个连接</b>读写同一张表，
 *       会抛 {@code SQLITE_BUSY_SNAPSHOT}（不受 {@code busy_timeout} 约束）
 *       或读到旧快照。所以 DAO 的每个方法都必须有"收调用方连接"的重载。</li>
 * </ol>
 *
 * <p>本测试钉住六类不变量：</p>
 * <ol>
 *   <li><b>公开 API 逐字不变</b>：{@code javap -public} 黄金清单；</li>
 *   <li><b>服务层不再有 JDBC</b>：{@code UserService} 源码里
 *       {@code DatabaseManager} / {@code getConnection(} / {@code PreparedStatement} /
 *       {@code ResultSet} / {@code setAutoCommit(} / SQL 关键字一处不剩，
 *       而认证语义（BCrypt、JWT、查重顺序、锁定阈值、错误码）必须还在服务层；</li>
 *   <li><b>SQL 守恒</b>：原 {@code UserService} 里的每一条 SQL 都还在
 *       {@link UserServiceDAO} 里（去空白后比对，只允许"搬走"，不允许"改掉/丢掉"），
 *       并且 DAO 里不许出现清单之外的、看着像 SQL 的新字面量；</li>
 *   <li><b>连接与事务</b>：每个 DAO 方法都有 {@code Connection} 首参重载，
 *       而<b>收连接的方法绝不自己开连接、也不自己开事务</b>；
 *       任何事务 lambda 体内也不许再开连接；</li>
 *   <li><b>认证语义</b>：注册查重、锁定阈值、重置解锁、改密校验经 DAO 仍然正确；</li>
 *   <li><b>真跑 SQLite</b>：注册后能按用户名/邮箱查回、重复用户名/邮箱被拒、
 *       改密码后新密码可校验、删用户后查不到、事务内用 {@code Connection} 重载
 *       不会抛 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ol>
 *
 * <p>源码级断言一律先剥掉注释（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class UserServiceDaoExtractionTest extends TestBase {

    private static final Path USER_SERVICE =
            Paths.get("src/main/java/com/mtxgdn/service/UserService.java");
    private static final Path USER_SERVICE_DAO =
            Paths.get("src/main/java/com/mtxgdn/db/UserServiceDAO.java");

    private static final UserServiceDAO DAO = new UserServiceDAO();

    private static final UserService SERVICE = new UserService();

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 注册时用的统一密码（满足"至少 8 位"）。 */
    private static final String PASSWORD = "Password1!";

    /** 匹配"方法声明"（行首访问修饰符 + 名字 + 左括号），用于按名字取方法体。 */
    private static final java.util.regex.Pattern METHOD_DECL =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:public|private|protected)(?:[^;{}\\n(]*?)\\b(\\w+)\\s*\\(");

    /** 匹配"第一个参数是 Connection 的方法声明"，形如 {@code public void addX(Connection conn, ...)}。 */
    private static final java.util.regex.Pattern CONNECTION_METHOD_DECL =
            java.util.regex.Pattern.compile("(?m)^[ \\t]*(?:public|private|protected)[^;{}\\n]*\\(Connection conn");

    /** 看着像一整条 SQL 的字符串字面量（用于"DAO 不许自己发明语句"的反向断言）。 */
    private static final java.util.regex.Pattern SQL_LOOKING_LITERAL =
            java.util.regex.Pattern.compile("(?s)^(SELECT|INSERT|UPDATE|DELETE|CREATE|INTO)\\b.*");

    /**
     * {@code javap -public -classpath target/classes com.mtxgdn.service.UserService}
     * 在<b>重构前</b>导出的公开成员清单（逐字抄录，与 {@link #describePublicApi(Class)}
     * 的输出格式一致）。
     *
     * <pre>
     * Compiled from "UserService.java"
     * public class com.mtxgdn.service.UserService {
     *   public com.mtxgdn.service.UserService();
     *   public jakarta.ws.rs.core.Response register(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
     *   public jakarta.ws.rs.core.Response login(java.lang.String, java.lang.String);
     *   public com.mtxgdn.entity.User authenticate(java.lang.String, java.lang.String);
     *   public void updateUserEmail(long, java.lang.String);
     *   public java.lang.String getUserEmail(long);
     *   public jakarta.ws.rs.core.Response changePassword(long, java.lang.String, java.lang.String);
     *   public boolean verifyPassword(long, java.lang.String);
     *   public jakarta.ws.rs.core.Response deleteUser(long);
     *   public jakarta.ws.rs.core.Response resetPasswordByEmail(java.lang.String, java.lang.String);
     * }
     * </pre>
     */
    private static final List<String> GOLDEN_SERVICE_API = List.of(
            "public com.mtxgdn.service.UserService()",
            "public jakarta.ws.rs.core.Response register(java.lang.String, java.lang.String, java.lang.String, java.lang.String)",
            "public jakarta.ws.rs.core.Response login(java.lang.String, java.lang.String)",
            "public com.mtxgdn.entity.User authenticate(java.lang.String, java.lang.String)",
            "public void updateUserEmail(long, java.lang.String)",
            "public java.lang.String getUserEmail(long)",
            "public jakarta.ws.rs.core.Response changePassword(long, java.lang.String, java.lang.String)",
            "public boolean verifyPassword(long, java.lang.String)",
            "public jakarta.ws.rs.core.Response deleteUser(long)",
            "public jakarta.ws.rs.core.Response resetPasswordByEmail(java.lang.String, java.lang.String)");

    /**
     * 原 {@code UserService.java} 里的全部 SQL（逐条抄录，去空白后比对）。
     *
     * <p>这批字符串是"重构前的真身"：只要有一条在 {@link UserServiceDAO} 里找不到，
     * 就说明搬运时改了字面量（表名/列名/条件/参数顺序），而那类改动不会让任何业务测试变红
     * —— 它只会在生产上悄悄换个结果集，或者让某个账号查不回来。</p>
     *
     * <p>{@code recordFailedAttempt} 那一条在原文件里是 5 段拼接（
     * {@code "UPDATE ... + 1, " + "locked_until = COALESCE(" + ...}），
     * 这里按拼接后的整句比对（去空白后两者等价）。</p>
     */
    private static final List<String> GOLDEN_SQL = List.of(
            // ---- 注册查重 ----
            "SELECT COUNT(*) FROM users WHERE username = ?",
            "SELECT COUNT(*) FROM users WHERE email = ?",
            // ---- 按用户名取账号 ----
            "SELECT id, username, password FROM users WHERE username = ?",
            "SELECT id, username, password, failed_attempts, locked_until FROM users WHERE username = ?",
            // ---- 插入账号 ----
            "INSERT INTO users (username, password, email) VALUES (?, ?, ?)",
            // ---- 登录失败计数 / 解锁 ----
            "UPDATE users SET failed_attempts = failed_attempts + 1, "
                    + "locked_until = COALESCE("
                    + "CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE NULL END, "
                    + "locked_until) "
                    + "WHERE id = ?",
            "UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?",
            // ---- 邮箱 ----
            "SELECT email FROM users WHERE id = ?",
            "UPDATE users SET email = NULL WHERE id = ? AND (email IS NULL OR email = '')",
            "UPDATE users SET email = ? WHERE id = ? AND (email IS NULL OR email = '' OR email = ?)",
            // ---- 改密 / 校验密码 / 邮箱重置密码 ----
            "SELECT id, password FROM users WHERE id = ?",
            "SELECT password FROM users WHERE id = ?",
            "UPDATE users SET password = ? WHERE id = ?",
            "SELECT id, username FROM users WHERE email = ?",
            "UPDATE users SET password = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?",
            // ---- 注销 ----
            "DELETE FROM users WHERE id = ?");

    @BeforeAll
    static void enableVerifyCode() {
        // 注册路径要跑通"邮箱 + 验证码"这条分支：默认值就是 true，
        // 这里显式钉住，避免别的用例把运行期覆盖留成 false。
        AppConfig.setRuntimeOverride("verify_code.enabled", "true");
    }

    @AfterAll
    static void restoreVerifyCode() {
        AppConfig.setRuntimeOverride("verify_code.enabled", null);
    }

    // ==================== 1. 公开 API 逐字不变 ====================

    @Test
    @DisplayName("API: UserService 的公开成员与重构前逐字一致（javap 黄金清单）")
    void userServiceKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(UserService.class);
        assertEquals(GOLDEN_SERVICE_API.stream().sorted().toList(), actual,
                "UserService 的公开 API 必须与重构前完全一致：多一个、少一个、"
                        + "改一个参数类型或返回类型都会让 REST / QQ / 插件 / 其它服务的调用方编译不过");
    }

    // ==================== 2. 服务层不再有 JDBC ====================

    /**
     * {@code DatabaseManager.getConnection()} 的白名单 —— <b>目前为空</b>。
     *
     * <p>之所以能是空的：13 处连接全部搬进了 {@link UserServiceDAO}，服务层一次连接都不需要开。
     * 真要在服务层再开连接，请在这里加一条（格式：{@code 行号 / 方法名 —— 理由}），
     * 并写明"为什么不能放进 DAO、事务边界由谁负责"，而不是把断言改松。</p>
     */
    private static final List<String> SERVICE_GET_CONNECTION_WHITELIST = List.of();

    @Test
    @DisplayName("服务层: UserService 里 DatabaseManager / JDBC / SQL 一处不剩（白名单为空）")
    void serviceLayerHasNoJdbc() throws IOException {
        String source = stripComments(read(USER_SERVICE));

        assertTrue(SERVICE_GET_CONNECTION_WHITELIST.isEmpty(),
                "白名单必须保持为空：服务层不该再开任何连接");

        List<String> offenders = new ArrayList<>();
        for (String forbidden : List.of(
                "DatabaseManager",
                "getConnection(",
                "PreparedStatement",
                "prepareStatement(",
                "ResultSet",
                "executeQuery(",
                "executeUpdate(",
                "createStatement(",
                "setAutoCommit(",
                "runTransaction(",
                "rollback(",
                "java.sql.Connection")) {
            if (source.contains(forbidden)) {
                offenders.add(forbidden);
            }
        }
        assertTrue(offenders.isEmpty(),
                "UserService 不该再碰 JDBC（连接、语句、结果集都属于 UserServiceDAO）：" + offenders);

        // 连 SQL 关键字都不该出现：账号语句必须只有一份，且只在 DAO 里
        List<String> sqlKeywords = new ArrayList<>();
        for (String keyword : List.of("SELECT ", "INSERT ", "UPDATE ", "DELETE ", "CREATE TABLE")) {
            if (source.contains(keyword)) {
                sqlKeywords.add(keyword);
            }
        }
        assertTrue(sqlKeywords.isEmpty(),
                "服务层不该再有 SQL 字面量（搬走了就别留半句）：" + sqlKeywords);
    }

    @Test
    @DisplayName("服务层: 认证语义仍在服务层（BCrypt / JWT / 查重顺序 / 锁定阈值 / 错误码）")
    void authSemanticsStayInTheService() throws IOException {
        String source = stripComments(read(USER_SERVICE));
        for (String anchor : List.of(
                "BCrypt.hashpw",
                "BCrypt.checkpw",
                "JwtUtil.generateToken",
                "AUTH_USERNAME_EXISTS",
                "AUTH_WRONG_PASSWORD",
                "MAX_FAILED_ATTEMPTS",
                "LOCK_DURATION_MINUTES",
                "USERNAME_PATTERN",
                "EMAIL_PATTERN",
                "verify_code.enabled",
                "USER_DAO.")) {
            assertTrue(source.contains(anchor),
                    "服务层必须保留认证语义锚点 " + anchor + "：这次重构只搬数据访问，"
                            + "不许把判定规则搬进 DAO");
        }

        // 查重顺序是安全语义：用户名 → 邮箱 → 验证码，顺序变了错误码就会变
        int usernameCheck = source.indexOf("existsByUsername(");
        int emailCheck = source.indexOf("existsByEmail(");
        int codeCheck = source.indexOf("verifyCode(");
        assertTrue(usernameCheck > 0 && emailCheck > usernameCheck && codeCheck > emailCheck,
                "注册的校验顺序必须仍是：用户名唯一 → 邮箱唯一 → 验证码");

        // DAO 不许碰密码学与令牌：它只存取行
        String daoSource = stripComments(read(USER_SERVICE_DAO));
        for (String forbidden : List.of("BCrypt", "JwtUtil", "GameErrorCode", "jakarta.ws.rs")) {
            assertFalse(daoSource.contains(forbidden),
                    "UserServiceDAO 不该知道认证语义（" + forbidden + "）：它只负责存取行");
        }
    }

    @Test
    @DisplayName("服务层: 业务逻辑通过 UserServiceDAO 访问 users 表")
    void serviceDelegatesToUserServiceDao() throws Exception {
        Field field = UserService.class.getDeclaredField("USER_DAO");
        assertEquals(UserServiceDAO.class, field.getType(),
                "UserService 必须持有 UserServiceDAO 入口，而不是把 SQL 搬回来");
        assertEquals("com.mtxgdn.db", UserServiceDAO.class.getPackageName(),
                "UserServiceDAO 必须与现有 DAO 同包（com.mtxgdn.db）");
    }

    // ==================== 3. SQL 守恒 ====================

    @Test
    @DisplayName("SQL: 原 UserService 的每一条 SQL 都逐字出现在 UserServiceDAO 里（旧有新无为空）")
    void sqlIsConserved() throws IOException {
        String daoSource = stripComments(read(USER_SERVICE_DAO));
        String fingerprint = squash(String.join("", stringLiterals(daoSource)));

        List<String> missing = new ArrayList<>();
        for (String sql : GOLDEN_SQL) {
            if (!fingerprint.contains(squash(sql))) {
                missing.add(sql.strip().replaceAll("\\R", " "));
            }
        }
        assertTrue(missing.isEmpty(),
                "下面这些 SQL 在 UserServiceDAO 里找不到（搬丢了、或被\"顺手优化\"了）。"
                        + "本次重构要求表名/列名/条件/参数顺序逐字不变：\n"
                        + String.join("\n---\n", missing));
    }

    @Test
    @DisplayName("SQL: UserServiceDAO 里不许出现清单之外、看着像 SQL 的新字面量（不许自创语句）")
    void daoInventsNoNewSql() throws IOException {
        String daoSource = stripComments(read(USER_SERVICE_DAO));
        String goldenFingerprint = squash(String.join("", GOLDEN_SQL));

        List<String> invented = new ArrayList<>();
        for (String literal : stringLiterals(daoSource)) {
            String trimmed = literal.strip();
            if (!SQL_LOOKING_LITERAL.matcher(trimmed).matches()) {
                continue; // 不是语句形态（异常文案、列名、空串等）
            }
            if (!goldenFingerprint.contains(squash(trimmed))) {
                invented.add(trimmed.replaceAll("\\R", " "));
            }
        }
        assertTrue(invented.isEmpty(),
                "这些语句在重构前的 UserService 里不存在：\n" + String.join("\n---\n", invented));
    }

    // ==================== 4. 连接与事务边界 ====================

    @Test
    @DisplayName("DAO: 每个 public 方法都有 Connection 首参重载（事务内可复用调用方的连接）")
    void everyDaoMethodHasConnectionFirstOverload() {
        Set<String> withConnection = new HashSet<>();
        for (Method m : UserServiceDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) {
                withConnection.add(m.getName());
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Method m : UserServiceDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) continue;
            if (!withConnection.contains(m.getName())) {
                offenders.add(m.getName() + "(" + parameterList(m) + ")");
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些 DAO 方法没有 Connection 首参重载，事务内调用时只能自己开连接 —— "
                        + "SQLite 下会抛 SQLITE_BUSY_SNAPSHOT 或读到旧快照：\n" + String.join("\n", offenders));

        // 逐个点名关键重载，防止有人把它们改名后"规则依然成立"
        for (String name : List.of(
                "existsByUsername", "existsByEmail",
                "findByUsername", "findByUsernameWithLockInfo",
                "findEmailById", "findPasswordHashById", "findCredentialById",
                "findIdAndUsernameByEmail",
                "insertUser",
                "updatePasswordHash", "resetPassword",
                "recordFailedAttempt", "clearFailedAttempts",
                "updateEmail", "deleteUserById")) {
            assertTrue(withConnection.contains(name), name + " 缺少 Connection 首参重载");
        }
    }

    @Test
    @DisplayName("DAO: 收 Connection 的方法绝不自己开连接、也不自己开事务")
    void daoConnectionOverloadsNeverOpenTheirOwnConnection() throws IOException {
        String source = stripComments(read(USER_SERVICE_DAO));
        List<String> bodies = methodBodiesTakingConnection(source);
        assertTrue(bodies.size() >= 15,
                "扫描到的 Connection 重载太少（" + bodies.size() + " 个），说明源码解析失效了");

        List<String> offenders = new ArrayList<>();
        for (String body : bodies) {
            if (body.contains("getConnection(")) {
                offenders.add("事务重载内部又开了连接：" + firstLine(body));
            }
            if (body.contains("runTransaction(")) {
                offenders.add("事务重载内部又开了一个事务：" + firstLine(body));
            }
            if (body.contains("setAutoCommit(")) {
                offenders.add("事务重载内部改写了 autocommit：" + firstLine(body));
            }
        }
        assertTrue(offenders.isEmpty(),
                "收 Connection 的重载只能用调用方的连接 —— 自己再开一条会在 SQLite 下死锁/读旧快照：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("事务: UserServiceDAO 自己不开事务（重构前这些语句全在 autocommit 下跑）")
    void daoOwnsNoTransaction() throws IOException {
        String source = stripComments(read(USER_SERVICE_DAO));
        List<String> owners = new ArrayList<>();
        for (String[] named : methodBodies(source)) {
            if (named[1].contains("setAutoCommit(")) {
                owners.add(named[0]);
            }
        }
        assertTrue(owners.isEmpty(),
                "本次重构不新增事务边界：原实现里这 13 处连接全是 autocommit，"
                        + "每条语句自己就是一个提交单元。谁要开事务，必须同时给出 Connection 重载："
                        + owners);
    }

    @Test
    @DisplayName("事务: 任何 runTransaction lambda 体内都不出现 DatabaseManager.getConnection()")
    void transactionLambdasNeverOpenTheirOwnConnections() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = stripComments(read(file));
                for (String body : transactionLambdaBodies(source)) {
                    if (body.contains("DatabaseManager.getConnection()")) {
                        offenders.add(file.getFileName() + " -> 事务块内调用了 DatabaseManager.getConnection()");
                    }
                    if (body.contains("runTransaction(")) {
                        offenders.add(file.getFileName() + " -> 事务块内又开了一个事务");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "事务块内再开连接/事务在 SQLite 下会死锁或读到旧快照。"
                        + "需要复用事务时请把 Connection 作为参数传下去：\n" + String.join("\n", offenders));
    }

    // ==================== 5. 行为：真跑 SQLite ====================

    @Test
    @DisplayName("行为: 注册成功后能按用户名/邮箱查回，且锁定信息可读")
    void registerThenLookupByUsernameAndEmail() {
        Account account = registerNew();

        User byName = DAO.findByUsername(account.username);
        assertNotNull(byName, "注册成功后必须能按用户名查回账号");
        assertEquals(account.id, byName.getId().longValue());
        assertEquals(account.username, byName.getUsername());
        assertTrue(byName.getPassword().startsWith("$2"),
                "库里存的必须是 BCrypt 哈希（$2 开头），不能是明文: " + byName.getPassword());

        User withLock = DAO.findByUsernameWithLockInfo(account.username);
        assertNotNull(withLock, "登录路径（带锁定信息）也必须能查到");
        assertEquals(0, withLock.getFailedAttempts().intValue(), "新账号失败计数应为 0");
        assertNull(withLock.getLockedUntil(), "新账号不该处于锁定状态");

        assertTrue(DAO.existsByUsername(account.username), "existsByUsername 应为 true");
        assertTrue(DAO.existsByEmail(account.email), "existsByEmail 应为 true");
        assertEquals(account.email, DAO.findEmailById(account.id), "按 id 取邮箱应一致");

        UserServiceDAO.EmailOwnerRow owner = DAO.findIdAndUsernameByEmail(account.email);
        assertNotNull(owner, "按邮箱必须能定位到账号");
        assertEquals(account.id, owner.id());
        assertEquals(account.username, owner.username());

        assertNotNull(SERVICE.authenticate(account.username, PASSWORD),
                "authenticate 走 findByUsername，注册后应能认证通过");
        assertNull(SERVICE.authenticate(account.username, "WrongPass1!"), "错误密码必须认证失败");
    }

    @Test
    @DisplayName("行为: 重复用户名被拒（409 + 原错误码文案），重复邮箱被拒（409）")
    void duplicateUsernameAndEmailAreRejected() {
        Account first = registerNew();

        // 同一个用户名、换一个邮箱
        String otherEmail = uniqueEmail();
        Response dupName = SERVICE.register(first.username, PASSWORD, otherEmail,
                new VerificationCodeService().generateAndStoreCode(otherEmail));
        assertEquals(409, dupName.getStatus(), "重复用户名必须回 409");
        assertEquals(GameErrorCode.AUTH_USERNAME_EXISTS.getMessage(), body(dupName).get("message").getAsString(),
                "重复用户名的文案必须仍取自 GameErrorCode.AUTH_USERNAME_EXISTS");

        // 新用户名、但邮箱已被占用
        String newName = uniqueUsername();
        Response dupEmail = SERVICE.register(newName, PASSWORD, first.email,
                new VerificationCodeService().generateAndStoreCode(first.email));
        assertEquals(409, dupEmail.getStatus(), "重复邮箱必须回 409");
        assertEquals("该邮箱已被注册", body(dupEmail).get("message").getAsString(),
                "重复邮箱的文案必须逐字不变");

        assertNull(DAO.findByUsername(newName), "被拒的注册不该写库");
        assertTrue(DAO.existsByUsername(first.username), "原账号必须还在");
    }

    @Test
    @DisplayName("行为: 改密码后新密码可校验、旧密码失效（改密读写的仍是同一张表）")
    void changePasswordThenNewPasswordVerifies() {
        Account account = registerNew();
        String newPassword = "NewPassword2!";

        assertTrue(SERVICE.verifyPassword(account.id, PASSWORD), "改密前旧密码应校验通过");

        Response changed = SERVICE.changePassword(account.id, PASSWORD, newPassword);
        assertEquals(200, changed.getStatus(), "改密应成功");

        assertTrue(SERVICE.verifyPassword(account.id, newPassword),
                "改密后新密码必须能校验通过（若失败说明 UPDATE 没落库）");
        assertFalse(SERVICE.verifyPassword(account.id, PASSWORD), "改密后旧密码必须失效");
        assertEquals(200, SERVICE.login(account.username, newPassword).getStatus(),
                "改密后应能用新密码登录");
        assertEquals(401, SERVICE.login(account.username, PASSWORD).getStatus(),
                "旧密码登录必须失败");

        // 原密码错 → 401；用户不存在 → 404
        assertEquals(401, SERVICE.changePassword(account.id, "WrongPass1!", "Another3!").getStatus(),
                "原密码错误必须回 401");
        assertEquals(404, SERVICE.changePassword(-1L, PASSWORD, "Another3!").getStatus(),
                "用户不存在必须回 404");
        assertFalse(SERVICE.verifyPassword(-1L, PASSWORD), "不存在的用户校验密码必须返回 false");
    }

    @Test
    @DisplayName("行为: 删用户后查不到，再删一次回 404")
    void deleteUserThenAccountIsGone() {
        Account account = registerNew();

        Response deleted = SERVICE.deleteUser(account.id);
        assertEquals(200, deleted.getStatus(), "注销应成功");

        assertNull(DAO.findByUsername(account.username), "注销后必须按用户名查不到");
        assertFalse(DAO.existsByUsername(account.username), "注销后 existsByUsername 必须为 false");
        assertNull(DAO.findEmailById(account.id), "注销后邮箱也读不到");
        assertNull(SERVICE.authenticate(account.username, PASSWORD), "注销后不能再认证");

        assertEquals(404, SERVICE.deleteUser(account.id).getStatus(),
                "重复注销必须回 404（DAO 必须把 0 行受影响报回来）");
    }

    @Test
    @DisplayName("行为: 邮箱重置密码会改密并解锁（只改密码不解锁等于还是进不去）")
    void resetPasswordByEmailUnlocksAndChangesPassword() {
        Account account = registerNew();
        String newPassword = "ResetPass3!";

        // 先把账号锁死：连续输错 MAX_FAILED_ATTEMPTS 次
        for (int i = 0; i < 5; i++) {
            assertEquals(401, SERVICE.login(account.username, "WrongPass1!").getStatus());
        }
        assertEquals(423, SERVICE.login(account.username, PASSWORD).getStatus(),
                "前置条件：账号应已被锁定");

        Response reset = SERVICE.resetPasswordByEmail(account.email, newPassword);
        assertEquals(200, reset.getStatus(), "邮箱重置密码应成功");
        assertEquals(account.username, body(reset).getAsJsonObject("data").get("username").getAsString(),
                "响应里的 username 必须仍是该账号的用户名");

        User afterReset = DAO.findByUsernameWithLockInfo(account.username);
        assertEquals(0, afterReset.getFailedAttempts().intValue(),
                "重置密码必须把失败计数清零");
        assertNull(afterReset.getLockedUntil(), "重置密码必须解除锁定");
        assertEquals(200, SERVICE.login(account.username, newPassword).getStatus(),
                "重置后应能用新密码登录");

        assertEquals(404, SERVICE.resetPasswordByEmail("no_such_" + uniqueUsername() + "@qq.com", newPassword)
                .getStatus(), "未注册的邮箱必须回 404");
    }

    @Test
    @DisplayName("行为: 登录失败计数累加、成功登录清零（阈值策略仍在服务层）")
    void failedAttemptsAccumulateAndReset() {
        Account account = registerNew();

        assertEquals(401, SERVICE.login(account.username, "WrongPass1!").getStatus());
        assertEquals(401, SERVICE.login(account.username, "WrongPass1!").getStatus());
        User afterTwo = DAO.findByUsernameWithLockInfo(account.username);
        assertEquals(2, afterTwo.getFailedAttempts().intValue(),
                "两次输错密码必须累加到 2（计数在 SQL 里做加法）");
        assertNull(afterTwo.getLockedUntil(), "未达阈值不该锁定");

        assertEquals(200, SERVICE.login(account.username, PASSWORD).getStatus(), "正确密码应登录成功");
        User afterOk = DAO.findByUsernameWithLockInfo(account.username);
        assertEquals(0, afterOk.getFailedAttempts().intValue(), "登录成功必须把失败计数清零");
        assertNull(afterOk.getLockedUntil(), "登录成功必须解除锁定");
    }

    @Test
    @DisplayName("行为: 改邮箱只在原邮箱为空/相同时生效（条件留在 SQL 里）")
    void emailUpdateOnlyFillsEmptyOrSameEmail() {
        Account account = registerNew();

        assertEquals(account.email, SERVICE.getUserEmail(account.id), "刚注册的邮箱应能读回");

        SERVICE.updateUserEmail(account.id, "someone_else@qq.com");
        assertEquals(account.email, SERVICE.getUserEmail(account.id),
                "已有邮箱时不该被别的邮箱覆盖（SQL 的 WHERE 条件必须还在）");

        SERVICE.updateUserEmail(account.id, account.email);
        assertEquals(account.email, SERVICE.getUserEmail(account.id), "写回同一个邮箱是允许的");

        // 一个没有邮箱的账号（注册时关闭验证码即为 NULL 邮箱）可以被补填
        String noEmailUser = uniqueUsername();
        User raw = DAO.insertUser(noEmailUser, "x", null);
        assertNotNull(raw, "直接插入账号应成功");
        assertNull(SERVICE.getUserEmail(raw.getId()), "未设邮箱的账号读出来应是 null");

        SERVICE.updateUserEmail(raw.getId(), "filled@qq.com");
        assertEquals("filled@qq.com", SERVICE.getUserEmail(raw.getId()),
                "原邮箱为空时必须能补填（SQL 里的 email IS NULL OR email = '' 分支）");

        SERVICE.updateUserEmail(raw.getId(), null);
        assertEquals("filled@qq.com", SERVICE.getUserEmail(raw.getId()),
                "已有非空邮箱时置 NULL 不该生效");
    }

    // ==================== 6. 事务内用 Connection 重载 ====================

    @Test
    @DisplayName("事务: 事务内用 Connection 重载写入 —— 回滚就真的回滚（证明用的是同一条连接）")
    void daoConnectionOverloadsJoinTheCallerTransaction() {
        String username = uniqueUsername();

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                DatabaseManager.runTransaction(conn -> {
                    DAO.insertUser(conn, username, "x", null);
                    assertNotNull(DAO.findByUsername(conn, username),
                            "同一事务内必须立刻读到自己刚写的账号（说明用的是同一条连接）");
                    throw new IllegalStateException("故意回滚");
                }));
        assertTrue(String.valueOf(error.getMessage()).contains("故意回滚"),
                "事务里的异常必须原样冒出来，实际: " + error.getMessage());

        assertNull(DAO.findByUsername(username),
                "事务已回滚，这个账号不该留在库里 —— 若留下了，说明 DAO 在事务里另开了一条"
                        + "（autocommit）连接，SQLite 下正是 SQLITE_BUSY_SNAPSHOT 的来源");
    }

    @Test
    @DisplayName("事务: 有并发写在跑时，事务内调 Connection 重载也不会抛 SQLITE_BUSY_SNAPSHOT")
    void transactionWithConcurrentWriterDoesNotRaiseBusySnapshot() throws Exception {
        Account mine = registerNew();
        Account other = registerNew();

        CountDownLatch txHasRead = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        AtomicBoolean interfered = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                txHasRead.await();
                // 另一个连接、另一行、同一张表 —— 它一提交，事务里已有的读快照就过期了
                DAO.recordFailedAttempt(other.id, 5,
                        new Timestamp(System.currentTimeMillis() + 60_000L));
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                otherCommitted.countDown();
            }
        }, "user-dao-concurrent-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            for (int round = 0; round < 3; round++) {
                int expected = round; // 循环变量不是 effectively final，lambda 里只能捕获这个副本
                DatabaseManager.runTransaction(conn -> {
                    // 先读（建立快照）
                    User before = DAO.findByUsernameWithLockInfo(conn, mine.username);
                    assertNotNull(before, "事务内必须能读到账号");
                    if (interfered.compareAndSet(false, true)) {
                        // 制造"读后写 + 期间别的连接提交了写"这一形状：
                        // 第一轮的 UPDATE 会撞上 SQLITE_BUSY_SNAPSHOT，重试由 runTransaction 负责，
                        // 它绝不该把这个异常冒给调用方。
                        txHasRead.countDown();
                        assertTrue(otherCommitted.await(10, TimeUnit.SECONDS), "并发写者应在 10 秒内提交");
                    }
                    // 阈值给 2：第 2 轮起必须真的写进 locked_until（COALESCE/CASE 分支）
                    DAO.recordFailedAttempt(conn, mine.id, 2,
                            new Timestamp(System.currentTimeMillis() + 900_000L));
                    User after = DAO.findByUsernameWithLockInfo(conn, mine.username);
                    assertTrue(after.getFailedAttempts() >= 1,
                            "第 " + expected + " 轮：同一事务内必须看到自己的写入");
                    return null;
                });
            }
        } finally {
            writer.join(10_000L);
        }

        assertNull(writerFailure.get(), "并发写线程不该失败: " + writerFailure.get());
        User finalRow = DAO.findByUsernameWithLockInfo(mine.username);
        assertEquals(3, finalRow.getFailedAttempts().intValue(),
                "3 轮事务的计数必须都落地（每轮恰好提交一次）");
        assertNotNull(finalRow.getLockedUntil(),
                "计数达到阈值后必须写入 locked_until（CASE/COALESCE 分支不能写坏）");

        User otherRow = DAO.findByUsernameWithLockInfo(other.username);
        assertEquals(1, otherRow.getFailedAttempts().intValue(), "并发写者的那笔写入也必须落地");
    }

    // ==================== 夹具 ====================

    /** 一个刚注册成功的账号（走真实注册路径：验证码 + BCrypt + 默认角色 + JWT）。 */
    private record Account(long id, String username, String email) {
    }

    private static Account registerNew() {
        String username = uniqueUsername();
        String email = uniqueEmail();
        String code = new VerificationCodeService().generateAndStoreCode(email);
        Response response = SERVICE.register(username, PASSWORD, email, code);
        assertEquals(200, response.getStatus(),
                "注册应成功，实际响应: " + response.getStatus() + " " + response.getEntity());
        long id = body(response).getAsJsonObject("data").get("id").getAsLong();
        assertTrue(id > 0, "注册返回的 id 必须大于 0");
        return new Account(id, username, email);
    }

    private static String uniqueUsername() {
        return "usdao_" + SEQ.incrementAndGet() + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String uniqueEmail() {
        return uniqueUsername() + "@qq.com";
    }

    private static JsonObject body(Response response) {
        return JsonParser.parseString(String.valueOf(response.getEntity())).getAsJsonObject();
    }

    // ==================== 辅助 ====================

    /** 反射导出与 {@code javap -public} 同格式的公开成员清单（排序后便于逐字比较）。 */
    private static List<String> describePublicApi(Class<?> type) {
        List<String> members = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(field.getModifiers())) sb.append("static ");
            if (Modifier.isFinal(field.getModifiers())) sb.append("final ");
            members.add(sb.append(field.getGenericType().getTypeName())
                    .append(' ').append(field.getName()).toString());
        }
        for (var ctor : type.getDeclaredConstructors()) {
            if (!Modifier.isPublic(ctor.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ").append(type.getTypeName()).append('(');
            Type[] params = ctor.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            StringBuilder sb = new StringBuilder("public ");
            if (Modifier.isStatic(method.getModifiers())) sb.append("static ");
            sb.append(method.getGenericReturnType().getTypeName())
                    .append(' ').append(method.getName()).append('(');
            Type[] params = method.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getTypeName());
            }
            members.add(sb.append(')').toString());
        }
        return members.stream().sorted().toList();
    }

    private static String parameterList(Method m) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 去掉全部空白，用于"SQL 字面量守恒"的比对（换行/缩进不算差异）。 */
    private static String squash(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static String firstLine(String body) {
        String trimmed = body.strip();
        int nl = trimmed.indexOf('\n');
        return nl > 0 ? trimmed.substring(0, nl).strip() : trimmed;
    }

    /**
     * 抽出源码里的全部字符串字面量（含 text block），按出现顺序拼接。
     *
     * <p>拼接后再去空白，是为了让 {@code "A " + "B"} 这种被拆开的 SQL 也能与
     * 完整的 SQL 文本对上 —— 本次重构允许把 SQL 拆成常量，但不允许改内容。</p>
     */
    private static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                assertTrue(end > 0, "text block 未闭合：源码解析失败");
                literals.add(source.substring(i + 3, end));
                i = end + 3;
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            while (j < source.length()) {
                char d = source.charAt(j);
                if (d == '\\' && j + 1 < source.length()) {
                    sb.append(source.charAt(j + 1));
                    j += 2;
                    continue;
                }
                if (d == '"') break;
                sb.append(d);
                j++;
            }
            literals.add(sb.toString());
            i = j + 1;
        }
        return literals;
    }

    /**
     * 取出源码里所有"第一个参数是 {@code Connection} 的方法"的声明 + 方法体。
     *
     * <p>只认<b>方法声明</b>（行首访问修饰符 + 名字 + {@code (Connection conn}），
     * 否则 {@code try (Connection conn = DatabaseManager.getConnection())} 这种
     * try-with-resources 也会被误当成方法头，扫描结果就不可信了。</p>
     */
    private static List<String> methodBodiesTakingConnection(String source) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = CONNECTION_METHOD_DECL.matcher(source);
        while (matcher.find()) {
            String body = methodBodyAt(source, matcher.start());
            if (body != null) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** 取出源码里所有方法：每个元素是 {@code {方法名, 声明 + 方法体}}。 */
    private static List<String[]> methodBodies(String source) {
        List<String[]> bodies = new ArrayList<>();
        Matcher matcher = METHOD_DECL.matcher(source);
        while (matcher.find()) {
            String body = methodBodyAt(source, matcher.start());
            if (body != null) {
                bodies.add(new String[]{matcher.group(1), body});
            }
        }
        return bodies;
    }

    /** 从方法声明的起点（{@code start}）取出"声明 + 方法体"；抽象声明返回 {@code null}。 */
    private static String methodBodyAt(String source, int start) {
        int open = source.indexOf('(', start);
        int close = matchingParen(source, open);
        int brace = source.indexOf('{', close);
        int semi = source.indexOf(';', close);
        // 抽象方法声明（如回调接口里的一行签名）没有方法体，跳过
        if (semi >= 0 && (brace < 0 || semi < brace)) return null;
        int end = matchingBrace(source, brace);
        return source.substring(start, end + 1);
    }

    /** 取出每个 {@code DatabaseManager.runTransaction(...)} 调用的实参文本（即 lambda 体）。 */
    private static List<String> transactionLambdaBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String needle = "DatabaseManager.runTransaction(";
        int from = 0;
        int idx;
        while ((idx = source.indexOf(needle, from)) >= 0) {
            int open = idx + needle.length() - 1;
            int close = matchingParen(source, open);
            bodies.add(source.substring(open + 1, close));
            from = close;
        }
        return bodies;
    }

    /** 从 {@code open} 处的 {@code (} 起找配对的 {@code )}，跳过字符串与字符字面量。 */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("括号不匹配：源码扫描失败");
    }

    /** 从 {@code open} 处的 {@code {} 起找配对的 {@code }}，跳过字符串与字符字面量。 */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (d == '\\') {
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j - 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalStateException("花括号不匹配：源码扫描失败");
    }

    /** 剥掉 {@code //} 与 {@code /* *}{@code /} 注释，保留字符串/字符字面量。 */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    out.append(d);
                    if (d == '\\' && j + 1 < source.length()) {
                        out.append(source.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    j++;
                    if (d == c) break;
                }
                i = j;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
