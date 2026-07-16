package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

public class MySQLDialect implements DatabaseDialect {

    @Override
    public String getPkDefinition() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public String getTimestampDefault() {
        return "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
    }

    @Override
    public String getTimestampUpdate() {
        return "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
    }

    @Override
    public String getBooleanType() {
        return "BOOLEAN DEFAULT FALSE";
    }

    @Override
    public String getTablePrefix() {
        return "`";
    }

    @Override
    public String getColumnPrefix() {
        return "`";
    }

    @Override
    public String getTableNamesQuery() {
        return "SHOW TABLES";
    }

    @Override
    public String getTableColumnsQuery(String tableName) {
        return "DESCRIBE `" + tableName + "`";
    }

    @Override
    public String buildSelectAll(String tableName) {
        return "SELECT * FROM `" + tableName + "` LIMIT ? OFFSET ?";
    }

    @Override
    public String buildSelectById(String tableName) {
        return "SELECT * FROM `" + tableName + "` WHERE id = ?";
    }

    @Override
    public String buildInsert(String tableName, List<String> columns) {
        String cols = columns.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));
        String vals = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));
        return "INSERT INTO `" + tableName + "` (" + cols + ") VALUES (" + vals + ")";
    }

    @Override
    public String buildUpdate(String tableName, List<String> columns) {
        String setClause = columns.stream()
                .map(c -> "`" + c + "` = ?")
                .collect(Collectors.joining(", "));
        return "UPDATE `" + tableName + "` SET " + setClause + " WHERE id = ?";
    }

    @Override
    public String buildDelete(String tableName) {
        return "DELETE FROM `" + tableName + "` WHERE id = ?";
    }

    @Override
    public String buildCount(String tableName) {
        return "SELECT COUNT(*) FROM `" + tableName + "`";
    }

    @Override
    public String getColumnQuote() {
        return "`";
    }

    @Override
    public void initConnection(Connection conn) throws SQLException {
    }

    @Override
    public String getDropColumnStatement(String tableName, String columnName) {
        return "ALTER TABLE `" + tableName + "` DROP COLUMN `" + columnName + "`";
    }

    @Override
    public String getModifyColumnStatement(String tableName, String columnName, String newType) {
        return "ALTER TABLE `" + tableName + "` MODIFY COLUMN `" + columnName + "` " + newType;
    }

    @Override
    public void disableForeignKeyChecks(Statement stmt) throws SQLException {
        stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
    }

    @Override
    public void enableForeignKeyChecks(Statement stmt) throws SQLException {
        stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Override
    public boolean supportsForeignKeysInCreateTable() {
        return true;
    }

    @Override
    public boolean supportsIndexInCreateTable() {
        return true;
    }

    @Override
    public String getAlterTableAddColumn(String tableName, String columnName, String columnType) {
        return "ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnType;
    }

    @Override
    public String getColumnNameFromMetadata(ResultSet rs) throws SQLException {
        return rs.getString("Field");
    }

    @Override
    public String getColumnTypeFromMetadata(ResultSet rs) throws SQLException {
        return rs.getString("Type");
    }

    @Override
    public boolean getColumnNotNullFromMetadata(ResultSet rs) throws SQLException {
        return "NO".equalsIgnoreCase(rs.getString("Null"));
    }

    @Override
    public boolean getColumnPkFromMetadata(ResultSet rs) throws SQLException {
        return "PRI".equalsIgnoreCase(rs.getString("Key"));
    }

    @Override
    public String quoteTableName(String tableName) {
        return "`" + tableName + "`";
    }

    @Override
    public String quoteColumnName(String columnName) {
        return "`" + columnName + "`";
    }
}