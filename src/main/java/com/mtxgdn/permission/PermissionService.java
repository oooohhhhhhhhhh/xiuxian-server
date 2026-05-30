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

public class PermissionService {

    private static final int DEFAULT_ROLE_LEVEL = AppConfig.getInt("permission.default.role.level", 10);
    private static final boolean IS_SQLITE = DatabaseManager.isSqlite();
    private static final String INSERT_OR_IGNORE = IS_SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";

    private static final Map<String, Integer> ROLE_HIERARCHY = new LinkedHashMap<>();
    static {
        ROLE_HIERARCHY.put("SUPER_ADMIN", 100);
        ROLE_HIERARCHY.put("ADMIN", 80);
        ROLE_HIERARCHY.put("MODERATOR", 50);
        ROLE_HIERARCHY.put("PLAYER", 10);
        ROLE_HIERARCHY.put("GUEST", 0);
    }

    private static final Map<String, Set<PermissionCode>> ROLE_PERMISSIONS = new HashMap<>();
    static {
        Set<PermissionCode> allPermissions = EnumSet.allOf(PermissionCode.class);

        Set<PermissionCode> adminPermissions = EnumSet.allOf(PermissionCode.class);
        adminPermissions.remove(PermissionCode.ADMIN_ROLES_MANAGE);
        adminPermissions.remove(PermissionCode.ADMIN_USERS_MANAGE);
        adminPermissions.remove(PermissionCode.ADMIN_DATABASE_CLEAR_PLAYERS);
        adminPermissions.remove(PermissionCode.ADMIN_DATABASE_RESET_ALL);

        Set<PermissionCode> moderatorPermissions = EnumSet.of(
                PermissionCode.GAME_PLAYER_INFO, PermissionCode.GAME_PLAYER_CREATE,
                PermissionCode.GAME_CULTIVATE, PermissionCode.GAME_EXPLORE,
                PermissionCode.GAME_SECRET_REALM, PermissionCode.GAME_REALM_BREAKTHROUGH,
                PermissionCode.GAME_ITEM_USE, PermissionCode.GAME_INVENTORY_VIEW,
                PermissionCode.GAME_ITEM_REGISTRY, PermissionCode.GAME_REALM_CONFIG,
                PermissionCode.GAME_SKILL_LEARN, PermissionCode.GAME_ITEM_ADD, PermissionCode.GAME_PVP_CHALLENGE,
                PermissionCode.QQ_BIND, PermissionCode.QQ_UNBIND,
                PermissionCode.QQ_COMMAND_BASIC, PermissionCode.QQ_COMMAND_GAME,
                PermissionCode.QQ_COMMAND_ADMIN,
                PermissionCode.ADMIN_LOGIN, PermissionCode.ADMIN_STATUS,
                PermissionCode.ADMIN_LOGS_VIEW
        );

        Set<PermissionCode> playerPermissions = EnumSet.of(
                PermissionCode.GAME_PLAYER_INFO, PermissionCode.GAME_PLAYER_CREATE,
                PermissionCode.GAME_CULTIVATE, PermissionCode.GAME_EXPLORE,
                PermissionCode.GAME_SECRET_REALM, PermissionCode.GAME_REALM_BREAKTHROUGH,
                PermissionCode.GAME_ITEM_USE, PermissionCode.GAME_INVENTORY_VIEW,
                PermissionCode.GAME_ITEM_REGISTRY, PermissionCode.GAME_REALM_CONFIG,
                PermissionCode.GAME_SKILL_LEARN, PermissionCode.GAME_PVP_CHALLENGE,
                PermissionCode.QQ_BIND, PermissionCode.QQ_UNBIND,
                PermissionCode.QQ_COMMAND_BASIC, PermissionCode.QQ_COMMAND_GAME
        );

        Set<PermissionCode> guestPermissions = EnumSet.of(
                PermissionCode.QQ_COMMAND_BASIC
        );

        ROLE_PERMISSIONS.put("SUPER_ADMIN", allPermissions);
        ROLE_PERMISSIONS.put("ADMIN", adminPermissions);
        ROLE_PERMISSIONS.put("MODERATOR", moderatorPermissions);
        ROLE_PERMISSIONS.put("PLAYER", playerPermissions);
        ROLE_PERMISSIONS.put("GUEST", guestPermissions);
    }

    public static Map<String, Integer> getRoleHierarchy() {
        return ROLE_HIERARCHY;
    }

    public static Set<String> getRoleNames() {
        return ROLE_HIERARCHY.keySet();
    }

    public static String getDefaultRoleName() {
        for (Map.Entry<String, Integer> entry : ROLE_HIERARCHY.entrySet()) {
            if (entry.getValue() <= DEFAULT_ROLE_LEVEL) {
                return entry.getKey();
            }
        }
        return "PLAYER";
    }

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
        List<String> roles = getUserRoles(userId);
        String highest = null;
        int highestLevel = -1;
        for (String role : roles) {
            Integer level = ROLE_HIERARCHY.get(role);
            if (level != null && level > highestLevel) {
                highestLevel = level;
                highest = role;
            }
        }
        return highest;
    }

    public static int getHighestRoleLevel(long userId) {
        String role = getHighestRole(userId);
        if (role == null) {
            String defaultRole = getDefaultRoleName();
            return ROLE_HIERARCHY.getOrDefault(defaultRole, 0);
        }
        return ROLE_HIERARCHY.getOrDefault(role, 0);
    }

    public static Set<PermissionCode> getUserPermissions(long userId) {
        List<String> roles = getUserRoles(userId);
        if (roles.isEmpty()) {
            return ROLE_PERMISSIONS.getOrDefault(getDefaultRoleName(), Collections.emptySet());
        }

        Set<PermissionCode> permissions = EnumSet.noneOf(PermissionCode.class);
        for (String role : roles) {
            Set<PermissionCode> rolePerms = ROLE_PERMISSIONS.get(role);
            if (rolePerms != null) {
                permissions.addAll(rolePerms);
            }
        }
        return permissions;
    }

    public static boolean hasPermission(long userId, String permissionCode) {
        Set<PermissionCode> permissions = getUserPermissions(userId);
        for (PermissionCode pc : permissions) {
            if (pc.getCode().equals(permissionCode)) {
                return true;
            }
        }
        return false;
    }

    public static void assignRole(long userId, String roleName) {
        if (!ROLE_HIERARCHY.containsKey(roleName)) {
            throw new IllegalArgumentException("未知角色: " + roleName);
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
        if (getHighestRoleLevel(userId) <= ROLE_HIERARCHY.getOrDefault(roleName, 0)) {
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

    public static Set<PermissionCode> getRoleDefaultPermissions(String roleName) {
        return ROLE_PERMISSIONS.getOrDefault(roleName, Collections.emptySet());
    }

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
            for (Map<String, Object> entry : userMap.values()) {
                result.add(entry);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户角色列表失败", e);
        }
        return result;
    }

    public static void initDefaultData() {
        for (Map.Entry<String, Integer> entry : ROLE_HIERARCHY.entrySet()) {
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

        for (Map.Entry<String, Set<PermissionCode>> entry : ROLE_PERMISSIONS.entrySet()) {
            for (PermissionCode pc : entry.getValue()) {
                String sql = INSERT_OR_IGNORE + " INTO role_permissions (role_name, permission_code) VALUES (?, ?)";
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, entry.getKey());
                    ps.setString(2, pc.getCode());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    throw new RuntimeException("初始化角色权限数据失败", e);
                }
            }
        }
    }
}
