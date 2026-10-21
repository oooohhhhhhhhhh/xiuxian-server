package com.mtxgdn.db;

import com.mtxgdn.TestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移历史（{@code schema_migrations}）回归测试。
 *
 * <p>改造前的迁移全部是 {@code try { ALTER TABLE ... } catch (SQLException ignored) {}}，
 * 于是"列已存在"和"没权限 / 表不存在 / SQL 写错 / 磁盘满"享受同一待遇：
 * 服务照常启动、日志里一个字都没有，直到某个查询在运行期炸掉才暴露。
 * {@code verification_codes.sent_at} 就是这么在 SQLite 上从来没补成功过的
 * —— SQLite 不支持 {@code ADD COLUMN IF NOT EXISTS}。</p>
 *
 * <p>这里钉住两条不变量：每一步都要留下记录，且只有"已存在"类错误允许被吞。</p>
 */
class MigrationHistoryTest extends TestBase {

    @Test
    @DisplayName("迁移历史: 每一步都登记进 schema_migrations，且全部为 ok")
    void historyIsRecorded() throws SQLException {
        DatabaseManager.initTable();

        Map<String, String> history = readHistory();
        assertFalse(history.isEmpty(), "schema_migrations 应当有记录");
        assertTrue(history.size() >= 20, "登记的迁移数偏少（" + history.size() + " 条）: " + history.keySet());

        for (Map.Entry<String, String> e : history.entrySet()) {
            assertEquals("ok", e.getValue(), "迁移 " + e.getKey() + " 应为 ok，实际: " + e.getValue());
        }

        assertTrue(history.containsKey("022_sects_treasury"), "缺少宗门金库迁移记录: " + history.keySet());
        assertTrue(history.containsKey("024_players_language"), "缺少玩家语言列迁移记录: " + history.keySet());
    }

    @Test
    @DisplayName("迁移历史: 反复执行 initTable 幂等，同一迁移不产生重复记录")
    void historyIsIdempotent() throws SQLException {
        DatabaseManager.initTable();
        int first = readHistory().size();

        DatabaseManager.initTable();
        DatabaseManager.initTable();

        assertEquals(first, readHistory().size(),
                "同一个 version 应当被覆盖而不是追加，否则历史表会随重启无限膨胀");
    }

    @Test
    @DisplayName("只吞「已存在」类错误：其余 SQL 错误必须原样抛出")
    void onlyAlreadyExistsIsBenign() {
        assertTrue(DatabaseMigration.isAlreadyExists(new SQLException("duplicate column name: lang")),
                "SQLite 的重复列错误应被视为预期");
        assertTrue(DatabaseMigration.isAlreadyExists(new SQLException("Duplicate column name 'lang'")),
                "MySQL 的重复列错误应被视为预期");
        assertTrue(DatabaseMigration.isAlreadyExists(new SQLException("table players already exists")),
                "表已存在应被视为预期");

        assertFalse(DatabaseMigration.isAlreadyExists(new SQLException("no such table: players")),
                "表不存在是真错误，不能被吞掉");
        assertFalse(DatabaseMigration.isAlreadyExists(new SQLException("disk I/O error")),
                "磁盘错误是真错误，不能被吞掉");
        assertFalse(DatabaseMigration.isAlreadyExists(new SQLException("near \"ADD\": syntax error")),
                "SQL 写错是真错误，不能被吞掉");
        assertFalse(DatabaseMigration.isAlreadyExists(new SQLException((String) null)),
                "没有 message 的异常不能当成已存在");
    }

    /** 读 version -> status。 */
    private static Map<String, String> readHistory() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version, status FROM schema_migrations ORDER BY version")) {
            while (rs.next()) {
                out.put(rs.getString("version"), rs.getString("status"));
            }
        }
        return out;
    }
}
