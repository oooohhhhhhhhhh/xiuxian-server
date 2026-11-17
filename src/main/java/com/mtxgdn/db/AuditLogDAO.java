package com.mtxgdn.db;

import com.mtxgdn.audit.AuditActor;
import com.mtxgdn.audit.AuditLog;
import com.mtxgdn.audit.AuditResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code audit_logs} 的读写入口。
 *
 * <p><b>只增不删</b>：本类不提供按用户/按条删除的方法。唯一会删数据的是归档器
 * （{@link com.mtxgdn.audit.AuditArchiveService}），它先写文件再删库，
 * 且只删已经完整落盘的行。</p>
 */
public class AuditLogDAO {

    /** {@code detail} 列长度上限；写入前截断，避免超长导致整条审计丢失。 */
    private static final int DETAIL_MAX = 2000;

    /** 写入一条审计，返回自增 ID。失败抛 {@link SQLException}，由调用方决定是否阻断操作。 */
    public long insert(AuditLog log) throws SQLException {
        String sql = "INSERT INTO audit_logs (ts, actor_type, actor_id, actor_name, action, "
                + "target_type, target_id, detail, ip, result, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, log);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    /** 事务内版本：只使用传入的连接。 */
    public long insert(Connection conn, AuditLog log) throws SQLException {
        String sql = "INSERT INTO audit_logs (ts, actor_type, actor_id, actor_name, action, "
                + "target_type, target_id, detail, ip, result, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, log);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    private static void bind(PreparedStatement ps, AuditLog log) throws SQLException {
        ps.setLong(1, log.getTs() > 0 ? log.getTs() : System.currentTimeMillis());
        ps.setString(2, log.getActor().name());
        ps.setString(3, log.getActorId());
        ps.setString(4, log.getActorName());
        ps.setString(5, log.getAction());
        ps.setString(6, log.getTargetType());
        ps.setString(7, log.getTargetId());
        ps.setString(8, truncate(log.getDetail()));
        ps.setString(9, log.getIp());
        ps.setString(10, log.getResult().name());
        ps.setString(11, log.getTraceId());
    }

    /** 预写一条「进行中」记录（fail-closed 的写操作先落审计再执行），返回 ID。 */
    public long insertPending(AuditLog log) throws SQLException {
        log.setResult(AuditResult.OK);
        return insert(log);
    }

    /** 回填执行结果（成功 / 拒绝 / 失败）。失败抛异常。 */
    public void updateResult(long id, AuditResult result, String detail) throws SQLException {
        String sql = "UPDATE audit_logs SET result = ?, detail = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, result.name());
            ps.setString(2, truncate(detail));
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    /** 按条件分页查询（后台列表）。 */
    public List<Map<String, Object>> query(String actorType, String action, String actorId,
                                           String targetId, String result, String keyword,
                                           Long fromTs, Long toTs, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, actorType, action, actorId, targetId, result, keyword, fromTs, toTs);
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return readRows(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询审计日志失败", e);
        }
    }

    /** 与 {@link #query} 同条件的总数。 */
    public int count(String actorType, String action, String actorId, String targetId,
                     String result, String keyword, Long fromTs, Long toTs) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, actorType, action, actorId, targetId, result, keyword, fromTs, toTs);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计审计日志失败", e);
        }
    }

    /** 归档器用：取一批早于 cutoff 的记录（按 ts 升序，先归档最旧的）。 */
    public List<AuditLog> findOlderThan(long cutoffTs, int limit) {
        String sql = "SELECT * FROM audit_logs WHERE ts < ? ORDER BY ts ASC LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, cutoffTs);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<AuditLog> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(readLog(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new RuntimeException("读取待归档审计失败", e);
        }
    }

    /** 归档器用：按 ID 删除已成功落盘的行。 */
    public int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("DELETE FROM audit_logs WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除已归档审计失败", e);
        }
    }

    // ==================== 内部 ====================

    private static void appendFilters(StringBuilder sql, List<Object> params, String actorType,
                                      String action, String actorId, String targetId, String result,
                                      String keyword, Long fromTs, Long toTs) {
        if (actorType != null && !actorType.isBlank()) {
            sql.append(" AND actor_type = ?");
            params.add(actorType);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action);
        }
        if (actorId != null && !actorId.isBlank()) {
            sql.append(" AND actor_id = ?");
            params.add(actorId);
        }
        if (targetId != null && !targetId.isBlank()) {
            sql.append(" AND target_id = ?");
            params.add(targetId);
        }
        if (result != null && !result.isBlank()) {
            sql.append(" AND result = ?");
            params.add(result);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (action LIKE ? OR detail LIKE ? OR actor_name LIKE ? OR target_id LIKE ?)");
            String like = "%" + keyword + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (fromTs != null) {
            sql.append(" AND ts >= ?");
            params.add(fromTs);
        }
        if (toTs != null) {
            sql.append(" AND ts <= ?");
            params.add(toTs);
        }
    }

    private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (value instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(toMap(readLog(rs)));
        }
        return rows;
    }

    /** 行 → 后台列表/归档用的 Map（键与表列同名）。 */
    private static Map<String, Object> toMap(AuditLog log) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", log.getId());
        map.put("ts", log.getTs());
        map.put("actor_type", log.getActor().name());
        map.put("actor_id", log.getActorId());
        map.put("actor_name", log.getActorName());
        map.put("action", log.getAction());
        map.put("target_type", log.getTargetType());
        map.put("target_id", log.getTargetId());
        map.put("detail", log.getDetail());
        map.put("ip", log.getIp());
        map.put("result", log.getResult().name());
        map.put("trace_id", log.getTraceId());
        return map;
    }

    private static AuditLog readLog(ResultSet rs) throws SQLException {
        AuditLog log = new AuditLog();
        log.setId(rs.getLong("id"));
        log.setTs(rs.getLong("ts"));
        log.setActor(parseActor(rs.getString("actor_type")));
        log.setActorId(rs.getString("actor_id"));
        log.setActorName(rs.getString("actor_name"));
        log.setAction(rs.getString("action"));
        log.setTargetType(rs.getString("target_type"));
        log.setTargetId(rs.getString("target_id"));
        log.setDetail(rs.getString("detail"));
        log.setIp(rs.getString("ip"));
        log.setResult(parseResult(rs.getString("result")));
        log.setTraceId(rs.getString("trace_id"));
        return log;
    }

    private static AuditActor parseActor(String value) {
        try {
            return AuditActor.valueOf(value);
        } catch (RuntimeException e) {
            return AuditActor.SYSTEM;
        }
    }

    private static AuditResult parseResult(String value) {
        try {
            return AuditResult.valueOf(value);
        } catch (RuntimeException e) {
            return AuditResult.FAIL;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= DETAIL_MAX ? value : value.substring(0, DETAIL_MAX) + "…";
    }
}
