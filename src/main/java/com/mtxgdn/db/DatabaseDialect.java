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

    /**
     * 初始化数据库文件级设置（只需在建池后执行<b>一次</b>）。
     *
     * <p>与 {@link #initConnection} 的区别：后者是<b>连接级</b>属性（每条连接都要重设，
     * 如 busy_timeout / foreign_keys），而本方法是<b>文件级</b>的持久化设置
     * （如 SQLite 的 journal_mode = WAL）。文件级设置每条连接都重设一遍不只是浪费：
     * SQLite 设置 journal_mode 需要排他锁，反复抢锁会打断别人的事务。</p>
     *
     * <p>默认空实现：只有需要文件级设置的方言才覆写。</p>
     */
    default void initDatabase(Connection conn) throws SQLException {
        // 默认无需文件级设置
    }

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