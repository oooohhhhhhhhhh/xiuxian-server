package com.mtxgdn.playerdata;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份还原必须拒收"结构非法"的数据文件。
 *
 * <p><b>缺陷形态</b>：{@code PlayerDataService} 的还原路径把 JSON 里的
 * <b>表名和列名</b>直接拼进 SQL 文本（{@code INSERT INTO <表> (<列>, ...) VALUES (?)}、
 * {@code DELETE FROM <表> WHERE <列> = ?}、{@code UPDATE players SET <列> = ?}）。
 * 数据值一直走 {@code ?} 占位符是安全的，但标识符天生无法参数化 ——
 * 于是一份被篡改或损坏的备份文件只要把表名写成
 * {@code players; DROP TABLE users; --}、或把列名写成
 * {@code name = 'x', gold}, 就会被原样执行。</p>
 *
 * <p>本测试用三份文件验证：恶意表名被拒、恶意列名被拒，且 {@code users} 表
 * 在两次尝试后依然健在；同时用一份正常文件确认校验不会误伤合法备份。</p>
 */
class PlayerDataRestoreValidationTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final PlayerDataService service = new PlayerDataService();

    @Test
    @DisplayName("表名注入必须被拒绝，且不会真的执行 DROP")
    void maliciousTableNameIsRejected() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("pdtable" + n, "注入测试" + n);
        long userCountBefore = countUsers();

        String json = """
                {"playerId": %d, "userId": 0, "playerName": "注入测试%d",
                 "tables": {"players; DROP TABLE users; --": [{"id": %d}]}}
                """.formatted(playerId, n, playerId);

        PlayerDataResult result = service.restorePlayer(playerId, json, true);

        assertFalse(result.success(),
                "表名含分号/空格的数据文件必须被拒收，实际: " + result);
        assertEquals(userCountBefore, countUsers(),
                "users 表必须还在（注入语句不能被执行）");
        assertTrue(result.message().contains("表名"),
                "错误信息应指出是表名非法，实际: " + result.message());
    }

    @Test
    @DisplayName("列名注入必须被拒绝，且不会真的执行 DROP")
    void maliciousColumnNameIsRejected() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("pdcolumn" + n, "列注入" + n);
        long userCountBefore = countUsers();

        String json = """
                {"playerId": %d, "userId": 0, "playerName": "列注入%d",
                 "tables": {"players_items": [
                     {"id": 1, "item_key) VALUES ('x'); DROP TABLE users; --": 1}
                 ]}}
                """.formatted(playerId, n);

        PlayerDataResult result = service.restorePlayer(playerId, json, true);

        assertFalse(result.success(),
                "列名含括号/分号的数据文件必须被拒收，实际: " + result);
        assertEquals(userCountBefore, countUsers(),
                "users 表必须还在（注入语句不能被执行）");
        assertTrue(result.message().contains("列名"),
                "错误信息应指出是列名非法，实际: " + result.message());
    }

    @Test
    @DisplayName("合法备份不能被误伤")
    void legitimateBackupStillRestores() throws SQLException {
        int n = SEQ.incrementAndGet();
        long playerId = createPlayer("pdok" + n, "还原前" + n);

        String json = """
                {"playerId": %d, "userId": 0, "playerName": "还原前%d",
                 "tables": {"players": [{"id": %d, "name": "还原后%d"}]}}
                """.formatted(playerId, n, playerId, n);

        PlayerDataResult result = service.restorePlayer(playerId, json, true);

        assertTrue(result.success(),
                "结构合法的备份必须能正常还原，实际: " + result);
        assertEquals("还原后" + n, playerName(playerId), "还原应写回 name");
    }

    // ==================== 夹具 ====================

    private static long createPlayer(String username, String name) throws SQLException {
        long userId;
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
                    assertTrue(rs.next());
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, spiritual_root, realm, sub_realm, "
                            + "hp, max_hp, mp, max_mp, attack, defense, speed, spirit, experience, "
                            + "gold, last_breakthrough_time) "
                            + "VALUES (?, ?, 'FIRE_WOOD_EARTH', 0, 0, 100, 100, 50, 50, 10, 10, 10, 10, 0, 0, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    return rs.getLong(1);
                }
            }
        }
    }

    private static String playerName(long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static long countUsers() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
