package com.mtxgdn.permission;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.rest.AdminUserResource;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 移除角色必须真正生效，且接口要给出诚实的响应。
 *
 * <p><b>缺陷形态</b>：{@code AdminUserResource.removeRole} 与
 * {@code PermissionService.removeRole} 里都有同一道方向反了的判断：</p>
 *
 * <pre>{@code
 * int currentLevel = PermissionService.getHighestRoleLevel(userId);   // 目标用户的最高等级
 * Integer roleLevel = PermissionService.getRoleHierarchy().get(roleName);
 * if (roleLevel != null && currentLevel <= roleLevel) { ...删除... }
 * }</pre>
 *
 * <p>{@code currentLevel} 是<b>目标用户</b>的最高角色等级，必然 &ge; 他持有的任何角色等级，
 * 所以条件只在"要移除的正好是他的最高角色"时成立。管理员给一个同时持有
 * ADMIN(80) 与 MODERATOR(50) 的用户摘掉 MODERATOR：{@code 80 <= 50} 不成立，
 * 角色<b>纹丝不动</b>，而接口照样回 {@code 200 角色移除成功} —— 管理员以为改成功了。</p>
 *
 * <p>现在服务层是纯数据操作（授权交给 {@code @RequirePermission}），
 * 资源层显式检查"用户是否真有这个角色""能不能移除默认角色"，并返回 404 / 400。</p>
 */
class AdminRemoveRoleTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    @DisplayName("摘掉低于最高角色的那个角色必须真正生效")
    void removesLowerRole() throws SQLException {
        long userId = createUser("role_remove_" + SEQ.incrementAndGet());
        PermissionService.assignRole(userId, "ADMIN");       // 最高等级 80
        PermissionService.assignRole(userId, "MODERATOR");   // 50，要摘掉的是它
        assertTrue(PermissionService.getUserRoles(userId).contains("MODERATOR"), "前置条件：角色已分配");

        Response response = new AdminUserResource().removeRole(userId, "MODERATOR");

        assertEquals(200, response.getStatus());
        assertFalse(PermissionService.getUserRoles(userId).contains("MODERATOR"),
                "MODERATOR 必须被真正删除；旧实现里 currentLevel(80) <= roleLevel(50) 不成立，"
                        + "角色根本没动却回了 200");
        assertTrue(PermissionService.getUserRoles(userId).contains("ADMIN"),
                "只应移除指定的那一个角色，其它角色不受影响");
    }

    @Test
    @DisplayName("用户没有该角色时返回 404，而不是假装成功")
    void reportsNotFoundWhenRoleNotHeld() throws SQLException {
        long userId = createUser("role_missing_" + SEQ.incrementAndGet());
        PermissionService.assignRole(userId, "PLAYER");

        Response response = new AdminUserResource().removeRole(userId, "MODERATOR");

        assertEquals(404, response.getStatus(),
                "用户本来就没有 MODERATOR，应明确告知，而不是回 200 让管理员以为删掉了");
    }

    @Test
    @DisplayName("不能移除默认角色（否则该用户将没有任何角色）")
    void refusesToRemoveDefaultRole() throws SQLException {
        long userId = createUser("role_default_" + SEQ.incrementAndGet());
        String defaultRole = PermissionService.getDefaultRoleName();
        PermissionService.assignRole(userId, defaultRole);

        Response response = new AdminUserResource().removeRole(userId, defaultRole);

        assertEquals(400, response.getStatus(), "移除默认角色应被明确拒绝");
        assertTrue(PermissionService.getUserRoles(userId).contains(defaultRole),
                "默认角色必须还在");
    }

    @Test
    @DisplayName("无效角色名仍然返回 400")
    void rejectsUnknownRoleName() throws SQLException {
        long userId = createUser("role_unknown_" + SEQ.incrementAndGet());
        Response response = new AdminUserResource().removeRole(userId, "NOT_A_ROLE_" + SEQ.incrementAndGet());
        assertEquals(400, response.getStatus());
    }

    // ==================== 夹具 ====================

    private static long createUser(String username) throws SQLException {
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
                    assertTrue(rs.next(), "用户应已创建");
                    return rs.getLong(1);
                }
            }
        }
    }
}
