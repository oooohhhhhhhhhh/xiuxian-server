package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家数据文件化模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code PlayerDataService} 原来 889 行里 4 处
 * {@code DatabaseManager.getConnection()}，而它扛着"导出全部玩家"、
 * "采集单个玩家快照"（十几张表、两种关联列、好友还是双向的）和
 * "还原"（删旧行 → 逐行插回的大事务）。表名与列名来自备份 JSON，
 * 天生无法用 {@code ?} 参数化，只能靠 {@code assertSafeTableName} /
 * {@code assertSafeColumnName} 兜底 —— 这条防线一旦在搬运时被丢掉，
 * 一份被篡改的备份就能把任意标识符拼进 SQL。</p>
 */
class PlayerDataCacheDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return PlayerDataCacheDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/playerdata/PlayerDataService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/PlayerDataCacheDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 跨模块的缓存副作用必须留在服务层
                "public static void markDirty(long playerId)",
                "dirtyPlayers.add(playerId)",
                // 文件落盘、回读校验、摘要
                "Files.write(tmp",
                "computeChecksum(canonical)",
                "verifySerialized(",
                "SCHEMA_VERSION",
                // 还原前的编排：标识符校验、在线保护、行数/摘要比对
                "validateTableStructure(data.tables)",
                "isPlayerOnline(data.userId)",
                // JSON 值 → JDBC 值的解码与导出编码是一对
                "restoreValue(",
                "\"ts:\"",
                // 宗门成员刻意不删重建
                "sect_members",
                // 对玩家可见的文案
                "playerdata.restore_failed_and_was_rolled");
    }

    @Test
    @DisplayName("四个意图各一对重载，自取连接版直接委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        // 事务内的写/删方法只提供 Connection 版（调用方必须在事务里），
        // 所以对它们只要求"有 Connection 版 + 没有自取连接版"。
        for (String sig : new String[]{
                "public List<Map<String, Object>> findRowsByColumn(Connection conn, String table, String column, long value)",
                "public List<Map<String, Object>> findRowsByColumnOr(Connection conn, String table, String colA, String colB, long value)",
                "public List<Long> listPlayerIds(Connection conn)",
                "public void updatePlayerRow(Connection conn, Map<String, Object> row)",
                "public boolean insertRow(Connection conn, String table, Map<String, Object> row)",
                "public void deleteByColumn(Connection conn, String table, String column, long value)",
                "public void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value)"}) {
            assertTrue(code.contains(sig),
                    "缺少 Connection 版：" + sig + "（事务内调用只能另开连接，SQLite 下会 SQLITE_BUSY_SNAPSHOT）");
        }
        // 自取连接版必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public List<Long> listPlayerIds()",
                "public List<Map<String, Object>> findRowsByColumn(String table, String column, long value)",
                "public List<Map<String, Object>> findRowsByColumnOr(String table, String colA, String colB, long value)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
        // 事务内的写/删方法：只收调用方连接，绝不自己开连接
        for (String sig : new String[]{
                "public void updatePlayerRow(Connection conn, Map<String, Object> row)",
                "public boolean insertRow(Connection conn, String table, Map<String, Object> row)",
                "public void deleteByColumn(Connection conn, String table, String column, long value)",
                "public void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value)"}) {
            String body = bodyOf(code, sig);
            assertFalse(body.contains("DatabaseManager.getConnection()"),
                    sig + " 是事务内方法，绝不能再自己开连接（SQLite 下会 SQLITE_BUSY_SNAPSHOT）");
        }
    }

    @Test
    @DisplayName("SQL 逐字保留：SELECT *、两种关联列、双向 OR 都在")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 委托之后每条 SQL 只写在 Connection 版里，所以读/删各 1 次；
        // UPDATE 的列集合是拼出来的，只有前缀一处。
        assertEquals(1, countOccurrences(code, "\"SELECT * FROM \" + table + \" WHERE \" + column + \" = ?\""),
                "单列查询逐字保留");
        assertEquals(1, countOccurrences(code, "\"SELECT * FROM \" + table + \" WHERE \" + colA + \" = ? OR \" + colB + \" = ?\""),
                "双向 OR 查询（好友）逐字保留");
        assertEquals(1, countOccurrences(code, "\"SELECT id FROM players\""),
                "导出全部玩家的语句逐字保留");
        assertEquals(1, countOccurrences(code, "\"UPDATE players SET \""),
                "还原基础行的 UPDATE 前缀逐字保留");
        assertEquals(1, countOccurrences(code, "\"DELETE FROM \" + table + \" WHERE \" + column + \" = ?\""),
                "单列删除逐字保留");
        assertEquals(1, countOccurrences(code, "\"DELETE FROM \" + table + \" WHERE \" + colA + \" = ? OR \" + colB + \" = ?\""),
                "双向删除逐字保留");
        // 原实现没有 LIMIT / 没有分页：一个玩家的行数有限
        assertFalse(code.contains("LIMIT"),
                "原实现是全量 SELECT *，不要顺手加分页");
    }

    @Test
    @DisplayName("标识符校验一条都不能少：表名与列名来自备份文件")
    void identifierValidationIsComplete() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(5, countOccurrences(code, "DatabaseManager.assertSafeTableName("),
                "每个拼表名的方法都要先校验表名（findRowsByColumn、findRowsByColumnOr、"
                        + "insertRow、deleteByColumn、deleteByColumnOr 各一处）");
        // 列名校验：读/写/删的每个列名参数都要过
        assertTrue(countOccurrences(code, "DatabaseManager.assertSafeColumnName(") >= 6,
                "每个拼列名的方法都要先校验列名");
        // updatePlayerRow 里逐个列名校验（值走占位符，列名没有占位符可用）
        String updateBody = bodyOf(code, "public void updatePlayerRow(Connection conn, Map<String, Object> row)");
        assertTrue(updateBody.contains("DatabaseManager.assertSafeColumnName(entry.getKey())"),
                "UPDATE 的列名要拼进 SQL 文本，必须逐个校验");
        String insertBody = bodyOf(code, "public boolean insertRow(Connection conn, String table, Map<String, Object> row)");
        assertTrue(insertBody.contains("DatabaseManager.assertSafeColumnName(entry.getKey())"),
                "INSERT 的列名要拼进 SQL 文本，必须逐个校验");
        assertTrue(insertBody.contains("DatabaseManager.assertSafeTableName(table)"),
                "INSERT 的表名要拼进 SQL 文本，必须校验（纵深防御）");
    }

    @Test
    @DisplayName("还原禁止覆盖 user_id：数据文件不能把玩家挂到他人账号")
    void restoreNeverOverwritesOwnershipColumns() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void updatePlayerRow(Connection conn, Map<String, Object> row)");
        for (String column : new String[]{"\"id\"", "\"user_id\"", "\"created_at\"", "\"updated_at\""}) {
            assertTrue(body.contains(column + ".equals(entry.getKey())"),
                    column + " 必须在还原时被跳过，否则数据文件能改掉行身份或账号所有权");
        }
        assertTrue(body.contains("sql.append(\" WHERE id = ?\")"),
                "UPDATE 必须带 WHERE id = ?，否则会更新整张 players 表");
    }

    @Test
    @DisplayName("读失败必须抛异常，绝不返回空列表")
    void readFailureThrowsInsteadOfSilentlyEmpty() throws IOException {
        String code = stripComments(read(daoPath()));
        String needle = "throw new RuntimeException(\"读取表 \" + table + \" 失败: \" + e.getMessage(), e)";
        assertEquals(2, countOccurrences(code, needle),
                "两个读方法（findRowsByColumn、findRowsByColumnOr）的自取连接版"
                        + "都必须把读失败变成异常：一张表读挂了会产出一份缺表的「完整快照」，"
                        + "等到需要恢复时才发现少了一半数据");
        assertTrue(code.contains("findRowsByColumn(Connection conn, String table, String column, long value)"),
                "单列查询要有 Connection 版");
        assertTrue(code.contains("findRowsByColumnOr(Connection conn, String table, String colA, String colB, long value)"),
                "双向 OR 查询要有 Connection 版");
        // 三个写/删方法只有 Connection 版，都声明 throws SQLException。
        // throws 在方法体左括号之前，所以对整份源码做签名子串匹配。
        for (String sig : new String[]{
                "public void updatePlayerRow(Connection conn, Map<String, Object> row) throws SQLException",
                "public void deleteByColumn(Connection conn, String table, String column, long value) throws SQLException",
                "public void deleteByColumnOr(Connection conn, String table, String colA, String colB, long value) throws SQLException"}) {
            assertTrue(code.contains(sig),
                    sig + " 必须声明 throws SQLException：还原是在事务里跑的");
        }
        assertFalse(bodyOf(code, "public void updatePlayerRow(Connection conn, Map<String, Object> row)")
                        .contains("DatabaseManager.getConnection()"),
                "updatePlayerRow 只该用调用方连接");
    }

    @Test
    @DisplayName("单行插入失败只告警不抛异常：一行坏数据不该让整份备份还原失败")
    void insertRowFailureOnlyWarns() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public boolean insertRow(Connection conn, String table, Map<String, Object> row)");
        assertTrue(body.contains("LOG.warn(\"还原行失败 [\" + table + \"]: \" + e.getMessage())"),
                "单行失败只告警，文案逐字保留");
        assertTrue(body.contains("return false;"), "失败要返回 false 让调用方不计数");
        assertTrue(body.contains("return true;"), "成功要返回 true 让调用方计数");
        // 值为 null 的列直接跳过，让数据库用默认值
        assertTrue(body.contains("if (entry.getValue() == null)"),
                "null 值列必须跳过，原实现如此");
    }

    @Test
    @DisplayName("时间戳与 BLOB 的编解码是一对，两边都不能改")
    void timestampAndBlobEncodingPaired() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("return \"ts:\" + ((Date) value).getTime();"),
                "导出时时间戳必须带 ts: 标签，还原时才能还原成 Timestamp");
        assertTrue(dao.contains("java.util.Base64.getEncoder().encodeToString((byte[]) value)"),
                "BLOB 列必须走 Base64，否则进不了 JSON");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("new java.sql.Timestamp(Long.parseLong(((String) value).substring(3)))"),
                "还原时必须把 ts: 标签解回 Timestamp（兼容 MySQL DATETIME 列）");
        assertTrue(service.contains("value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)"),
                "JSON 数字统一为 Double，整数值必须还原为 Long，避免写入 DB 变成浮点");
        // 解码在服务层，DAO 不认识 JSON
        assertFalse(dao.contains("restoreValue"), "JSON 反序列化语义不属于 DAO");
        assertFalse(service.contains("toJsonSafe"), "导出编码已搬进 DAO");
    }

    @Test
    @DisplayName("列名来自 ResultSetMetaData，不用列序号")
    void mappingUsesMetadataColumnNames() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("ResultSetMetaData meta = rs.getMetaData()"),
                "必须用元数据取列名：SELECT * 取回哪些列由数据库决定，加列不会静默错位");
        assertTrue(code.contains("for (int i = 1; i <= meta.getColumnCount(); i++)"),
                "从 1 开始遍历到列数（JDBC 列序号是 1-based）");
        assertTrue(code.contains("row.put(columnName, toJsonSafe(value))"),
                "每列都要过 toJsonSafe");
    }

    @Test
    @DisplayName("还原大事务共用一条连接，事务边界在服务层")
    void restoreTransactionSharesOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        // 事务边界由 DatabaseManager.runTransaction 持有，服务层零 getConnection
        assertEquals(0, countOccurrences(service, "getConnection("),
                "服务层不该自己开连接：还原大事务走 runTransaction，DAO 只收 Connection");
        assertTrue(service.contains("DatabaseManager.runTransaction(conn -> {"),
                "还原必须是一条事务，删旧行与逐行插回共用同一条连接");
        for (String call : List.of(
                "cacheDao.updatePlayerRow(conn,",
                "cacheDao.deleteByColumnOr(conn,",
                "cacheDao.deleteByColumn(conn,",
                "cacheDao.insertRow(conn,")) {
            assertTrue(service.contains(call),
                    call + " 必须显式传 conn，否则这一步会单独提交："
                            + "表被清空但没插回，中间失败就留下一张空表");
        }
        // 服务层不该再出现事务原语
        assertFalse(service.contains("setAutoCommit("), "事务边界已在 runTransaction 内");
        assertFalse(service.contains("conn.commit()"), "事务边界已在 runTransaction 内");
        assertFalse(service.contains("conn.rollback()"), "事务边界已在 runTransaction 内");
    }

    @Test
    @DisplayName("runTransaction 的包装被解包，玩家看到的仍是真实原因")
    void transactionWrapperIsUnwrapped() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("private static String rootMessage(Throwable t)"),
                "runTransaction 会把业务异常包成「事务执行失败: ...」，必须解包");
        assertTrue(service.contains("I18n.t(\"playerdata.restore_failed_and_was_rolled\", rootMessage(e))"),
                "还原失败文案必须用真实原因，不能用事务包装");
    }
}
