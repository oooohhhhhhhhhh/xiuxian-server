package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public interface DatabaseDialect {

    String getPkDefinition();

    String getTimestampDefault();

    String getTimestampUpdate();

    String getBooleanType();

    String getTablePrefix();

    String getColumnPrefix();

    String getTableNamesQuery();

    String getTableColumnsQuery(String tableName);

    String buildSelectAll(String tableName);

    String buildSelectById(String tableName);

    String buildInsert(String tableName, List<String> columns);

    String buildUpdate(String tableName, List<String> columns);

    String buildDelete(String tableName);

    String buildCount(String tableName);

    String getColumnQuote();

    void initConnection(Connection conn) throws SQLException;

    String getDropColumnStatement(String tableName, String columnName);

    String getModifyColumnStatement(String tableName, String columnName, String newType);

    void disableForeignKeyChecks(Statement stmt) throws SQLException;

    void enableForeignKeyChecks(Statement stmt) throws SQLException;

    boolean supportsForeignKeysInCreateTable();

    boolean supportsIndexInCreateTable();

    String getAlterTableAddColumn(String tableName, String columnName, String columnType);

    String getColumnNameFromMetadata(ResultSet rs) throws SQLException;

    String getColumnTypeFromMetadata(ResultSet rs) throws SQLException;

    boolean getColumnNotNullFromMetadata(ResultSet rs) throws SQLException;

    boolean getColumnPkFromMetadata(ResultSet rs) throws SQLException;

    String quoteTableName(String tableName);

    String quoteColumnName(String columnName);
    
    /**
     * 返回数据库特定的 FOR UPDATE 子句
     * 用于在 SELECT 语句中加悲观锁
     */
    String getForUpdateClause();
}