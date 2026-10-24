package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限模块的五张表（{@code roles} / {@code role_permissions} / {@code user_roles} /
 * {@code user_permissions} / {@code permissions}）上的全部读写，外加一条
 * {@code users LEFT JOIN user_roles} 的联查。
 *
 * <p><b>为什么要有这个类</b>：这些 SQL 原来散在
 * {@code com.mtxgdn.permission.PermissionService} 里，一共 23 处
 * {@code DatabaseManager.getConnection()}，业务方法里混着
 * {@code PreparedStatement} / {@code ResultSet} / {@code SQLException} /
 * {@code setAutoCommit}。后果不是"文件长了 985 行"，而是：</p>
 * <ul>
 *   <li>权限判定与权限落库混在同一份代码里 —— 而这是安全代码，
 *       改 SQL 与改判定规则应该能分别 review；</li>
 *   <li>"哪条语句在事务里、哪些不在"看不出来：同一个类里既有手写事务
 *       （{@code deleteGroup} / {@code setGroupPermissions}）又有随手开的短连接，
 *       谁在事务里全靠人眼判断；</li>
 *   <li>权限组的写操作有 4 处（初始化、注册插件权限、加组权限、整组覆盖）
 *       各自抄了一遍 {@code INSERT OR IGNORE ...} 与参数绑定，
 *       方言前缀 {@code INSERT OR IGNORE} / {@code INSERT IGNORE} 也得跟着抄。</li>
 * </ul>
 *
 * <p><b>切法</b>：按<b>业务意图</b>切，不按"一条 SQL 一个方法"切。
 * 例如"用户直接授予的权限码"在原实现里被三条 SQL 分别读了三遍
 * （一次过滤内置枚举、一次过滤插件权限、一次两者都要），
 * 这里只有 {@link #findDirectPermissionCodes} 一个意图 ——
 * <b>过滤规则属于权限语义，留在服务层</b>，DAO 只负责把行取回来。</p>
 *
 * <p><b>连接与事务</b>：除两个"自己就是事务边界"的方法（
 * {@link #deleteGroupCascade} / {@link #replaceRolePermissions}）之外，
 * 每个方法都有一对重载 ——</p>
 * <ul>
 *   <li>{@code xxx(...)}：自己从 {@link DatabaseManager} 取一个短连接，用完即关，
 *       失败按原实现的文案包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：<b>只用调用方传进来的连接</b>，
 *       绝不自己开连接、也绝不碰 {@code setAutoCommit}，失败直接抛
 *       {@link SQLException} 交给调用方。事务内必须走这一版：SQLite 的
 *       {@code BEGIN DEFERRED} 事务里若先读后写、期间又用<b>另一个连接</b>写同一张表，
 *       会直接抛 {@code SQLITE_BUSY_SNAPSHOT}（该错误不受 {@code busy_timeout} 约束），
 *       或者读到旧快照。</li>
 * </ul>
 *
 * <p><b>SQL 逐字保留</b>：表名、列名、条件、{@code ORDER BY}、{@code UNIQUE}、
 * 参数顺序全部原样搬过来，一条没改、没合并、没"优化"。
 * 回归测试：{@code PermissionDaoExtractionTest}（含 SQL 守恒比对）。</p>
 */
public class PermissionDAO {

    // ==================== 结果载体 ====================

    /** {@code roles} 表的一行（一个权限组）。 */
    public record RoleRow(String name, String displayName, int level) {
    }

    /**
     * 权限组目录：按 {@code level DESC} 排好的组定义 + 组名 → 权限码集合。
     *
     * <p>两个查询共用<b>同一条连接</b>读出来，因为它们描述的是同一份"权限组目录"；
     * 拆成两次调用会多借还一次连接，而服务层的 {@code refreshGroupCache}
     * 也必须拿到同一时刻的一致视图。</p>
     */
    public record GroupCatalog(List<RoleRow> roles, Map<String, Set<String>> rolePermissions) {
    }

    /**
     * {@code users LEFT JOIN user_roles} 的一行。
     *
     * <p>{@code roleName} 可能为 {@code null}：{@code LEFT JOIN} 保证"没有任何角色"的
     * 账号也会出现一行，这种账号在原实现里同样会出现在管理后台的用户列表里
     * （{@code roles} 是空数组，而不是整个账号消失）。</p>
     */
    public record UserRoleRow(long userId, String username, String roleName) {
    }

    // ==================== 方言前缀 ====================

    /**
     * 方言相关的"已存在就跳过"前缀（SQLite 是 {@code INSERT OR IGNORE}，
     * MySQL 是 {@code INSERT IGNORE}）。
     *
     * <p>刻意每次调用时判断方言，而不是放进 {@code static final} 常量：静态常量会在
     * 类初始化那一刻定死，而本类有可能在 {@link DatabaseManager#init} 之前就被加载
     * （方言尚未确定），那时算出来的前缀就是 MySQL 的写法，
     * 之后在 SQLite 上执行会直接报语法错误。原实现把这个值放在服务类的静态字段里，
     * 是因为它一定在数据库初始化之后才被加载；搬到 DAO 后不能再依赖那个加载顺序。</p>
     */
    private static String insertIgnore() {
        return DatabaseManager.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE";
    }

    // ==================== 1. 读取：权限组目录 ====================

    /** 读取权限组目录（组定义 + 组→权限映射）；失败包成 {@link RuntimeException}。 */
    public GroupCatalog loadGroupCatalog() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return loadGroupCatalog(conn);
        } catch (SQLException e) {
            throw new RuntimeException("从DB加载权限组失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接，绝不自己开连接。 */
    public GroupCatalog loadGroupCatalog(Connection conn) throws SQLException {
        List<RoleRow> roles = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, display_name, level FROM roles ORDER BY level DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roles.add(new RoleRow(rs.getString("name"), rs.getString("display_name"), rs.getInt("level")));
            }
        }

        Map<String, Set<String>> rolePermissions = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT role_name, permission_code FROM role_permissions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rolePermissions
                        .computeIfAbsent(rs.getString("role_name"), key -> new LinkedHashSet<>())
                        .add(rs.getString("permission_code"));
            }
        }
        return new GroupCatalog(roles, rolePermissions);
    }

    // ==================== 2. 读取：用户 → 角色 / 直接权限 ====================

    /** 该用户拥有的权限组名称（{@code user_roles}）；没有角色时返回空列表。 */
    public List<String> findRoleNamesByUserId(long userId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findRoleNamesByUserId(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException("查询用户角色失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<String> findRoleNamesByUserId(Connection conn, long userId) throws SQLException {
        List<String> roles = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT role_name FROM user_roles WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString("role_name"));
                }
            }
        }
        return roles;
    }

    /**
     * 该用户<b>直接</b>被授予的全部权限码（{@code user_permissions}），原样返回、不做过滤。
     *
     * <p>调用方有三个，各有各的过滤口径（只看内置枚举 / 只看插件权限 / 两者都算），
     * 所以"哪些码算数"这件事刻意留在服务层：那是权限语义，不是存取逻辑。
     * 三个调用点的失败文案也不同（"查询用户单独权限失败" / "查询用户插件权限失败" /
     * "查询用户权限失败"），因此失败文案由调用方传入，异常里逐字保持原样。</p>
     *
     * @param failureMessage 失败时抛出的异常文案（调用方原样沿用重构前的文案）
     */
    public List<String> findDirectPermissionCodes(long userId, String failureMessage) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findDirectPermissionCodes(conn, userId);
        } catch (SQLException e) {
            throw new RuntimeException(failureMessage, e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<String> findDirectPermissionCodes(Connection conn, long userId) throws SQLException {
        List<String> codes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT permission_code FROM user_permissions WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codes.add(rs.getString("permission_code"));
                }
            }
        }
        return codes;
    }

    /**
     * 权限码是否已在 {@code permissions} 表里登记过。
     *
     * <p>只回答"库里有没有这一行"，不代表"这个码有效" —— 有效性还包含内置枚举与
     * 插件注册两条路径，那是服务层的判定。</p>
     */
    public boolean existsPermissionCode(String code) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return existsPermissionCode(conn, code);
        } catch (SQLException e) {
            throw new RuntimeException("查询权限码失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public boolean existsPermissionCode(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM permissions WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 全量读取"账号 → 角色"的联表结果（管理后台的用户列表用）。
     *
     * <p>{@code LEFT JOIN} 与 {@code ORDER BY u.id} 都是语义的一部分：
     * 没有任何角色的账号必须照样出现（{@code roleName} 为 {@code null}），
     * 列表顺序按账号 id 升序。</p>
     */
    public List<UserRoleRow> findAllUserRoleRows() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllUserRoleRows(conn);
        } catch (SQLException e) {
            throw new RuntimeException("查询用户角色列表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public List<UserRoleRow> findAllUserRoleRows(Connection conn) throws SQLException {
        List<UserRoleRow> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT u.id, u.username, ur.role_name FROM users u LEFT JOIN user_roles ur ON u.id = ur.user_id ORDER BY u.id")) {
            while (rs.next()) {
                rows.add(new UserRoleRow(rs.getLong("id"), rs.getString("username"), rs.getString("role_name")));
            }
        }
        return rows;
    }

    // ==================== 3. 写入：权限组定义（roles） ====================

    /** 初始化用：组不存在时插入（已存在则静默跳过）。 */
    public void insertRoleIfAbsent(String name, int level, String displayName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertRoleIfAbsent(conn, name, level, displayName);
        } catch (SQLException e) {
            throw new RuntimeException("初始化角色数据失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertRoleIfAbsent(Connection conn, String name, int level, String displayName) throws SQLException {
        String sql = insertIgnore() + " INTO roles (name, level, display_name) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, level);
            ps.setString(3, displayName);
            ps.executeUpdate();
        }
    }

    /** 新建一个权限组（重名时由 {@code UNIQUE(name)} 约束报错，不做静默忽略）。 */
    public void createRole(String name, String displayName, int level) {
        try (Connection conn = DatabaseManager.getConnection()) {
            createRole(conn, name, displayName, level);
        } catch (SQLException e) {
            throw new RuntimeException("创建权限组失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void createRole(Connection conn, String name, String displayName, int level) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO roles (name, display_name, level) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, displayName);
            ps.setInt(3, level);
            ps.executeUpdate();
        }
    }

    /** 更新权限组的显示名与等级。 */
    public void updateRole(String name, String displayName, int level) {
        try (Connection conn = DatabaseManager.getConnection()) {
            updateRole(conn, name, displayName, level);
        } catch (SQLException e) {
            throw new RuntimeException("更新权限组失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void updateRole(Connection conn, String name, String displayName, int level) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE roles SET display_name = ?, level = ? WHERE name = ?")) {
            ps.setString(1, displayName);
            ps.setInt(2, level);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    /**
     * 删除权限组：先删组权限映射、再删用户关联、最后删组定义，整笔一个事务。
     *
     * <p><b>为什么事务开在这里而不是服务层</b>：三步必须原子（否则会出现
     * "组没了但 user_roles 还挂着一个不存在的组名"），而且顺序固定。
     * 事务开在 DAO 里，服务层就不用碰 {@code setAutoCommit / commit / rollback}，
     * 也不可能出现"事务里又开一个连接"。</p>
     *
     * <p>事务控制刻意保持手写（{@code setAutoCommit(false)} + 显式 rollback），
     * 而不是换成 {@link DatabaseManager#runTransaction}：后者的失败文案是
     * "事务执行失败: ..."，而本方法的公开契约是"删除权限组失败"；
     * 换成它等于改了异常行为。同理，组不存在时抛出的
     * {@link IllegalArgumentException} 必须原样冒出去（调用方靠它回 404），
     * 不能被包成 {@link RuntimeException}。</p>
     *
     * @throws IllegalArgumentException 组定义一行都没删掉（组不存在）
     */
    public void deleteGroupCascade(String name) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                deleteGroupCascade(conn, name);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("删除权限组失败", e);
        }
    }

    /**
     * 事务内版本：只使用传入的连接，提交/回滚由调用方负责。
     *
     * @throws IllegalArgumentException 组定义一行都没删掉（组不存在）
     */
    public void deleteGroupCascade(Connection conn, String name) throws SQLException {
        // 删除组权限映射
        deleteRolePermissions(conn, name);
        // 删除用户与该组的关联
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE role_name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        }
        // 删除组定义
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM roles WHERE name = ?")) {
            ps.setString(1, name);
            int deleted = ps.executeUpdate();
            if (deleted == 0) {
                throw new IllegalArgumentException("权限组不存在: " + name);
            }
        }
    }

    // ==================== 4. 写入：组 → 权限映射（role_permissions） ====================

    /**
     * 给权限组补一条权限（已存在则跳过）。
     *
     * @param failureMessage 失败时抛出的异常文案：三个调用点的文案不同
     *        （"添加权限组权限失败" / "初始化角色权限数据失败" / 刷新缓存时忽略），
     *        因此由调用方传入，异常里逐字保持原样
     */
    public void insertRolePermissionIfAbsent(String roleName, String code, String failureMessage) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertRolePermissionIfAbsent(conn, roleName, code);
        } catch (SQLException e) {
            throw new RuntimeException(failureMessage, e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertRolePermissionIfAbsent(Connection conn, String roleName, String code) throws SQLException {
        String sql = insertIgnore() + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roleName);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    /** 从权限组移除一条权限。 */
    public void deleteRolePermission(String roleName, String code) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteRolePermission(conn, roleName, code);
        } catch (SQLException e) {
            throw new RuntimeException("移除权限组权限失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void deleteRolePermission(Connection conn, String roleName, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM role_permissions WHERE role_name = ? AND permission_code = ?")) {
            ps.setString(1, roleName);
            ps.setString(2, code);
            ps.executeUpdate();
        }
    }

    /** 清空某个权限组的全部权限（事务内用，因此只提供收连接的重载）。 */
    private static void deleteRolePermissions(Connection conn, String roleName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM role_permissions WHERE role_name = ?")) {
            ps.setString(1, roleName);
            ps.executeUpdate();
        }
    }

    /**
     * 用给定权限码集合<b>整组覆盖</b>某个权限组的权限（先清空、再批量插入）。
     *
     * <p>"清空 + 插入"必须原子：中途失败留下半个权限集，等于给这个组随机增减了权限 ——
     * 在安全代码里这是最不该出现的一种中间态。因此事务开在 DAO 里，
     * 失败文案与重构前一致（"设置权限组权限失败"），
     * 不用 {@link DatabaseManager#runTransaction}（它会改写异常文案）。</p>
     */
    public void replaceRolePermissions(String groupName, Set<String> codes) {
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                replaceRolePermissions(conn, groupName, codes);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("设置权限组权限失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接，提交/回滚由调用方负责。 */
    public void replaceRolePermissions(Connection conn, String groupName, Set<String> codes) throws SQLException {
        // 先清空
        deleteRolePermissions(conn, groupName);
        // 再插入
        String insertSql = insertIgnore() + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (String code : codes) {
                ps.setString(1, groupName);
                ps.setString(2, code);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ==================== 5. 写入：权限码登记（permissions） ====================

    /**
     * 登记一个权限码（已存在则跳过）。
     *
     * @param failureMessage 失败时抛出的异常文案：注册插件权限时带权限码
     *        （"注册插件权限失败: xxx"），初始化时是"初始化权限数据失败"，
     *        因此由调用方传入
     */
    public void insertPermissionIfAbsent(String code, String name, String category, String failureMessage) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertPermissionIfAbsent(conn, code, name, category);
        } catch (SQLException e) {
            throw new RuntimeException(failureMessage, e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertPermissionIfAbsent(Connection conn, String code, String name, String category) throws SQLException {
        String sql = insertIgnore() + " INTO permissions (code, name, category) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, category);
            ps.executeUpdate();
        }
    }

    // ==================== 6. 写入：用户 → 角色（user_roles） ====================

    /** 给用户分配一个角色（已存在则跳过）。 */
    public void insertUserRoleIfAbsent(long userId, String roleName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertUserRoleIfAbsent(conn, userId, roleName);
        } catch (SQLException e) {
            throw new RuntimeException("分配角色失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertUserRoleIfAbsent(Connection conn, long userId, String roleName) throws SQLException {
        String sql = insertIgnore() + " INTO user_roles (user_id, role_name) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, roleName);
            ps.executeUpdate();
        }
    }

    /** 摘掉用户的某个角色（精确匹配 {@code user_id + role_name}，不动别的角色）。 */
    public void deleteUserRole(long userId, String roleName) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteUserRole(conn, userId, roleName);
        } catch (SQLException e) {
            throw new RuntimeException("移除角色失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void deleteUserRole(Connection conn, long userId, String roleName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_roles WHERE user_id = ? AND role_name = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, roleName);
            ps.executeUpdate();
        }
    }

    // ==================== 7. 写入：用户 → 直接权限（user_permissions） ====================

    /** 给用户单独授予一个权限码（已存在则跳过）。 */
    public void insertUserPermissionIfAbsent(long userId, String permissionCode) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertUserPermissionIfAbsent(conn, userId, permissionCode);
        } catch (SQLException e) {
            throw new RuntimeException("分配权限失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void insertUserPermissionIfAbsent(Connection conn, long userId, String permissionCode) throws SQLException {
        String sql = insertIgnore() + " INTO user_permissions (user_id, permission_code) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        }
    }

    /** 撤销用户单独被授予的一个权限码。 */
    public void deleteUserPermission(long userId, String permissionCode) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteUserPermission(conn, userId, permissionCode);
        } catch (SQLException e) {
            throw new RuntimeException("移除权限失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void deleteUserPermission(Connection conn, long userId, String permissionCode) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM user_permissions WHERE user_id = ? AND permission_code = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        }
    }

    // ==================== 8. 建表 ====================

    /**
     * 建 {@code user_permissions} 表（权限模块自己建的第五张表，不在 {@link DatabaseSchema} 里）。
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} 对已有库是空操作，因此可以每次启动都跑。</p>
     */
    public void createUserPermissionsTable() {
        try (Connection conn = DatabaseManager.getConnection()) {
            createUserPermissionsTable(conn);
        } catch (SQLException e) {
            throw new RuntimeException("创建用户权限表失败", e);
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public void createUserPermissionsTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_permissions (" +
                    "user_id INTEGER NOT NULL, " +
                    "permission_code VARCHAR(255) NOT NULL, " +
                    "UNIQUE(user_id, permission_code)" +
                    ")");
        }
    }
}
