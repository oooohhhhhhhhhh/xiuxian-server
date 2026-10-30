package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import com.mtxgdn.permission.PermissionCode;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.util.AppConfig;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * 守护「{@code PermissionService} 里的裸 JDBC 抽到 {@link PermissionDAO}」这次纯结构重构。
 *
 * <p><b>缺陷形态</b>：{@code PermissionService} 一个类里 985 行、23 处
 * {@code DatabaseManager.getConnection()}，权限判定（角色层级、默认权限集合、继承展开、
 * 缓存失效）与 {@code PreparedStatement} / {@code ResultSet} / {@code SQLException} /
 * {@code setAutoCommit} 混在一起。它是<b>安全代码</b>，所以混在一起格外糟：</p>
 * <ol>
 *   <li>改一条 SQL 与改一条判定规则会出现在同一份 diff 里，review 时无法区分
 *       "只是搬了语句"与"放宽了判定"；</li>
 *   <li>"哪条语句在事务里"看不出来：同一个类里既有手写事务
 *       （删组 / 整组覆盖权限）又有随手开的短连接；</li>
 *   <li>SQLite 上最致命的一条：事务里若用<b>另一个连接</b>读写同一张表，
 *       会抛 {@code SQLITE_BUSY_SNAPSHOT}（不受 {@code busy_timeout} 约束）
 *       或读到旧快照。所以 DAO 的每个方法都必须有"收调用方连接"的重载。</li>
 * </ol>
 *
 * <p>本测试钉住六类不变量：</p>
 * <ol>
 *   <li><b>公开 API 逐字不变</b>：{@code javap -public} 黄金清单（重构前后导出一致）；</li>
 *   <li><b>服务层不再有 JDBC</b>：{@code PermissionService} 源码里
 *       {@code DatabaseManager} / {@code getConnection(} / {@code PreparedStatement} /
 *       {@code ResultSet} / {@code setAutoCommit(} / SQL 关键字一处不剩，
 *       而权限语义（层级表、默认权限集合、缓存）必须还在服务层；</li>
 *   <li><b>SQL 守恒</b>：原 {@code PermissionService} 里的每一条 SQL 都还在
 *       {@link PermissionDAO} 里（去空白后比对，只允许"搬走"，不允许"改掉/丢掉"），
 *       并且 DAO 里不许出现清单之外的、看着像 SQL 的新字面量；</li>
 *   <li><b>连接与事务</b>：每个 DAO 方法都有 {@code Connection} 首参重载，
 *       而<b>收连接的方法绝不自己开连接、也不自己开事务</b>；
 *       只有两个"自己就是事务边界"的方法可以碰 {@code setAutoCommit}；
 *       任何事务 lambda 体内也不许再开连接；</li>
 *   <li><b>权限语义</b>：角色层级数值、默认组、继承关系、缓存失效时机都不变；</li>
 *   <li><b>真跑 SQLite</b>：授予角色后 {@code hasPermission} 通过、撤销后失败、
 *       层级继承仍生效、缓存失效后能读到新权限、事务内用 {@code Connection} 重载
 *       不会抛 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ol>
 *
 * <p>源码级断言一律先剥掉注释（见 {@link #stripComments}）：把关键字写在注释里
 * 不算数，也不允许注释文本冒充断言锚点。</p>
 */
class PermissionDaoExtractionTest extends TestBase {

    private static final Path PERMISSION_SERVICE =
            Paths.get("src/main/java/com/mtxgdn/permission/PermissionService.java");
    private static final Path PERMISSION_DAO =
            Paths.get("src/main/java/com/mtxgdn/db/PermissionDAO.java");

    private static final PermissionDAO DAO = new PermissionDAO();

    private static final AtomicInteger SEQ = new AtomicInteger();

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
     * {@code javap -public -classpath target/classes com.mtxgdn.permission.PermissionService}
     * 在<b>重构前</b>导出的公开成员清单（逐字抄录，去掉行尾分号与 {@code synchronized}
     * 这类不影响签名的修饰符，与 {@link #describePublicApi(Class)} 的输出格式一致）。
     */
    private static final List<String> GOLDEN_SERVICE_API = List.of(
            "public com.mtxgdn.permission.PermissionService()",
            "public static void invalidatePermissionCache()",
            "public static void invalidatePermissionCache(long)",
            "public static void refreshGroupCache()",
            "public static void registerPluginPermission(java.lang.String, java.lang.String, java.lang.String)",
            "public static boolean isPluginPermission(java.lang.String)",
            "public static java.util.Map<java.lang.String, com.mtxgdn.permission.PermissionService$PluginPermissionInfo> getPluginPermissions()",
            "public static boolean isValidPermissionCode(java.lang.String)",
            "public static java.util.Map<java.lang.String, java.lang.Integer> getRoleHierarchy()",
            "public static java.util.Set<java.lang.String> getRoleNames()",
            "public static java.lang.String getGroupDisplayName(java.lang.String)",
            "public static java.util.List<java.util.Map<java.lang.String, java.lang.Object>> getAllGroups()",
            "public static java.lang.String getDefaultRoleName()",
            "public static java.util.List<java.lang.String> getUserRoles(long)",
            "public static java.lang.String getHighestRole(long)",
            "public static int getHighestRoleLevel(long)",
            "public static java.util.Set<com.mtxgdn.permission.PermissionCode> getUserDirectPermissions(long)",
            "public static java.util.Set<java.lang.String> getUserPluginPermissionCodes(long)",
            "public static java.util.Set<java.lang.String> getUserGroupPermissionCodes(long)",
            "public static java.util.Set<com.mtxgdn.permission.PermissionCode> getUserPermissions(long)",
            "public static java.util.Set<java.lang.String> getAllUserPermissionCodes(long)",
            "public static boolean hasPermission(long, java.lang.String)",
            "public static void assignRole(long, java.lang.String)",
            "public static void removeRole(long, java.lang.String)",
            "public static void assignDefaultRole(long)",
            "public static void assignPermission(long, java.lang.String)",
            "public static void removePermission(long, java.lang.String)",
            "public static java.util.Set<java.lang.String> getGroupPermissionCodes(java.lang.String)",
            "public static java.util.Set<com.mtxgdn.permission.PermissionCode> getRoleDefaultPermissions(java.lang.String)",
            "public static void createGroup(java.lang.String, java.lang.String, int)",
            "public static void updateGroup(java.lang.String, java.lang.String, int)",
            "public static void deleteGroup(java.lang.String)",
            "public static void addGroupPermission(java.lang.String, java.lang.String)",
            "public static void removeGroupPermission(java.lang.String, java.lang.String)",
            "public static void setGroupPermissions(java.lang.String, java.util.Set<java.lang.String>)",
            "public static java.util.List<java.util.Map<java.lang.String, java.lang.Object>> getAllUsersWithRoles()",
            "public static void initDefaultData()",
            // 换库时必须让权限组缓存与用户权限缓存失效（见 ServiceRegistry 的换库监听）。
            // 新增的公开方法，黄金清单同步登记。
            "public static void invalidateAllCachesForNewDatabase()");

    /** {@code javap -public com.mtxgdn.permission.PermissionService$PluginPermissionInfo} 重构前的清单。 */
    private static final List<String> GOLDEN_PLUGIN_PERMISSION_INFO_API = List.of(
            "public final java.lang.String category",
            "public final java.lang.String code",
            "public final java.lang.String name");

    /**
     * 原 {@code PermissionService.java} 里的全部 SQL（逐条抄录，去空白后比对）。
     *
     * <p>这批字符串是"重构前的真身"：只要有一条在 {@link PermissionDAO} 里找不到，
     * 就说明搬运时改了字面量（表名/列名/条件/ORDER BY/UNIQUE/参数顺序），
     * 而那类改动不会让任何业务测试变红 —— 它只会在生产上悄悄换个结果集，
     * 或者让某个权限组少一条权限。</p>
     *
     * <p>注意带 {@code INSERT OR IGNORE} / {@code INSERT IGNORE} 前缀的那几条：
     * 前缀是方言常量，原实现写成 {@code INSERT_OR_IGNORE + " INTO ..."}，
     * 因此这里把"前缀"与"后半句"分开列（拼接后的完整语句在原文件里本来就不存在
     * 单一字面量，硬要比对整句等于自欺）。</p>
     */
    private static final List<String> GOLDEN_SQL = List.of(
            // ---- 权限组目录 ----
            "SELECT name, display_name, level FROM roles ORDER BY level DESC",
            "SELECT role_name, permission_code FROM role_permissions",
            // ---- 用户 → 角色 / 直接权限 ----
            "SELECT role_name FROM user_roles WHERE user_id = ?",
            "SELECT permission_code FROM user_permissions WHERE user_id = ?",
            "SELECT COUNT(*) FROM permissions WHERE code = ?",
            "SELECT u.id, u.username, ur.role_name FROM users u LEFT JOIN user_roles ur ON u.id = ur.user_id ORDER BY u.id",
            // ---- 方言前缀 ----
            "INSERT OR IGNORE",
            "INSERT IGNORE",
            // ---- 权限组定义 ----
            " INTO roles (name, level, display_name) VALUES (?, ?, ?)",
            "INSERT INTO roles (name, display_name, level) VALUES (?, ?, ?)",
            "UPDATE roles SET display_name = ?, level = ? WHERE name = ?",
            "DELETE FROM roles WHERE name = ?",
            // ---- 组 → 权限 ----
            " INTO role_permissions (role_name, permission_code) VALUES (?, ?)",
            "DELETE FROM role_permissions WHERE role_name = ?",
            "DELETE FROM role_permissions WHERE role_name = ? AND permission_code = ?",
            // ---- 用户 → 角色 ----
            " INTO user_roles (user_id, role_name) VALUES (?, ?)",
            "DELETE FROM user_roles WHERE user_id = ? AND role_name = ?",
            "DELETE FROM user_roles WHERE role_name = ?",
            // ---- 用户 → 直接权限 ----
            " INTO user_permissions (user_id, permission_code) VALUES (?, ?)",
            "DELETE FROM user_permissions WHERE user_id = ? AND permission_code = ?",
            // ---- 权限码登记 ----
            " INTO permissions (code, name, category) VALUES (?, ?, ?)",
            // ---- 建表（原实现是一条 Statement + 四段拼接，这里按拼接后的整句比对） ----
            "CREATE TABLE IF NOT EXISTS user_permissions (user_id INTEGER NOT NULL, permission_code VARCHAR(255) NOT NULL, UNIQUE(user_id, permission_code))");

    // ==================== 1. 公开 API 逐字不变 ====================

    @Test
    @DisplayName("API: PermissionService 的公开成员与重构前逐字一致（javap 黄金清单）")
    void permissionServiceKeepsGoldenPublicApi() {
        List<String> actual = describePublicApi(PermissionService.class);
        assertEquals(GOLDEN_SERVICE_API.stream().sorted().toList(), actual,
                "PermissionService 的公开 API 必须与重构前完全一致：多一个、少一个、"
                        + "改一个参数类型都会让 REST / WebSocket / 指令 / 插件 / 其它服务的调用方编译不过");

        assertEquals(GOLDEN_PLUGIN_PERMISSION_INFO_API.stream().sorted().toList(),
                describePublicApi(PermissionService.PluginPermissionInfo.class),
                "嵌套的 PluginPermissionInfo 字段（code/name/category）是管理后台读取的公开契约，不能变");
    }

    // ==================== 2. 服务层不再有 JDBC ====================

    /**
     * {@code DatabaseManager.getConnection()} 的白名单 —— <b>目前为空</b>。
     *
     * <p>之所以能是空的：这次重构把两个手写事务（删组、整组覆盖权限）的边界也一起搬进了
     * {@link PermissionDAO}，服务层因此一次连接都不需要开。真要在服务层再开连接，
     * 请在这里加一条并写明理由（哪一行、为什么不能放进 DAO、事务边界由谁负责），
     * 而不是把断言改松。</p>
     */
    private static final List<String> SERVICE_GET_CONNECTION_WHITELIST = List.of();

    @Test
    @DisplayName("服务层: PermissionService 里 DatabaseManager / JDBC / SQL 一处不剩（白名单为空）")
    void serviceLayerContainsNoJdbc() throws IOException {
        String source = stripComments(read(PERMISSION_SERVICE));

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
                "java.sql")) {
            if (source.contains(forbidden)) {
                offenders.add(forbidden);
            }
        }
        assertTrue(offenders.isEmpty(),
                "PermissionService 不该再碰 JDBC（连接、方言差异、事务都属于 PermissionDAO）："
                        + offenders);

        // 连 SQL 关键字都不该出现：权限语句必须只有一份，且只在 DAO 里
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
    @DisplayName("服务层: 权限语义仍在服务层（层级/默认集合/继承/缓存），只是数据访问换成了 DAO")
    void permissionSemanticsStayInTheService() throws IOException {
        String source = stripComments(read(PERMISSION_SERVICE));
        for (String anchor : List.of(
                "DEFAULT_GROUP_HIERARCHY",
                "buildDefaultGroupPermissions",
                "getEffectivePermissionCodes",
                "invalidatePermissionCache",
                "PERMISSION_CACHE",
                "PERMISSION_DAO.")) {
            assertTrue(source.contains(anchor),
                    "服务层必须保留权限语义锚点 " + anchor + "："
                            + "这次重构只搬数据访问，不许把判定规则搬进 DAO");
        }

        String daoSource = stripComments(read(PERMISSION_DAO));
        // 注意 "PermissionCode" 是方法名 existsPermissionCode / findDirectPermissionCodes 的子串，
        // 因此这里查的是"引用权限枚举类型"与"写死权限组名"两种真正的语义耦合。
        for (String forbidden : List.of("com.mtxgdn.permission", "PermissionCode.", "SUPER_ADMIN", "MODERATOR", "GUEST")) {
            assertFalse(daoSource.contains(forbidden),
                    "PermissionDAO 不该知道权限语义（" + forbidden + "）：它只负责存取行");
        }
    }

    @Test
    @DisplayName("服务层: 业务逻辑通过 PermissionDAO 访问权限表")
    void serviceDelegatesToPermissionDao() throws Exception {
        Field field = PermissionService.class.getDeclaredField("PERMISSION_DAO");
        assertEquals(PermissionDAO.class, field.getType(),
                "PermissionService 必须持有 PermissionDAO 入口，而不是把 SQL 搬回来");
        assertEquals("com.mtxgdn.db", PermissionDAO.class.getPackageName(),
                "PermissionDAO 必须与现有两个 DAO 同包（com.mtxgdn.db）");
    }

    // ==================== 3. SQL 守恒 ====================

    @Test
    @DisplayName("SQL: 原 PermissionService 的每一条 SQL 都逐字出现在 PermissionDAO 里（旧有新无为空）")
    void sqlIsConserved() throws IOException {
        String daoSource = stripComments(read(PERMISSION_DAO));
        String fingerprint = squash(String.join("", stringLiterals(daoSource)));

        List<String> missing = new ArrayList<>();
        for (String sql : GOLDEN_SQL) {
            if (!fingerprint.contains(squash(sql))) {
                missing.add(sql.strip().replaceAll("\\R", " "));
            }
        }
        assertTrue(missing.isEmpty(),
                "下面这些 SQL 在 PermissionDAO 里找不到（搬丢了、或被\"顺手优化\"了）。"
                        + "本次重构要求表名/列名/条件/ORDER BY/UNIQUE/参数顺序逐字不变：\n"
                        + String.join("\n---\n", missing));
    }

    @Test
    @DisplayName("SQL: PermissionDAO 里不许出现清单之外、看着像 SQL 的新字面量（不许自创语句）")
    void daoInventsNoNewSql() throws IOException {
        String daoSource = stripComments(read(PERMISSION_DAO));
        String goldenFingerprint = squash(String.join("", GOLDEN_SQL));

        List<String> invented = new ArrayList<>();
        for (String literal : stringLiterals(daoSource)) {
            String trimmed = literal.strip();
            if (!SQL_LOOKING_LITERAL.matcher(trimmed).matches()) {
                continue; // 不是语句形态（异常文案、空串等）
            }
            if (!goldenFingerprint.contains(squash(trimmed))) {
                invented.add(trimmed.replaceAll("\\R", " "));
            }
        }
        assertTrue(invented.isEmpty(),
                "这些语句在重构前的 PermissionService 里不存在：\n" + String.join("\n---\n", invented));
    }

    // ==================== 4. 连接与事务边界 ====================

    @Test
    @DisplayName("DAO: 每个 public 方法都有 Connection 首参重载（事务内可复用调用方的连接）")
    void everyDaoMethodHasConnectionFirstOverload() {
        Set<String> withConnection = new HashSet<>();
        for (Method m : PermissionDAO.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() > 0 && m.getParameterTypes()[0] == Connection.class) {
                withConnection.add(m.getName());
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Method m : PermissionDAO.class.getDeclaredMethods()) {
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
                "loadGroupCatalog", "findRoleNamesByUserId", "findDirectPermissionCodes",
                "existsPermissionCode", "findAllUserRoleRows",
                "insertRoleIfAbsent", "createRole", "updateRole", "deleteGroupCascade",
                "insertRolePermissionIfAbsent", "deleteRolePermission", "replaceRolePermissions",
                "insertPermissionIfAbsent",
                "insertUserRoleIfAbsent", "deleteUserRole",
                "insertUserPermissionIfAbsent", "deleteUserPermission",
                "createUserPermissionsTable")) {
            assertTrue(withConnection.contains(name), name + " 缺少 Connection 首参重载");
        }
    }

    @Test
    @DisplayName("DAO: 收 Connection 的方法绝不自己开连接、也不自己开事务")
    void daoConnectionOverloadsNeverOpenTheirOwnConnection() throws IOException {
        String source = stripComments(read(PERMISSION_DAO));
        List<String> bodies = methodBodiesTakingConnection(source);
        assertTrue(bodies.size() >= 18,
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
    @DisplayName("事务: 只有 deleteGroupCascade / replaceRolePermissions 自己管事务（且各有 Connection 重载）")
    void onlyTwoMethodsOwnTheirTransaction() throws IOException {
        String source = stripComments(read(PERMISSION_DAO));
        List<String> owners = new ArrayList<>();
        for (String[] named : methodBodies(source)) {
            if (named[1].contains("setAutoCommit(")) {
                owners.add(named[0]);
            }
        }
        assertEquals(List.of("deleteGroupCascade", "replaceRolePermissions"),
                owners.stream().distinct().sorted().toList(),
                "手写事务只该存在于这两个'自己就是事务边界'的方法里；"
                        + "其它方法在事务内被调用时必须复用调用方的连接");
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

    // ==================== 5. 权限语义（真跑 SQLite） ====================

    @Test
    @DisplayName("语义: 角色层级数值与默认组不变（SUPER_ADMIN 100 / ADMIN 80 / MODERATOR 50 / PLAYER 10 / GUEST 0）")
    void roleHierarchyValuesAreUnchanged() {
        Map<String, Integer> hierarchy = PermissionService.getRoleHierarchy();
        assertEquals(100, hierarchy.get("SUPER_ADMIN").intValue());
        assertEquals(80, hierarchy.get("ADMIN").intValue());
        assertEquals(50, hierarchy.get("MODERATOR").intValue());
        assertEquals(10, hierarchy.get("PLAYER").intValue());
        assertEquals(0, hierarchy.get("GUEST").intValue());
        assertEquals("PLAYER", PermissionService.getDefaultRoleName(),
                "默认组必须仍是等级 <= 10 的第一个（PLAYER）");

        // 顺序仍是"等级降序"（用相对位置断言，避免受别的测试临时建的组影响）
        List<String> names = new ArrayList<>(hierarchy.keySet());
        assertTrue(names.indexOf("SUPER_ADMIN") < names.indexOf("ADMIN"), "SUPER_ADMIN 应排在 ADMIN 之前");
        assertTrue(names.indexOf("ADMIN") < names.indexOf("MODERATOR"), "ADMIN 应排在 MODERATOR 之前");
        assertTrue(names.indexOf("MODERATOR") < names.indexOf("PLAYER"), "MODERATOR 应排在 PLAYER 之前");
        assertTrue(names.indexOf("PLAYER") < names.indexOf("GUEST"), "PLAYER 应排在 GUEST 之前");
    }

    @Test
    @DisplayName("语义: 组权限继承不变（MODERATOR ⊇ PLAYER、ADMIN 缺 4 个最高权限、SUPER_ADMIN 全都有）")
    void groupPermissionInheritanceIsUnchanged() {
        Set<String> player = PermissionService.getGroupPermissionCodes("PLAYER");
        Set<String> moderator = PermissionService.getGroupPermissionCodes("MODERATOR");
        Set<String> admin = PermissionService.getGroupPermissionCodes("ADMIN");
        Set<String> superAdmin = PermissionService.getGroupPermissionCodes("SUPER_ADMIN");
        Set<String> guest = PermissionService.getGroupPermissionCodes("GUEST");

        assertTrue(moderator.containsAll(player), "MODERATOR 必须继承 PLAYER 的全部权限");
        assertTrue(moderator.contains("admin.status"), "MODERATOR 应额外拿到管理查看权限");
        assertFalse(player.contains("admin.status"), "PLAYER 不该有管理权限");
        assertFalse(player.contains("game.item.add"), "发放物品属于管理员操作，PLAYER 不可自刷");
        assertFalse(guest.contains("game.player.info"), "GUEST 只有基本查询权限");

        for (PermissionCode pc : PermissionCode.values()) {
            assertTrue(superAdmin.contains(pc.getCode()),
                    "SUPER_ADMIN 必须拥有全部内置权限，缺: " + pc.getCode());
        }
        for (String highest : List.of("admin.roles.manage", "admin.users.manage",
                "admin.database.clear_players", "admin.database.reset_all")) {
            assertFalse(admin.contains(highest), "ADMIN 不该拥有最高管理权限: " + highest);
        }
        assertTrue(admin.contains("admin.status"), "ADMIN 仍应拥有普通管理权限");
    }

    @Test
    @DisplayName("行为: 授予角色后 hasPermission 通过，撤销后立刻失败")
    void grantingAndRevokingRoleChangesEffectivePermission() throws SQLException {
        long userId = newUser();
        PermissionService.invalidatePermissionCache();

        // MODERATOR 拥有 admin.status（管理查看权限），而 PLAYER / 无角色时没有 ——
        // 用一个"角色确实会带来"的权限做断言，否则授予/撤销根本看不出效果。
        assertFalse(PermissionService.hasPermission(userId, "admin.status"),
                "前置条件：还没分配 MODERATOR，不该有这个权限");

        PermissionService.assignRole(userId, "MODERATOR");
        assertTrue(PermissionService.getUserRoles(userId).contains("MODERATOR"), "user_roles 应真的写入");
        assertTrue(PermissionService.hasPermission(userId, "admin.status"),
                "刚授予的角色必须立刻生效（缓存被失效）；若失败说明缓存或 SQL 写坏了");

        PermissionService.removeRole(userId, "MODERATOR");
        assertTrue(PermissionService.getUserRoles(userId).isEmpty(), "user_roles 应真的删除");
        assertFalse(PermissionService.hasPermission(userId, "admin.status"),
                "刚撤销的角色必须立刻失效；缓存若还在，被撤权的人还能继续操作");

        // 直接授予的权限走的是另一条路径（user_permissions）
        PermissionService.assignPermission(userId, "admin.users.manage");
        assertTrue(PermissionService.hasPermission(userId, "admin.users.manage"),
                "直接授予的权限应立刻生效");
        assertTrue(PermissionService.getUserDirectPermissions(userId).contains(PermissionCode.ADMIN_USERS_MANAGE),
                "getUserDirectPermissions 应读到刚授予的内置权限");
        PermissionService.removePermission(userId, "admin.users.manage");
        assertFalse(PermissionService.hasPermission(userId, "admin.users.manage"), "直接撤销必须立刻失效");
    }

    @Test
    @DisplayName("行为: 最高角色 / 最高等级按层级判定（多角色取最高）")
    void highestRoleRespectsHierarchy() throws SQLException {
        long userId = newUser();
        PermissionService.assignRole(userId, "PLAYER");
        PermissionService.assignRole(userId, "MODERATOR");
        PermissionService.assignRole(userId, "ADMIN");

        assertEquals("ADMIN", PermissionService.getHighestRole(userId), "多角色时最高角色应是 ADMIN");
        assertEquals(80, PermissionService.getHighestRoleLevel(userId));
        assertEquals(3, PermissionService.getUserRoles(userId).size(), "三个角色都应留在 user_roles 里");
    }

    @Test
    @DisplayName("缓存: 库被绕过服务层改动后，缓存失效就能读到新权限（TTL 内仍命中旧值）")
    void cacheInvalidationPicksUpOutOfBandChanges() throws SQLException {
        long userId = newUser();
        PermissionService.invalidatePermissionCache();
        assertFalse(PermissionService.hasPermission(userId, "admin.status"), "前置条件：还没有这个权限");

        // 绕过服务层直接写库（模拟"别的地方改了库"这种非正常路径）
        DAO.insertUserPermissionIfAbsent(userId, "admin.status");

        int ttl = AppConfig.getInt("permission.cache_ttl_ms", 5000);
        if (ttl > 0) {
            assertFalse(PermissionService.hasPermission(userId, "admin.status"),
                    "TTL 内应仍然命中旧缓存（缓存本来就是为了省掉这 2 条 SQL）");
        }
        PermissionService.invalidatePermissionCache();
        assertTrue(PermissionService.hasPermission(userId, "admin.status"),
                "缓存失效后必须能读到库里新写的权限");
    }

    @Test
    @DisplayName("行为: DAO 原样返回库里的行，过滤规则仍由服务层决定（野码不算数）")
    void daoReturnsRawRowsAndServiceAppliesTheFilter() throws SQLException {
        long userId = newUser();
        String bogus = "totally.bogus.code." + UUID.randomUUID();

        DAO.insertUserPermissionIfAbsent(userId, bogus);
        assertTrue(DAO.findDirectPermissionCodes(userId, "查询用户权限失败").contains(bogus),
                "DAO 必须原样返回库里的行（过滤是权限语义，不属于存取逻辑）");

        PermissionService.invalidatePermissionCache();
        assertFalse(PermissionService.getUserPluginPermissionCodes(userId).contains(bogus),
                "未注册的码不该被当成插件权限");
        assertFalse(PermissionService.hasPermission(userId, bogus),
                "手工塞进库的野码不该被当成有效权限（原有语义保持不变）");
    }

    @Test
    @DisplayName("行为: 权限组 CRUD 经 DAO 仍然正确（增删改、整组覆盖、内置组保护、不存在时报错）")
    void groupCrudWorksThroughTheDao() {
        String group = "TEST_GROUP_" + SEQ.incrementAndGet();
        PermissionService.createGroup(group, "测试组", 30);
        try {
            assertTrue(PermissionService.getRoleNames().contains(group), "新建的组应立刻出现在缓存里");
            assertEquals(30, PermissionService.getRoleHierarchy().get(group).intValue());
            assertEquals("测试组", PermissionService.getGroupDisplayName(group));

            PermissionService.updateGroup(group, "测试组改名", 35);
            assertEquals("测试组改名", PermissionService.getGroupDisplayName(group), "显示名应更新");
            assertEquals(35, PermissionService.getRoleHierarchy().get(group).intValue(), "等级应更新");

            PermissionService.addGroupPermission(group, "admin.status");
            assertTrue(PermissionService.getGroupPermissionCodes(group).contains("admin.status"),
                    "加组权限应立刻进缓存");
            PermissionService.removeGroupPermission(group, "admin.status");
            assertFalse(PermissionService.getGroupPermissionCodes(group).contains("admin.status"),
                    "移除组权限应立刻从缓存消失");

            Set<String> codes = new LinkedHashSet<>(List.of("game.player.info", "admin.status"));
            PermissionService.setGroupPermissions(group, codes);
            assertEquals(codes, new HashSet<>(PermissionService.getGroupPermissionCodes(group)),
                    "整组覆盖必须恰好等于给定集合（先清空、再插入，且是一个事务）");

            PermissionService.setGroupPermissions(group, Set.of());
            assertTrue(PermissionService.getGroupPermissionCodes(group).isEmpty(),
                    "空集合 = 清空该组的全部权限");
        } finally {
            PermissionService.deleteGroup(group);
            PermissionService.refreshGroupCache();
        }
        assertFalse(PermissionService.getRoleNames().contains(group), "删除后组必须从缓存消失");
        assertTrue(PermissionService.getGroupPermissionCodes(group).isEmpty(), "删除后组权限映射应清空");

        IllegalArgumentException builtin = assertThrows(IllegalArgumentException.class,
                () -> PermissionService.deleteGroup("PLAYER"));
        assertTrue(builtin.getMessage().contains("系统内置权限组不可删除"),
                "内置组必须拒删，实际: " + builtin.getMessage());

        // 事务内抛出的 IllegalArgumentException 必须原样冒出来（不能被包成 RuntimeException）
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> PermissionService.deleteGroup("NO_SUCH_GROUP_" + SEQ.incrementAndGet()));
        assertTrue(missing.getMessage().contains("权限组不存在"),
                "组不存在时应抛 IllegalArgumentException，实际: " + missing.getMessage());
    }

    @Test
    @DisplayName("行为: 用户列表仍按 LEFT JOIN 聚合（多角色合并、无角色也出现、带直接权限）")
    @SuppressWarnings("unchecked")
    void allUsersWithRolesStillAggregatesLeftJoinRows() throws SQLException {
        long withRoles = newUser();
        PermissionService.assignRole(withRoles, "GUEST");
        PermissionService.assignRole(withRoles, "MODERATOR");
        PermissionService.assignPermission(withRoles, "admin.status");

        long withoutRoles = newUser();

        List<Map<String, Object>> users = PermissionService.getAllUsersWithRoles();

        Map<String, Object> mine = findByUserId(users, withRoles);
        assertNotNull(mine, "刚建的账号必须出现在用户列表里");
        List<String> roles = (List<String>) mine.get("roles");
        assertTrue(roles.contains("GUEST") && roles.contains("MODERATOR"),
                "同一账号的多行必须合并成一个账号，实际: " + roles);
        List<String> direct = (List<String>) mine.get("directPermissions");
        assertTrue(direct.contains("admin.status"), "直接授予的权限码应出现在 directPermissions 里");

        Map<String, Object> roleless = findByUserId(users, withoutRoles);
        assertNotNull(roleless, "LEFT JOIN 必须让没有任何角色的账号也出现在列表里");
        assertTrue(((List<String>) roleless.get("roles")).isEmpty(), "没有角色的账号 roles 应是空数组");
        assertEquals(withoutRoles, ((Number) roleless.get("id")).longValue());
    }

    // ==================== 6. 事务内用 Connection 重载 ====================

    @Test
    @DisplayName("事务: 事务内用 Connection 重载写入 —— 回滚就真的回滚（证明用的是同一条连接）")
    void daoConnectionOverloadsJoinTheCallerTransaction() throws SQLException {
        long userId = newUser();
        PermissionService.invalidatePermissionCache();

        RuntimeException error = assertThrows(RuntimeException.class, () ->
                DatabaseManager.runTransaction(conn -> {
                    DAO.insertUserRoleIfAbsent(conn, userId, "MODERATOR");
                    assertTrue(DAO.findRoleNamesByUserId(conn, userId).contains("MODERATOR"),
                            "同一事务内必须立刻读到自己刚写的角色（说明用的是同一条连接）");
                    throw new IllegalStateException("故意回滚");
                }));
        assertTrue(String.valueOf(error.getMessage()).contains("故意回滚"),
                "事务里的异常必须原样冒出来，实际: " + error.getMessage());

        assertTrue(DAO.findRoleNamesByUserId(userId).isEmpty(),
                "事务已回滚，这个角色不该留在库里 —— 若留下了，说明 DAO 在事务里另开了一条"
                        + "（autocommit）连接，SQLite 下正是 SQLITE_BUSY_SNAPSHOT 的来源");
    }

    @Test
    @DisplayName("事务: 有并发写在跑时，事务内调 Connection 重载也不会抛 SQLITE_BUSY_SNAPSHOT")
    void transactionWithConcurrentWriterDoesNotRaiseBusySnapshot() throws Exception {
        long userId = newUser();
        long otherUserId = newUser();

        CountDownLatch txHasRead = new CountDownLatch(1);
        CountDownLatch otherCommitted = new CountDownLatch(1);
        AtomicBoolean interfered = new AtomicBoolean(false);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();

        Thread writer = new Thread(() -> {
            try {
                txHasRead.await();
                // 另一个连接、另一行、同一张表 —— 它一提交，事务里已有的读快照就过期了
                DAO.insertUserPermissionIfAbsent(otherUserId, "admin.status");
            } catch (Throwable t) {
                writerFailure.set(t);
            } finally {
                otherCommitted.countDown();
            }
        }, "permission-dao-concurrent-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            for (int round = 0; round < 3; round++) {
                int expected = round; // 循环变量不是 effectively final，lambda 里只能捕获这个副本
                DatabaseManager.runTransaction(conn -> {
                    // 先读（建立快照）
                    List<String> before = DAO.findDirectPermissionCodes(conn, userId);
                    assertNotNull(before, "事务内必须能读到直接权限列表");
                    if (interfered.compareAndSet(false, true)) {
                        // 制造"读后写 + 期间别的连接提交了写"这一形状：
                        // 第一轮的 INSERT 会撞上 SQLITE_BUSY_SNAPSHOT，重试由 runTransaction 负责，
                        // 它绝不该把这个异常冒给调用方。
                        txHasRead.countDown();
                        assertTrue(otherCommitted.await(10, TimeUnit.SECONDS), "并发写者应在 10 秒内提交");
                    }
                    DAO.insertUserPermissionIfAbsent(conn, userId, "admin.status");
                    assertTrue(DAO.findDirectPermissionCodes(conn, userId).contains("admin.status"),
                            "第 " + expected + " 轮：同一事务内必须看到自己的写入");
                    return null;
                });
            }
        } finally {
            writer.join(10_000L);
        }

        assertNull(writerFailure.get(), "并发写线程不该失败: " + writerFailure.get());
        assertTrue(DAO.findDirectPermissionCodes(userId, "查询用户权限失败").contains("admin.status"),
                "3 轮事务的写入必须落地");
        assertTrue(DAO.findDirectPermissionCodes(otherUserId, "查询用户权限失败").contains("admin.status"),
                "并发写者的那笔写入也必须落地");
    }

    // ==================== 夹具 ====================

    private static long newUser() throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "permdao_" + n + "_" + UUID.randomUUID().toString().substring(0, 6);
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败: " + username);
                    return rs.getLong(1);
                }
            }
        }
    }

    private static Map<String, Object> findByUserId(List<Map<String, Object>> users, long userId) {
        for (Map<String, Object> user : users) {
            Object id = user.get("id");
            if (id instanceof Number number && number.longValue() == userId) {
                return user;
            }
        }
        return null;
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
