package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 老库升级回归测试。
 *
 * <p>背景：{@code initTable()} 里的 {@code CREATE TABLE IF NOT EXISTS} 对老库是<b>空操作</b>，
 * 真正补列的是随后的 {@code executeMigrations()}。但索引创建一度排在迁移<b>之前</b>，
 * 于是任何"升级上来的库"都会在启动时直接抛
 * {@code SQLITE_ERROR: no such column: expires_at} —— 表建好了、服务却起不来。</p>
 *
 * <p>这里把结构还原成"老版本"再跑一遍 {@code initTable()} 模拟重启，
 * 钉住"先迁移、后建索引"这个顺序。</p>
 *
 * <p><b>注意</b>：整套测试共用一个 SQLite 文件（{@code TestBase} 的删库在 Windows 上
 * 因连接池仍持有文件句柄而失效），所以本类必须自己收拾现场 ——
 * 用例里插入的夹具行一律在 {@code @AfterEach} 里删掉，否则会污染后续测试类
 * （曾导致 {@code MapServiceTest} 断言"15 个地点"时读到 16 个）。</p>
 */
class LegacySchemaUpgradeTest extends TestBase {

    /** 夹具行的 id，取自正常地点 id（1~15）之外，便于识别与清理。 */
    private static final long FIXTURE_ID = 900;

    @org.junit.jupiter.api.AfterEach
    void removeFixtureRow() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM map_locations WHERE id = " + FIXTURE_ID);
        }
    }

    @Test
    @DisplayName("老库缺少 expires_at 时 initTable 不得中断启动，且索引仍要建出来")
    void testLegacyTradeListingsWithoutExpiresAt() throws SQLException {
        makeLegacyTradeListings();
        assertFalse(hasColumn("trade_listings", "expires_at"), "前置条件：老表不应有 expires_at");

        assertDoesNotThrow(DatabaseManager::initTable,
                "老库升级不得因索引引用了尚未补上的列而中断启动");

        assertTrue(hasColumn("trade_listings", "expires_at"), "迁移应补上 expires_at 列");
        assertTrue(indexNames("trade_listings").contains("idx_trade_listings_status_expires"),
                "补列之后 (status, expires_at) 索引应当建出来，实际索引: "
                        + indexNames("trade_listings"));
    }

    @Test
    @DisplayName("老库缺少地图加成列时也能启动，并补上两列（既有行为 NULL = 未播种）")
    void testLegacyMapLocationsWithoutBonusColumns() throws SQLException {
        // 先放一行"升级前就存在"的老数据（带值），删列后其值应丢失并变为 NULL
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM map_locations WHERE id = " + FIXTURE_ID);
            st.execute("INSERT INTO map_locations "
                    + "(id, name, description, region, min_realm, is_safe_zone, cultivation_bonus, exploration_bonus) "
                    + "VALUES (" + FIXTURE_ID + ", '老库地点', '升级前就存在的地点', '测试区域', 0, 0, 1.23, 1.45)");
        }

        dropColumnIfPresent("map_locations", "cultivation_bonus");
        dropColumnIfPresent("map_locations", "exploration_bonus");
        assertFalse(hasColumn("map_locations", "cultivation_bonus"), "前置条件：加成列应已被移除");

        assertDoesNotThrow(DatabaseManager::initTable, "老库升级不得中断启动");

        assertTrue(hasColumn("map_locations", "cultivation_bonus"), "迁移应补上 cultivation_bonus");
        assertTrue(hasColumn("map_locations", "exploration_bonus"), "迁移应补上 exploration_bonus");

        // 刻意不带 DEFAULT：老库既有行得到 NULL = "尚未播种"，由 MapService 按出厂值回填，
        // 这样既不覆盖管理员改过的数值，也不需要额外的迁移版本号
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT cultivation_bonus, exploration_bonus FROM map_locations WHERE id = " + FIXTURE_ID)) {
            assertTrue(rs.next(), "升级前的老数据行应当还在");
            rs.getDouble("cultivation_bonus");
            assertTrue(rs.wasNull(), "升级后老行的灵气倍率应为 NULL（等待播种）");
            rs.getDouble("exploration_bonus");
            assertTrue(rs.wasNull(), "升级后老行的地界倍率应为 NULL（等待播种）");
        }
    }

    // ==================== 辅助 ====================

    /** 把 trade_listings 还原成"没有 expires_at"的老结构。 */
    private void makeLegacyTradeListings() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE trade_listings");
            st.execute("CREATE TABLE trade_listings ("
                    + "id INTEGER PRIMARY KEY, "
                    + "seller_player_id BIGINT NOT NULL, "
                    + "item_key VARCHAR(64) NOT NULL, "
                    + "quantity INT NOT NULL, "
                    + "price_spirit_stones BIGINT NOT NULL, "
                    + "fee BIGINT DEFAULT 0, "
                    + "status VARCHAR(16) DEFAULT 'active', "
                    + "created_at TIMESTAMP)");
        }
    }

    /** 删列（SQLite 3.35+ 支持）；列不存在时静默跳过。 */
    private void dropColumnIfPresent(String table, String column) throws SQLException {
        if (!hasColumn(table, column)) return;
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private Set<String> indexNames(String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '" + table + "'")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
