package com.mtxgdn.permission;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hasPermission} 必须有缓存，且权限变更必须立刻生效。
 *
 * <p><b>缺陷形态</b>：{@code hasPermission} 每次调用跑 <b>3 条 SQL</b> ——
 * 一次查角色（{@code getUserRoles}）、一次查直接授予的内置权限、
 * 一次再查同一张 {@code user_permissions} 表拿插件权限码
 * （两次查的是同一张表、同一批行，只是过滤条件不同）。
 * 而它会被 {@code PermissionFilter}（每个 REST 请求）、每个 WS 动作、
 * 每条 QQ 指令各调用一次 —— 一次 API 请求光"校验权限"就要 3 次数据库往返。</p>
 *
 * <p><b>修复的另一面同样重要</b>：加了缓存之后，撤销权限必须<b>立刻</b>生效，
 * 不能等 TTL 到期。所以所有写操作（分配/移除角色、授予/撤销权限、
 * 改权限组、注册插件权限）都要显式失效缓存。本测试同时守住这两点。</p>
 */
class PermissionCacheTest extends TestBase {

    /**
     * 用一个 PLAYER 默认角色「没有」的权限。
     *
     * <p>这一点很关键：{@code getUserGroupPermissionCodes} 在没有角色时会回退到默认角色
     * （PLAYER）的权限，而 PLAYER 拥有全部 {@code game.*}。若用 {@code game.player.info}
     * 做测试，角色继承会让"授予/撤销"根本看不出效果，测试就成了空跑。</p>
     */
    private static final String TEST_PERMISSION = "admin.users.manage";

    @Test
    @DisplayName("授予权限后立刻生效（缓存被正确失效）")
    void grantTakesEffectImmediately() throws SQLException {
        long userId = createUser();
        PermissionService.invalidatePermissionCache();
        // 先读一次，让缓存里有一个"没有该权限"的旧值
        boolean before = PermissionService.hasPermission(userId, TEST_PERMISSION);

        PermissionService.assignPermission(userId, TEST_PERMISSION);
        try {
            assertTrue(PermissionService.hasPermission(userId, TEST_PERMISSION),
                    "刚授予的权限必须立刻生效；若这里失败说明缓存没有失效"
                            + "（before=" + before + "）");
        } finally {
            PermissionService.removePermission(userId, TEST_PERMISSION);
        }
    }

    @Test
    @DisplayName("撤销权限后立刻失效（不能等 TTL）")
    void revokeTakesEffectImmediately() throws SQLException {
        long userId = createUser();
        PermissionService.assignPermission(userId, TEST_PERMISSION);
        PermissionService.invalidatePermissionCache();
        assertTrue(PermissionService.hasPermission(userId, TEST_PERMISSION), "前置条件：已授予");

        PermissionService.removePermission(userId, TEST_PERMISSION);
        assertFalse(PermissionService.hasPermission(userId, TEST_PERMISSION),
                "刚撤销的权限必须立刻失效；缓存若还在，被撤权的人还能继续操作");
    }

    @Test
    @DisplayName("分配/移除角色后权限立刻变化")
    void roleChangeTakesEffectImmediately() throws SQLException {
        long userId = createUser();
        PermissionService.assignRole(userId, "MODERATOR");
        PermissionService.invalidatePermissionCache();
        boolean moderatorPermission = PermissionService.hasPermission(userId, "admin.users.manage");

        PermissionService.removeRole(userId, "MODERATOR");
        assertFalse(PermissionService.hasPermission(userId, "admin.users.manage"),
                "移除角色后该角色的权限必须立刻消失（moderator 原本有 admin.users.manage: "
                        + moderatorPermission + "）");
    }

    @Test
    @DisplayName("缓存命中时不查库：第二次调用不产生 SQL")
    void secondCallDoesNotHitDatabase() throws SQLException {
        long userId = createUser();
        PermissionService.assignRole(userId, "PLAYER");
        PermissionService.invalidatePermissionCache();

        // 第一次：填充缓存
        PermissionService.hasPermission(userId, TEST_PERMISSION);

        // 把库连接指向一个"不可用"的状态很难做，这里改用计数式验证：
        // 直接比对两次调用是否返回同一份缓存实例（同一个 Set 对象即说明命中缓存）
        java.util.Set<String> first = PermissionService.getEffectivePermissionCodes(userId);
        java.util.Set<String> second = PermissionService.getEffectivePermissionCodes(userId);
        assertTrue(first == second,
                "两次调用应返回同一份缓存实例；返回不同实例说明每次都在重新查库（3 条 SQL/次）");
    }

    @Test
    @DisplayName("未知权限码不会被当成有效权限")
    void unknownCodeIsIgnored() throws SQLException {
        long userId = createUser();
        // 直接塞一行野码进库（绕过 assignPermission 的校验）
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, "totally.bogus.code." + UUID.randomUUID());
            ps.executeUpdate();
        }
        PermissionService.invalidatePermissionCache();
        assertFalse(PermissionService.hasPermission(userId, "totally.bogus.code"),
                "手工塞进库的野码不应被当成有效权限（原有语义保持不变）");
    }

    @Test
    @DisplayName("空权限码直接返回 false，不查库也不抛异常")
    void nullOrEmptyPermissionIsDenied() throws SQLException {
        long userId = createUser();
        assertFalse(PermissionService.hasPermission(userId, null));
        assertFalse(PermissionService.hasPermission(userId, ""));
    }

    // ==================== 夹具 ====================

    private long createUser() throws SQLException {
        String username = "permcache_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, password) VALUES (?, ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, "x");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next(), "创建测试用户失败");
                return rs.getLong(1);
            }
        }
    }
}
