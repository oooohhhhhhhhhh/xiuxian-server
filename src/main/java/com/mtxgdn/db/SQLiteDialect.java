package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

public class SQLiteDialect implements DatabaseDialect {

    @Override
    public String getPkDefinition() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public String getTimestampDefault() {
        return "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
    }

    @Override
    public String getTimestampUpdate() {
        return "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
    }

    @Override
    public String getBooleanType() {
        return "INTEGER DEFAULT 0";
    }

    @Override
    public String getTablePrefix() {
        return "\"";
    }

    @Override
    public String getColumnPrefix() {
        return "\"";
    }

    @Override
    public String getTableNamesQuery() {
        return "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
    }

    @Override
    public String getTableColumnsQuery(String tableName) {
        return "PRAGMA table_info(\"" + tableName + "\")";
    }

    @Override
    public String buildSelectAll(String tableName) {
        return "SELECT * FROM \"" + tableName + "\" LIMIT ? OFFSET ?";
    }

    @Override
    public String buildSelectById(String tableName) {
        return "SELECT * FROM \"" + tableName + "\" WHERE id = ?";
    }

    @Override
    public String buildInsert(String tableName, List<String> columns) {
        String cols = columns.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(", "));
        String vals = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));
        return "INSERT INTO \"" + tableName + "\" (" + cols + ") VALUES (" + vals + ")";
    }

    @Override
    public String buildUpdate(String tableName, List<String> columns) {
        String setClause = columns.stream()
                .map(c -> "\"" + c + "\" = ?")
                .collect(Collectors.joining(", "));
        return "UPDATE \"" + tableName + "\" SET " + setClause + " WHERE id = ?";
    }

    @Override
    public String buildDelete(String tableName) {
        return "DELETE FROM \"" + tableName + "\" WHERE id = ?";
    }

    @Override
    public String buildCount(String tableName) {
        return "SELECT COUNT(*) FROM \"" + tableName + "\"";
    }

    @Override
    public String getColumnQuote() {
        return "\"";
    }

    @Override
    public void initConnection(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            // busy_timeout 是**连接级**属性（每条新连接都要重设，否则默认 0 = 立即失败）。
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
        // journal_mode 刻意不在这里设。
        //
        // 它是**数据库文件级**的持久化属性：设成 WAL 后写进文件头，之后所有连接都自动是 WAL，
        // 不需要（也不应该）每条连接重设。而设置它需要**排他锁** —— 在一条已经借出的连接上
        // 反复执行 `PRAGMA journal_mode = WAL`，等于每次拿连接都去抢一次全库排他锁：
        //   · 撞上别的连接正在写时，这条 PRAGMA 自己就可能失败或长时间阻塞；
        //   · 更糟的是它会打断正在进行的事务，让对方的写入报 SQLITE_BUSY。
        // 这正好解释了"偶发、无法复现、堆栈指向毫不相干的查询"的 database is locked。
        //
        // 现在改由 {@link DatabaseManager} 在建池后对一条连接执行一次（见 ensureWalMode）。
    }

    /**
     * 把数据库文件切到 WAL 模式。
     *
     * <p>只需在建池后执行<b>一次</b>：journal_mode 是写进数据库文件头的持久化属性，
     * 之后所有连接自动是 WAL，不必每条重设。详见 {@link #initConnection} 的说明。</p>
     */
    @Override
    public void initDatabase(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL");
        }
    }

    @Override
    public String getDropColumnStatement(String tableName, String columnName) {
        return "ALTER TABLE \"" + tableName + "\" DROP COLUMN \"" + columnName + "\"";
    }

    @Override
    public String getModifyColumnStatement(String tableName, String columnName, String newType) {
        return "ALTER TABLE \"" + tableName + "\" ALTER COLUMN \"" + columnName + "\" TYPE " + newType;
    }

    @Override
    public void disableForeignKeyChecks(Statement stmt) throws SQLException {
        stmt.execute("PRAGMA foreign_keys = OFF");
    }

    @Override
    public void enableForeignKeyChecks(Statement stmt) throws SQLException {
        stmt.execute("PRAGMA foreign_keys = ON");
    }

    @Override
    public boolean supportsForeignKeysInCreateTable() {
        return true;
    }

    @Override
    public boolean supportsIndexInCreateTable() {
        return false;
    }

    @Override
    public String getAlterTableAddColumn(String tableName, String columnName, String columnType) {
        return "ALTER TABLE \"" + tableName + "\" ADD COLUMN \"" + columnName + "\" " + columnType;
    }

    @Override
    public String getColumnNameFromMetadata(ResultSet rs) throws SQLException {
        return rs.getString("name");
    }

    @Override
    public String getColumnTypeFromMetadata(ResultSet rs) throws SQLException {
        return rs.getString("type");
    }

    @Override
    public boolean getColumnNotNullFromMetadata(ResultSet rs) throws SQLException {
        return rs.getInt("notnull") == 1;
    }

    @Override
    public boolean getColumnPkFromMetadata(ResultSet rs) throws SQLException {
        return rs.getInt("pk") == 1;
    }

    @Override
    public String quoteTableName(String tableName) {
        return "\"" + tableName + "\"";
    }

    @Override
    public String quoteColumnName(String columnName) {
        return "\"" + columnName + "\"";
    }
    
    @Override
    public String getForUpdateClause() {
        // SQLite 不支持 FOR UPDATE，但在事务内 UPDATE/DELETE 会自动加写锁
        // 返回空字符串，调用者需要确保在事务内执行
        return "";
    }
}