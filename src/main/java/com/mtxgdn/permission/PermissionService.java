package com.mtxgdn.permission;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限服务 - 管理权限组（角色）和权限的分配与校验。
 *
 * 概念说明：
 * - 权限组（PermissionGroup）：一组权限的集合，拥有名称(唯一标识)、显示名称、等级(用于排序和层级判断)。
 *   原有系统中的"角色"(role)在逻辑上等同于权限组。
 * - 权限(Permission)：细粒度的操作许可，如 "game.player.info"、"admin.users.manage"。
 *   系统内置权限由 {@link PermissionCode} 枚举定义，插件可注册自定义权限。
 * - 用户可以属于多个权限组，其有效权限 = 所有组权限的并集 + 直接分配的权限。
 */
public class PermissionService {

    private static final int DEFAULT_ROLE_LEVEL = AppConfig.getInt("permission.default.role.level", 10);
    private static final boolean IS_SQLITE = DatabaseManager.isSqlite();
    private static final String INSERT_OR_IGNORE = IS_SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";

    // ==================== 默认权限组定义（系统内置，首次初始化时写入DB） ====================

    private static final Map<String, Integer> DEFAULT_GROUP_HIERARCHY = new LinkedHashMap<>();
    static {
        DEFAULT_GROUP_HIERARCHY.put("SUPER_ADMIN", 100);
        DEFAULT_GROUP_HIERARCHY.put("ADMIN", 80);
        DEFAULT_GROUP_HIERARCHY.put("MODERATOR", 50);
        DEFAULT_GROUP_HIERARCHY.put("PLAYER", 10);
        DEFAULT_GROUP_HIERARCHY.put("GUEST", 0);
    }

    /**
     * 获取默认的权限组→权限码映射（使用字符串而非枚举，以兼容插件权限）。
     */
    private static Map<String, Set<String>> buildDefaultGroupPermissions() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> allCodes = new HashSet<>();
        for (PermissionCode pc : PermissionCode.values()) {
            allCodes.add(pc.getCode());
        }

        // PLAYER: 所有 game.* 和 qq.* 权限（不含 admin.* 和不含 qq.command.admin）
        Set<String> playerPerms = new HashSet<>();
        for (PermissionCode pc : PermissionCode.values()) {
            String code = pc.getCode();
            if ((code.startsWith("game.") || code.startsWith("qq."))
                    && !code.startsWith("admin.")
                    && !code.equals("qq.command.admin")) {
                playerPerms.add(code);
            }
        }

        // MODERATOR: PLAYER + 管理查看权限
        Set<String> moderatorPerms = new HashSet<>(playerPerms);
        moderatorPerms.add(PermissionCode.ADMIN_LOGIN.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_STATUS.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_LOGS_VIEW.getCode());
        moderatorPerms.add(PermissionCode.QQ_COMMAND_ADMIN.getCode());
        moderatorPerms.add(PermissionCode.ADMIN_BLACKLIST_VIEW.getCode());

        // ADMIN: 全部权限，但不含最高管理权限
        Set<String> adminPerms = new HashSet<>(allCodes);
        adminPerms.remove(PermissionCode.ADMIN_ROLES_MANAGE.getCode());
        adminPerms.remove(PermissionCode.ADMIN_USERS_MANAGE.getCode());
        adminPerms.remove(PermissionCode.ADMIN_DATABASE_CLEAR_PLAYERS.getCode());
        adminPerms.remove(PermissionCode.ADMIN_DATABASE_RESET_ALL.getCode());

        // GUEST: 仅基本查询
        Set<String> guestPerms = new HashSet<>();
        guestPerms.add(PermissionCode.QQ_COMMAND_BASIC.getCode());

