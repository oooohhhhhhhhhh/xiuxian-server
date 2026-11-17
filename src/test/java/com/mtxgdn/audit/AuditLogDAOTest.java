package com.mtxgdn.audit;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.AuditLogDAO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审计日志 DAO 的基本契约：写入、查询、回填、截断、按 ID 删除。
 */
class AuditLogDAOTest extends TestBase {

    private final AuditLogDAO dao = new AuditLogDAO();

    private AuditLog newLog(AuditActor actor, String action, long ts) {
        return newLog(actor, "42", action, ts);
    }

    private AuditLog newLog(AuditActor actor, String actorId, String action, long ts) {
        AuditLog log = new AuditLog();
        log.setTs(ts);
        log.setActor(actor);
        log.setActorId(actorId);
        log.setActorName("测试者");
        log.setAction(action);
        log.setTargetType("PLAYER");
        log.setTargetId("7");
        log.setDetail("{\"before\":1,\"after\":2}");
        log.setIp("127.0.0.1");
        log.setResult(AuditResult.OK);
        return log;
    }

    @Test
    @DisplayName("表结构：audit_logs 存在，player_action_logs 已不存在")
    void schemaIsMerged() throws SQLException {
        try (Connection conn = com.mtxgdn.db.DatabaseManager.getConnection()) {
            assertTrue(tableExists(conn, "audit_logs"), "audit_logs 应已建表");
            assertFalse(tableExists(conn, "player_action_logs"), "旧表应已被迁移删除");
        }
    }

    @Test
    @DisplayName("写入 + 查询 + 计数")
    void insertAndQuery() throws SQLException {
        long id = dao.insert(newLog(AuditActor.ADMIN, "iq", "USER_DELETE", System.currentTimeMillis()));
        assertTrue(id > 0);

        List<Map<String, Object>> rows = dao.query("ADMIN", null, "iq", null, null, null, null, null, 10, 0);
        assertEquals(1, rows.size());
        assertEquals("USER_DELETE", rows.get(0).get("action"));
        assertEquals("ADMIN", rows.get(0).get("actor_type"));
        assertEquals(1, dao.count("ADMIN", null, "iq", null, null, null, null, null));
    }

    @Test
    @DisplayName("回填结果：进行中 -> 失败")
    void updateResult() throws SQLException {
        long id = dao.insertPending(newLog(AuditActor.ADMIN, "upd", "ADMIN_REQUEST", System.currentTimeMillis()));
        assertEquals("OK", dao.query(null, null, "upd", null, null, null, null, null, 1, 0).get(0).get("result"));

        dao.updateResult(id, AuditResult.FAIL, "boom");
        Map<String, Object> row = dao.query(null, null, "upd", null, null, null, null, null, 1, 0).get(0);
        assertEquals("FAIL", row.get("result"));
        assertEquals("boom", row.get("detail"));
    }

    @Test
    @DisplayName("detail 超长时截断，不丢整条记录")
    void detailIsTruncated() throws SQLException {
        AuditLog log = newLog(AuditActor.SYSTEM, "big", "BIG", System.currentTimeMillis());
        log.setDetail("x".repeat(5000));
        dao.insert(log);

        Map<String, Object> row = dao.query("SYSTEM", "BIG", null, null, null, null, null, null, 1, 0).get(0);
        String detail = (String) row.get("detail");
        assertTrue(detail.length() <= 2001, "detail 应被截断，实际 " + detail.length());
    }

    @Test
    @DisplayName("按时间范围 / 关键字过滤")
    void filters() throws SQLException {
        long now = System.currentTimeMillis();
        dao.insert(newLog(AuditActor.PLAYER, "flt", "LOGIN", now - 10_000));
        dao.insert(newLog(AuditActor.PLAYER, "flt", "LOGOUT", now));

        assertEquals(2, dao.count("PLAYER", null, "flt", null, null, null, null, null));
        assertEquals(1, dao.count("PLAYER", "LOGIN", "flt", null, null, null, null, null));
        assertEquals(1, dao.count("PLAYER", null, "flt", null, null, "LOGOUT", null, null));
        assertEquals(1, dao.count("PLAYER", null, "flt", null, null, null, now - 5_000, null),
                "只应命中最近一条");
        assertEquals(2, dao.count("PLAYER", null, "flt", "7", null, null, null, null));
    }

    @Test
    @DisplayName("按 ID 批量删除（归档器用）")
    void deleteByIds() throws SQLException {
        long a = dao.insert(newLog(AuditActor.SYSTEM, "del", "A", System.currentTimeMillis()));
        long b = dao.insert(newLog(AuditActor.SYSTEM, "del", "B", System.currentTimeMillis()));
        long c = dao.insert(newLog(AuditActor.SYSTEM, "del", "C", System.currentTimeMillis()));

        assertEquals(2, dao.deleteByIds(List.of(a, b)));
        List<Map<String, Object>> left = dao.query(null, null, "del", null, null, null, null, null, 10, 0);
        assertEquals(1, left.size());
        assertEquals(c, ((Number) left.get(0).get("id")).longValue());
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
