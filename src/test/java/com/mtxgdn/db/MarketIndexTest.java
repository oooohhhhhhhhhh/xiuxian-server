package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坊市挂单表索引回归测试。
 * <p>
 * 背景：{@code trade_listings} 此前<b>没有任何索引</b>，而到期回收任务每 60 秒
 * 就按 {@code status + expires_at} 扫一次全表，{@code /坊市} 每次也按
 * {@code status} 过滤。挂单量增长后这会变成持续的数据库开销。
 * <p>
 * 这里锁定两件事：索引确实被建出来了；以及回收查询的<b>执行计划真的用上了索引</b>
 * ——只断言"索引存在"是不够的，条件写法一变就可能退化为全表扫描。
 */
class MarketIndexTest extends TestBase {

    private static final String SWEEP_SQL =
            "SELECT id, seller_player_id, item_key, quantity FROM trade_listings "
                    + "WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at <= ?";

    @Test
    @DisplayName("坊市: trade_listings 已建立支撑回收/查询的索引")
    void tradeListingsIndexesExist() throws Exception {
        try (Connection conn = DatabaseManager.getConnection()) {
            if (!isSqlite(conn)) {
                return; // 目录表查询按 SQLite 写；索引语句本身是跨库通用的
            }
            List<String> names = listIndexes(conn, "trade_listings");
            assertTrue(names.contains("idx_trade_listings_status_expires"),
                    "缺少 (status, expires_at) 索引：到期回收任务每 60s 会全表扫描。实际索引: " + names);
            assertTrue(names.contains("idx_trade_listings_seller_status"),
                    "缺少 (seller_player_id, status) 索引：/我的上架 会全表扫描。实际索引: " + names);
        }
    }

    @Test
    @DisplayName("坊市: 到期回收查询的执行计划走索引而非全表扫描")
    void sweepQueryPlanUsesIndex() throws Exception {
        try (Connection conn = DatabaseManager.getConnection()) {
            if (!isSqlite(conn)) {
                return;
            }
            String plan;
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + SWEEP_SQL)) {
                ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                plan = collectPlan(ps);
            }
            assertTrue(plan.contains("idx_trade_listings_status_expires"),
                    "到期回收查询未走索引（可能退化为全表扫描）。执行计划: " + plan);
            assertTrue(!plan.contains("SCAN trade_listings"),
                    "到期回收查询仍在全表扫描 trade_listings。执行计划: " + plan);
        }
    }

    @Test
    @DisplayName("坊市: 在售列表查询的执行计划走索引")
    void activeListingsQueryPlanUsesIndex() throws Exception {
        try (Connection conn = DatabaseManager.getConnection()) {
            if (!isSqlite(conn)) {
                return;
            }
            String sql = "SELECT * FROM trade_listings WHERE status = 'active' "
                    + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC";
            String plan;
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
                ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                plan = collectPlan(ps);
            }
            assertTrue(plan.contains("idx_trade_listings_status_expires"),
                    "/坊市 在售列表查询未走索引。执行计划: " + plan);
        }
    }

    private static List<String> listIndexes(Connection conn, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static String collectPlan(PreparedStatement ps) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append(rs.getString("detail")).append(" | ");
            }
        }
        return sb.toString();
    }

    private static boolean isSqlite(Connection conn) throws Exception {
        return conn.getMetaData().getDatabaseProductName().toLowerCase().contains("sqlite");
    }
}
