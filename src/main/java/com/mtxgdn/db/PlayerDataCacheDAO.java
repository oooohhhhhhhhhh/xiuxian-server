package com.mtxgdn.db;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家数据快照的读写入口（{@code PlayerDataService} 的缓存/备份底层）。
 *
 * <p>从 {@code PlayerDataService} 抽出来的 DAO。每个意图一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，把 {@link SQLException} 包成
 *       {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ul>
 *
 * <p><b>这个 DAO 的特殊之处：它搬的是"结构"，不是"某张表"</b>。玩家快照要覆盖
 * 十几张个人表，而每张表的关联列不同（多为 {@code player_id}，
 * {@code trade_listings} 用 {@code seller_player_id}），好友还是双向的
 * （{@code player_id} 或 {@code friend_player_id}）。原实现把这些差异做成
 * "表名 + 列名作参数"的通用查询，SQL 文本靠字符串拼接 —— 表名与列名<b>无法
 * 用 {@code ?} 参数化</b>，所以这两个标识符进来时必须先过
 * {@link DatabaseManager#assertSafeTableName} / {@link #assertSafeColumnName}。
 * 这条校验逐字保留在 DAO 里，是备份文件被篡改时唯一的一道防线。</p>
 *
 * <p><b>为什么需要 Connection 重载</b>：还原是一条大事务
 * （{@code setAutoCommit(false)} + {@code commit()} + {@code rollback()}），
 * 里面"删旧行 → 逐行插回"两步必须共用同一条连接。DAO 若在事务里另开连接，
 * SQLite 下会直接抛 {@code SQLITE_BUSY_SNAPSHOT}，整次还原失败；
 * 即便不报错，删和插也会落在不同提交上，中间失败会留下"表被清空但没插回"的
 * 空表。所以事务内的五个写/删方法全部只提供 Connection 版。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>读失败必须抛异常</b>：原实现在 catch 里抛
 *       {@code RuntimeException("读取表 " + table + " 失败: " + e.getMessage(), e)}。
 *       旧实现曾经只打 WARN 然后返回空列表，结果一张表读挂了会产出一份
 *       "看起来正常、实际缺表"的快照，等到需要恢复时才发现少了一半数据。
 *       备份的价值在于完整性，所以宁可让整次导出失败。</li>
 *   <li><b>快照读的是"当前库"，没有 LIMIT / 没有分页</b>：一个玩家的行数有限，
 *       原实现就是全量 {@code SELECT *}，逐字保留。</li>
 *   <li><b>时间戳带 {@code "ts:"} 标签</b>：{@link #toJsonSafe} 把
 *       {@code java.util.Date}（JDBC 的 {@code TIMESTAMP}）转成
 *       {@code "ts:<毫秒>"} 字符串，还原时再转回 {@code java.sql.Timestamp}
 *       （兼容 MySQL {@code DATETIME} 列）。这个编解码是一对，两边都不能改。</li>
 *   <li><b>{@code byte[]} 走 Base64</b>：BLOB 列不能直接进 JSON。</li>
 *   <li><b>列名来自 {@code ResultSetMetaData}</b>：{@code SELECT *} 取回哪些列
 *       由数据库决定，所以用 {@code meta.getColumnName(i)} 而不是列序号。</li>
 * </ul>
 *
 * <p><b>不属于这个 DAO 的部分</b>：{@code markDirty} 是跨模块的缓存副作用
 * （十几个服务在写入后调它标记待落盘），刻意留在 {@code PlayerDataService}；
 * 文件落盘、摘要校验、还原前的结构校验编排也都在服务层。</p>
 */
public class PlayerDataCacheDAO {

    private static final GameLogger LOG = GameLogger.getLogger(PlayerDataCacheDAO.class);

    // ==================== 读（导出快照） ====================

    /** 全部玩家 id（导出全部玩家用）。 */
    public List<Long> listPlayerIds() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return listPlayerIds(conn);
        } catch (SQLException e) {
            throw new RuntimeException("导出全部玩家失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Long> listPlayerIds(Connection conn) throws SQLException {
        String sql = "SELECT id FROM players";
        List<Long> ids = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    /**
     * 读某张表里 {@code column = value} 的所有行。
     *
     * <p><b>读失败必须抛异常</b>：宁可整次导出失败，也不要产出一份缺表的
     * "完整快照"。</p>
     */
    public List<Map<String, Object>> findRowsByColumn(String table, String column, long value) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findRowsByColumn(conn, table, column, value);
        } catch (SQLException e) {
            throw new RuntimeException("读取表 " + table + " 失败: " + e.getMessage(), e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Map<String, Object>> findRowsByColumn(Connection conn, String table, String column, long value)
            throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(column);
        String sql = "SELECT * FROM " + table + " WHERE " + column + " = ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        }
        return rows;
    }

    /** 读某张表里 {@code colA = value OR colB = value} 的所有行（好友是双向记录）。 */
    public List<Map<String, Object>> findRowsByColumnOr(String table, String colA, String colB, long value) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findRowsByColumnOr(conn, table, colA, colB, value);
        } catch (SQLException e) {
            throw new RuntimeException("读取表 " + table + " 失败: " + e.getMessage(), e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Map<String, Object>> findRowsByColumnOr(Connection conn, String table, String colA, String colB, long value)
            throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(colA);
        DatabaseManager.assertSafeColumnName(colB);
        String sql = "SELECT * FROM " + table + " WHERE " + colA + " = ? OR " + colB + " = ?";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        }
        return rows;
    }

    // ==================== 写（还原） ====================

    /**
     * 更新玩家基础行。
     *
     * <p>{@code id} / {@code user_id} / {@code created_at} / {@code updated_at}
     * 四列<b>刻意跳过</b>：{@code user_id} 关联账号所有权，还原时禁止覆盖，
     * 防止数据文件把玩家挂到他人账号；另外三列是行身份与审计时间戳。
     * 列名要拼进 SQL 文本，所以逐个过 {@link DatabaseManager#assertSafeColumnName}
     * （值走占位符，列名没有占位符可用）。</p>
     *
     * <p>调用方负责先做 {@code restoreValue}（JSON 值 → JDBC 值），
     * 那是 JSON 反序列化语义，不属于 DAO。</p>
     */
    public void updatePlayerRow(Connection conn, Map<String, Object> row) throws SQLException {
        Long id = ((Number) row.get("id")).longValue();
        StringBuilder sql = new StringBuilder("UPDATE players SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if ("id".equals(entry.getKey()) || "user_id".equals(entry.getKey())
                    || "created_at".equals(entry.getKey()) || "updated_at".equals(entry.getKey())) {
                continue;
            }
            DatabaseManager.assertSafeColumnName(entry.getKey());
            if (!first) {
                sql.append(", ");
            }
            sql.append(entry.getKey()).append(" = ?");
            params.add(entry.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.setLong(params.size() + 1, id);
            ps.executeUpdate();
        }
    }

    /**
     * 插入一行（列名与表名都来自备份文件，逐个校验后拼进 SQL）。
     *
     * <p><b>纵深防御</b>：即便上游漏了结构校验，这里也不允许把非法标识符
     * 拼进 SQL。值为 {@code null} 的列直接跳过（原实现如此，
     * 让数据库用默认值）。</p>
     *
     * @return 是否插入成功；单行失败只告警不抛异常（原实现如此，
     *         一行坏数据不该让整份备份还原失败）
     */
    public boolean insertRow(Connection conn, String table, Map<String, Object> row) {
        DatabaseManager.assertSafeTableName(table);
        StringBuilder sql = new StringBuilder("INSERT INTO " + table + " (");
        StringBuilder values = new StringBuilder("VALUES (");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            DatabaseManager.assertSafeColumnName(entry.getKey());
            if (!first) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append(entry.getKey());
            values.append("?");
            params.add(entry.getValue());
            first = false;
        }
        sql.append(") ").append(values).append(")");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            LOG.warn("还原行失败 [" + table + "]: " + e.getMessage());
            return false;
        }
    }

    /** 删某张表里 {@code column = value} 的所有行（还原前清空旧数据）。 */
    public void deleteByColumn(Connection conn, String table, String column, long value) throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(column);
        String sql = "DELETE FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.executeUpdate();
        }
    }

    /** 删某张表里 {@code colA = value OR colB = value} 的所有行（好友双向）。 */
    public void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value) throws SQLException {
        DatabaseManager.assertSafeTableName(table);
        DatabaseManager.assertSafeColumnName(colA);
        DatabaseManager.assertSafeColumnName(colB);
        String sql = "DELETE FROM " + table + " WHERE " + colA + " = ? OR " + colB + " = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, value);
            ps.executeUpdate();
        }
    }

    // ==================== ResultSet → Map ====================

    /**
     * 一行变一个 {@code Map}，键是列名、值是 JSON 安全的值。
     *
     * <p>用 {@code ResultSetMetaData} 取列名而不是列序号：{@code SELECT *}
     * 取回哪些列由数据库决定，加列不会让这里静默错位。</p>
     */
    static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String columnName = meta.getColumnName(i);
            Object value = rs.getObject(i);
            row.put(columnName, toJsonSafe(value));
        }
        return row;
    }

    /** 将 JDBC 值转换为可 JSON 序列化的基本类型（时间戳 → "ts:毫秒" 标签，便于还原类型） */
    static Object toJsonSafe(Object value) {
        if (value instanceof Date) {
            return "ts:" + ((Date) value).getTime();
        }
        if (value instanceof byte[]) {
            return java.util.Base64.getEncoder().encodeToString((byte[]) value);
        }
        return value;
    }
}