        result.put("SUPER_ADMIN", new HashSet<>(allCodes));
        result.put("ADMIN", adminPerms);
        result.put("MODERATOR", moderatorPerms);
        result.put("PLAYER", playerPerms);
        result.put("GUEST", guestPerms);
        return result;
    }

    // ==================== 运行时缓存（从DB加载） ====================

    /** 权限组等级映射：groupName → level（从DB加载） */
    private static volatile Map<String, Integer> groupHierarchy = null;

    /** 权限组权限映射：groupName → Set&lt;permissionCode&gt;（从DB加载，字符串集合兼容插件权限） */
    private static volatile Map<String, Set<String>> groupPermissionCodes = null;

    /** 权限组显示名称映射：groupName → displayName */
    private static volatile Map<String, String> groupDisplayNames = null;

    /** 是否已初始化（调用过 initDefaultData / refreshGroupCache） */
    private static volatile boolean initialized = false;

    /**
     * 确保运行时缓存已加载。
     */
    private static synchronized void ensureLoaded() {
        if (!initialized) {
            refreshGroupCache();
            initialized = true;
        }
    }

    /**
     * 从数据库重新加载所有权限组数据（等级、显示名、权限映射）。
     * 如果数据库中没有任何权限组，则使用默认定义初始化。
     */
    public static synchronized void refreshGroupCache() {
        Map<String, Integer> hierarchy = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        Map<String, Set<String>> perms = new LinkedHashMap<>();

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            // 加载权限组基本信息
            String roleSql = "SELECT name, display_name, level FROM roles ORDER BY level DESC";
            try (ResultSet rs = stmt.executeQuery(roleSql)) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    hierarchy.put(name, rs.getInt("level"));
                    displayNames.put(name, rs.getString("display_name"));
                    perms.put(name, new HashSet<>());
                }
            }

            // 加载权限组→权限映射
            String rpSql = "SELECT role_name, permission_code FROM role_permissions";
            try (ResultSet rs = stmt.executeQuery(rpSql)) {
                while (rs.next()) {
                    String roleName = rs.getString("role_name");
                    String permCode = rs.getString("permission_code");
                    Set<String> set = perms.get(roleName);
                    if (set != null) {
                        set.add(permCode);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[PermissionService] 从DB加载权限组失败，使用默认配置: " + e.getMessage());
            // 回退到默认配置
            hierarchy = new LinkedHashMap<>(DEFAULT_GROUP_HIERARCHY);
            for (String name : hierarchy.keySet()) {
                displayNames.put(name, name);
            }
            perms = buildDefaultGroupPermissions();
        }

        // 如果数据库中没有任何权限组，使用默认配置但不写入DB（initDefaultData 会写入）
        if (hierarchy.isEmpty()) {
            hierarchy = new LinkedHashMap<>(DEFAULT_GROUP_HIERARCHY);
            for (String name : hierarchy.keySet()) {
                displayNames.put(name, name);
            }
            perms = buildDefaultGroupPermissions();
        }

        groupHierarchy = hierarchy;
        groupDisplayNames = displayNames;
        groupPermissionCodes = perms;
    }

    // ==================== 插件权限 ====================

    /** 插件注册的权限。key=权限码，value=权限信息 */
    private static final Map<String, PluginPermissionInfo> pluginPermissions = new LinkedHashMap<>();

    /** 插件权限信息。 */
    public static class PluginPermissionInfo {
        public final String code;
        public final String name;
        public final String category;
        PluginPermissionInfo(String code, String name, String category) {
            this.code = code;
            this.name = name;
            this.category = category;
        }
    }

    /**
     * 注册一个插件自定义权限。在插件 onLoad/onEnable 阶段调用。
     * 权限码会写入数据库 permissions 表，并可用于 hasPermission / assignPermission 等所有检查。
     * 注意：插件权限不自动分配给任何权限组，需管理员手动分配。
     */
    public static void registerPluginPermission(String code, String name, String category) {
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("权限码不能为空");
        }
        if (PermissionCode.fromCode(code) != null || pluginPermissions.containsKey(code)) {
            throw new IllegalArgumentException("权限码已存在: " + code);
        }
        pluginPermissions.put(code, new PluginPermissionInfo(code, name, category));
        String sql = INSERT_OR_IGNORE + " INTO permissions (code, name, category) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, category != null ? category : "插件扩展");
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("注册插件权限失败: " + code, e);
        }
    }

    /** 判断一个权限码是否为已注册的插件权限。 */
    public static boolean isPluginPermission(String code) {
        return pluginPermissions.containsKey(code);
    }

    /** 获取所有插件注册的权限。 */
    public static Map<String, PluginPermissionInfo> getPluginPermissions() {
        return Collections.unmodifiableMap(pluginPermissions);
    }

    /** 判断权限码是否有效（内置枚举、插件注册或数据库中已存在）。 */
    public static boolean isValidPermissionCode(String code) {
        if (PermissionCode.fromCode(code) != null || pluginPermissions.containsKey(code)) {
            return true;
        }
        String sql = "SELECT COUNT(*) FROM permissions WHERE code = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    // ==================== 权限组查询 ====================

    /** 获取所有权限组的等级映射（name → level，按等级降序）。 */
    public static Map<String, Integer> getRoleHierarchy() {
        ensureLoaded();
        return Collections.unmodifiableMap(groupHierarchy);
    }

    /** 获取所有权限组的名称集合。 */
    public static Set<String> getRoleNames() {
        ensureLoaded();
        return Collections.unmodifiableSet(groupHierarchy.keySet());
    }

    /** 获取权限组的显示名称。 */
    public static String getGroupDisplayName(String groupName) {
        ensureLoaded();
        return groupDisplayNames.getOrDefault(groupName, groupName);
    }

    /** 获取所有权限组信息（名称、显示名、等级、权限数量）。 */
    public static List<Map<String, Object>> getAllGroups() {
        ensureLoaded();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : groupHierarchy.entrySet()) {
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", entry.getKey());
            group.put("displayName", groupDisplayNames.getOrDefault(entry.getKey(), entry.getKey()));
            group.put("level", entry.getValue());
            Set<String> perms = groupPermissionCodes.getOrDefault(entry.getKey(), Collections.emptySet());
            group.put("permissionCount", perms.size());
            group.put("permissions", new ArrayList<>(perms));
            // 标记是否为系统内置组
            group.put("system", DEFAULT_GROUP_HIERARCHY.containsKey(entry.getKey()));
            result.add(group);
        }
        return result;
    }

    /** 获取默认权限组名称。 */
    public static String getDefaultRoleName() {
        ensureLoaded();
        for (Map.Entry<String, Integer> entry : groupHierarchy.entrySet()) {
            if (entry.getValue() <= DEFAULT_ROLE_LEVEL) {
                return entry.getKey();
            }
        }
        return "PLAYER";
    }

    // ==================== 用户角色/权限组管理 ====================

    public static List<String> getUserRoles(long userId) {
        String sql = "SELECT role_name FROM user_roles WHERE user_id = ?";
        List<String> roles = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString("role_name"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户角色失败", e);
        }
        return roles;
    }

    public static String getHighestRole(long userId) {
        ensureLoaded();
        List<String> roles = getUserRoles(userId);
        String highest = null;
        int highestLevel = -1;
        for (String role : roles) {
            Integer level = groupHierarchy.get(role);
            if (level != null && level > highestLevel) {
                highestLevel = level;
                highest = role;
            }
        }
        return highest;
    }

    public static int getHighestRoleLevel(long userId) {
        ensureLoaded();
        String role = getHighestRole(userId);
        if (role == null) {
            String defaultRole = getDefaultRoleName();
            return groupHierarchy.getOrDefault(defaultRole, 0);
        }
        return groupHierarchy.getOrDefault(role, 0);
    }

    // ==================== 权限查询 ====================

    /**
     * 获取用户单独分配的权限码（仅内置枚举类型）。
     */
    public static Set<PermissionCode> getUserDirectPermissions(long userId) {
        Set<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        String sql = "SELECT permission_code FROM user_permissions WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PermissionCode pc = PermissionCode.fromCode(rs.getString("permission_code"));
                    if (pc != null) {
                        result.add(pc);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户单独权限失败", e);
        }
        return result;
    }

    /**
     * 获取用户单独分配的插件权限码字符串集合。
     */
    public static Set<String> getUserPluginPermissionCodes(long userId) {
        Set<String> result = new HashSet<>();
        String sql = "SELECT permission_code FROM user_permissions WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("permission_code");
                    if (pluginPermissions.containsKey(code)) {
                        result.add(code);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户插件权限失败", e);
        }
        return result;
    }

    /**
     * 获取用户权限组继承的所有权限码字符串（包括内置和插件权限）。
     */
    public static Set<String> getUserGroupPermissionCodes(long userId) {
        ensureLoaded();
        Set<String> result = new HashSet<>();
        List<String> roles = getUserRoles(userId);
        if (roles.isEmpty()) {
            // 无角色时使用默认角色权限
            Set<String> defaultPerms = groupPermissionCodes.get(getDefaultRoleName());
            if (defaultPerms != null) result.addAll(defaultPerms);
        } else {
            for (String role : roles) {
                Set<String> rolePerms = groupPermissionCodes.get(role);
                if (rolePerms != null) {
                    result.addAll(rolePerms);
                }
            }
        }
        return result;
    }

    /**
     * 获取用户全部有效权限的内置枚举集合（角色继承 + 单独分配）。
     * 保持向后兼容。
     */
    public static Set<PermissionCode> getUserPermissions(long userId) {
        ensureLoaded();
        Set<PermissionCode> permissions = EnumSet.noneOf(PermissionCode.class);

        // 从权限组继承的枚举权限
        for (String code : getUserGroupPermissionCodes(userId)) {
            PermissionCode pc = PermissionCode.fromCode(code);
            if (pc != null) {
                permissions.add(pc);
            }
        }

        // 合并用户单独分配的枚举权限
        permissions.addAll(getUserDirectPermissions(userId));

        return permissions;
    }

    /**
     * 获取用户全部有效权限码（字符串，包括内置和插件权限）。
     */
    public static Set<String> getAllUserPermissionCodes(long userId) {
        Set<String> result = new HashSet<>();
        result.addAll(getUserGroupPermissionCodes(userId));
        result.addAll(getUserPluginPermissionCodes(userId));
        for (PermissionCode pc : getUserDirectPermissions(userId)) {
            result.add(pc.getCode());
        }
        return result;
    }

    /**
     * 检查用户是否拥有指定权限。
     */
    public static boolean hasPermission(long userId, String permissionCode) {
        // 先检查权限组继承（包括插件权限）
        if (getUserGroupPermissionCodes(userId).contains(permissionCode)) {
            return true;
        }
        // 再检查直接分配
        Set<PermissionCode> directPerms = getUserDirectPermissions(userId);
        for (PermissionCode pc : directPerms) {
            if (pc.getCode().equals(permissionCode)) {
                return true;
            }
        }
        return getUserPluginPermissionCodes(userId).contains(permissionCode);
    }

    // ==================== 用户角色分配 ====================

    public static void assignRole(long userId, String roleName) {
        ensureLoaded();
        if (!groupHierarchy.containsKey(roleName)) {
            throw new IllegalArgumentException("未知权限组: " + roleName);
        }

        String sql = INSERT_OR_IGNORE + " INTO user_roles (user_id, role_name) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, roleName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("分配角色失败", e);
        }
    }

    public static void removeRole(long userId, String roleName) {
        ensureLoaded();
        if (getHighestRoleLevel(userId) <= groupHierarchy.getOrDefault(roleName, 0)) {
            String sql = "DELETE FROM user_roles WHERE user_id = ? AND role_name = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setString(2, roleName);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("移除角色失败", e);
            }
        }
    }

    public static void assignDefaultRole(long userId) {
        assignRole(userId, getDefaultRoleName());
    }

    /**
     * 给用户单独分配一个权限码
     */
    public static void assignPermission(long userId, String permissionCode) {
        if (!isValidPermissionCode(permissionCode)) {
            throw new IllegalArgumentException("未知权限码: " + permissionCode);
        }
        String sql = INSERT_OR_IGNORE + " INTO user_permissions (user_id, permission_code) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("分配权限失败", e);
        }
    }

    /**
     * 移除用户单独分配的权限码
     */
    public static void removePermission(long userId, String permissionCode) {
        String sql = "DELETE FROM user_permissions WHERE user_id = ? AND permission_code = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("移除权限失败", e);
        }
    }

    /** 获取权限组的默认权限码列表（字符串集合）。 */
    public static Set<String> getGroupPermissionCodes(String groupName) {
        ensureLoaded();
        return Collections.unmodifiableSet(groupPermissionCodes.getOrDefault(groupName, Collections.emptySet()));
    }

    /** 向后兼容：获取权限组的默认权限（枚举形式）。 */
    public static Set<PermissionCode> getRoleDefaultPermissions(String roleName) {
        Set<PermissionCode> result = EnumSet.noneOf(PermissionCode.class);
        for (String code : getGroupPermissionCodes(roleName)) {
            PermissionCode pc = PermissionCode.fromCode(code);
            if (pc != null) {
                result.add(pc);
            }
        }
        return result;
    }

    // ==================== 权限组 CRUD ====================

    /**
     * 创建新的权限组。
     * @param name 权限组唯一名称
     * @param displayName 显示名称
     * @param level 等级（数值越大权限越高）
     * @throws IllegalArgumentException 如果名称已存在
     */
    public static void createGroup(String name, String displayName, int level) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        ensureLoaded();
        if (groupHierarchy.containsKey(name)) {
            throw new IllegalArgumentException("权限组已存在: " + name);
        }
        String sql = "INSERT INTO roles (name, display_name, level) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, displayName != null ? displayName : name);
            ps.setInt(3, level);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("创建权限组失败", e);
        }
        refreshGroupCache();
    }

    /**
     * 更新权限组的显示名称和等级。
     */
    public static void updateGroup(String name, String displayName, int level) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        ensureLoaded();
        if (!groupHierarchy.containsKey(name)) {
            throw new IllegalArgumentException("权限组不存在: " + name);
        }
        String sql = "UPDATE roles SET display_name = ?, level = ? WHERE name = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, displayName != null ? displayName : name);
            ps.setInt(2, level);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新权限组失败", e);
        }
        refreshGroupCache();
    }

    /**
     * 删除权限组。
     * @param name 权限组名称
     * @throws IllegalArgumentException 如果是系统内置权限组
     */
    public static void deleteGroup(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("权限组名称不能为空");
        }
        if (DEFAULT_GROUP_HIERARCHY.containsKey(name)) {
            throw new IllegalArgumentException("系统内置权限组不可删除: " + name);
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 删除组权限映射
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM role_permissions WHERE role_name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                // 删除用户与该组的关联
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM user_roles WHERE role_name = ?")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                // 删除组定义
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM roles WHERE name = ?")) {
                    ps.setString(1, name);
                    int deleted = ps.executeUpdate();
                    if (deleted == 0) {
                        throw new IllegalArgumentException("权限组不存在: " + name);
                    }
                }
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
        refreshGroupCache();
    }

    /**
     * 为权限组添加一个权限。
     */
    public static void addGroupPermission(String groupName, String permissionCode) {
        if (!isValidPermissionCode(permissionCode)) {
            throw new IllegalArgumentException("未知权限码: " + permissionCode);
        }
        ensureLoaded();
        if (!groupHierarchy.containsKey(groupName)) {
            throw new IllegalArgumentException("权限组不存在: " + groupName);
        }
        String sql = INSERT_OR_IGNORE + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupName);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("添加权限组权限失败", e);
        }
        refreshGroupCache();
    }

    /**
     * 从权限组移除一个权限。
     */
    public static void removeGroupPermission(String groupName, String permissionCode) {
        String sql = "DELETE FROM role_permissions WHERE role_name = ? AND permission_code = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupName);
            ps.setString(2, permissionCode);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("移除权限组权限失败", e);
        }
        refreshGroupCache();
    }

    /**
     * 设置权限组的完整权限列表（覆盖现有权限）。
     */
    public static void setGroupPermissions(String groupName, Set<String> permissionCodes) {
        ensureLoaded();
        if (!groupHierarchy.containsKey(groupName)) {
            throw new IllegalArgumentException("权限组不存在: " + groupName);
        }
        for (String code : permissionCodes) {
            if (!isValidPermissionCode(code)) {
                throw new IllegalArgumentException("未知权限码: " + code);
            }
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 先清空
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM role_permissions WHERE role_name = ?")) {
                    ps.setString(1, groupName);
                    ps.executeUpdate();
                }
                // 再插入
                String insertSql = INSERT_OR_IGNORE + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    for (String code : permissionCodes) {
                        ps.setString(1, groupName);
                        ps.setString(2, code);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
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
        refreshGroupCache();
    }

    // ==================== 用户列表查询 ====================

    public static List<Map<String, Object>> getAllUsersWithRoles() {
        String sql = "SELECT u.id, u.username, ur.role_name FROM users u LEFT JOIN user_roles ur ON u.id = ur.user_id ORDER BY u.id";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            Map<Long, Map<String, Object>> userMap = new LinkedHashMap<>();
            while (rs.next()) {
                long userId = rs.getLong("id");
                userMap.putIfAbsent(userId, new HashMap<>());
                Map<String, Object> userEntry = userMap.get(userId);
                userEntry.put("id", userId);
                userEntry.put("username", rs.getString("username"));
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) userEntry.computeIfAbsent("roles", k -> new ArrayList<>());
                String roleName = rs.getString("role_name");
                if (roleName != null) {
                    roles.add(roleName);
                }
            }
            for (Map.Entry<Long, Map<String, Object>> entry : userMap.entrySet()) {
                Set<PermissionCode> directPerms = getUserDirectPermissions(entry.getKey());
                List<String> permCodes = new ArrayList<>();
                for (PermissionCode pc : directPerms) {
                    permCodes.add(pc.getCode());
                }
                permCodes.addAll(getUserPluginPermissionCodes(entry.getKey()));
                entry.getValue().put("directPermissions", permCodes);
                result.add(entry.getValue());
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户角色列表失败", e);
        }
        return result;
    }

    // ==================== 初始化 ====================

    public static void initDefaultData() {
        // 创建用户单独权限表
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS user_permissions (" +
                    "user_id INTEGER NOT NULL, " +
                    "permission_code VARCHAR(255) NOT NULL, " +
                    "UNIQUE(user_id, permission_code)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("创建用户权限表失败", e);
        }

        // 初始化默认权限组
        for (Map.Entry<String, Integer> entry : DEFAULT_GROUP_HIERARCHY.entrySet()) {
            String sql = INSERT_OR_IGNORE + " INTO roles (name, level, display_name) VALUES (?, ?, ?)";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, entry.getKey());
                ps.setInt(2, entry.getValue());
                ps.setString(3, entry.getKey());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("初始化角色数据失败", e);
            }
        }

        // 初始化权限码
        for (PermissionCode pc : PermissionCode.values()) {
            String sql = INSERT_OR_IGNORE + " INTO permissions (code, name, category) VALUES (?, ?, ?)";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, pc.getCode());
                ps.setString(2, pc.getName());
                ps.setString(3, pc.getCategory());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("初始化权限数据失败", e);
            }
        }

        // 初始化权限组→权限映射
        Map<String, Set<String>> defaultPerms = buildDefaultGroupPermissions();
        for (Map.Entry<String, Set<String>> entry : defaultPerms.entrySet()) {
            for (String permCode : entry.getValue()) {
                String sql = INSERT_OR_IGNORE + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, entry.getKey());
                    ps.setString(2, permCode);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException("初始化角色权限数据失败", e);
                }
            }
        }

        // 加载到运行时缓存
        refreshGroupCache();
    }
}
