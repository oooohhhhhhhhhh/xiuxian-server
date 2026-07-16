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
}