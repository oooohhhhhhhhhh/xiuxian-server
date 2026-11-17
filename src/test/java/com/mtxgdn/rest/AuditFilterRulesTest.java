package com.mtxgdn.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计过滤器的路径规则：什么算「管理后台写操作」、什么算「敏感读」。
 *
 * <p>规则错了会导致两种事故：漏审（改数据没留痕）或过度审计（每个轮询都写一行，
 * 把审计表撑爆）。这里把边界钉死。</p>
 */
class AuditFilterRulesTest {

    // ==================== 写操作 ====================

    @Test
    @DisplayName("后台非 GET 请求 = 写操作，fail-closed")
    void adminWrites() {
        assertTrue(AuditFilter.isAdminWrite("admin/players/1/edit", "POST"));
        assertTrue(AuditFilter.isAdminWrite("admin/users/5", "DELETE"));
        assertTrue(AuditFilter.isAdminWrite("admin/plugins/foo/enable", "PUT"));
        assertTrue(AuditFilter.isAdminWrite("admin/audit-logs", "DELETE"));
    }

    @Test
    @DisplayName("GET / HEAD / OPTIONS 不是写操作")
    void readsAreNotWrites() {
        assertFalse(AuditFilter.isAdminWrite("admin/players", "GET"));
        assertFalse(AuditFilter.isAdminWrite("admin/players", "HEAD"));
        assertFalse(AuditFilter.isAdminWrite("admin/players", "OPTIONS"));
    }

    @Test
    @DisplayName("非 /admin 路径不是后台写操作")
    void nonAdminPaths() {
        assertFalse(AuditFilter.isAdminWrite("game/map/travel", "POST"));
        assertFalse(AuditFilter.isAdminWrite("auth/login", "POST"));
    }

    // ==================== 敏感读 ====================

    @Test
    @DisplayName("玩家/用户/数据库/自身资料/game 的 GET 算敏感读")
    void sensitiveReads() {
        assertTrue(AuditFilter.isSensitiveRead("admin/players", "GET"));
        assertTrue(AuditFilter.isSensitiveRead("admin/players/1", "GET"));
        assertTrue(AuditFilter.isSensitiveRead("admin/users", "GET"));
        assertTrue(AuditFilter.isSensitiveRead("admin/database/tables", "GET"));
        assertTrue(AuditFilter.isSensitiveRead("admin/db/tables", "GET"), "数据库表列表也算敏感读");
        assertTrue(AuditFilter.isSensitiveRead("admin/me", "GET"));
        assertTrue(AuditFilter.isSensitiveRead("game/player/1", "GET"));
    }

    @Test
    @DisplayName("审计页自己的读取、普通后台列表不算敏感读（避免自审计噪声）")
    void notSensitive() {
        assertFalse(AuditFilter.isSensitiveRead("admin/audit-logs", "GET"));
        assertFalse(AuditFilter.isSensitiveRead("admin/overview", "GET"));
        assertFalse(AuditFilter.isSensitiveRead("admin/logs", "GET"));
    }

    @Test
    @DisplayName("敏感路径的写请求不算「读」")
    void writeIsNotRead() {
        assertFalse(AuditFilter.isSensitiveRead("admin/players/1/edit", "POST"));
    }

    // ==================== 轮询跳过审计 ====================

    @Test
    @DisplayName("列表页自动轮询带标记 → 跳过审计（手点查询不带标记仍留痕）")
    void pollSkipsListReads() {
        assertTrue(AuditFilter.shouldSkipAudit("admin/players", "GET", "1"));
        assertTrue(AuditFilter.shouldSkipAudit("admin/users", "GET", "1"));
        assertTrue(AuditFilter.shouldSkipAudit("admin/db/tables", "GET", "1"));
        assertTrue(AuditFilter.shouldSkipAudit("admin/db/tables/players", "GET", "1"));
    }

    @Test
    @DisplayName("没有轮询标记时一律审计")
    void noHeaderMeansAudit() {
        assertFalse(AuditFilter.shouldSkipAudit("admin/players", "GET", null));
        assertFalse(AuditFilter.shouldSkipAudit("admin/players", "GET", ""));
    }

    @Test
    @DisplayName("单条详情即使带标记也要审计（有意查看某个玩家）")
    void singleEntityIsNeverSkipped() {
        assertFalse(AuditFilter.shouldSkipAudit("admin/players/1", "GET", "1"));
        assertFalse(AuditFilter.shouldSkipAudit("admin/users/5", "GET", "1"));
        assertFalse(AuditFilter.shouldSkipAudit("admin/me", "GET", "1"));
        assertFalse(AuditFilter.shouldSkipAudit("game/player/1", "GET", "1"));
    }

    @Test
    @DisplayName("写操作永远不能被轮询标记绕过（fail-closed 底线）")
    void writesNeverSkip() {
        assertFalse(AuditFilter.shouldSkipAudit("admin/players", "POST", "1"));
        assertFalse(AuditFilter.shouldSkipAudit("admin/users/5", "DELETE", "1"));
        assertFalse(AuditFilter.shouldSkipAudit("admin/db/tables", "PUT", "1"));
    }
}
