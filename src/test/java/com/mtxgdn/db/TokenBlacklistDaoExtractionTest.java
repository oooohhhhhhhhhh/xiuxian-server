package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 吊销名单的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code TokenBlacklist} 原来 230 行里 5 处
 * {@code DatabaseManager.getConnection()}，而它是<b>认证链路</b> ——
 * 每次请求鉴权都要走 {@code isBlacklisted}。登出/吊销这条路径如果因为
 * 数据库抖动而抛异常，玩家就无法登出；鉴权这条路径如果抛异常，
 * 表现是"所有人突然都登录不了"。</p>
 */
class TokenBlacklistDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return TokenBlacklistDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/util/TokenBlacklist.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/TokenBlacklistDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // SHA-256 摘要：落库与缓存统一使用摘要，不保存明文
                "static String hash(String token)",
                "MessageDigest.getInstance(\"SHA-256\")",
                // 过期时间解析与兜底保留期
                "DEFAULT_RETENTION_MS",
                "resolveExpiry(token)",
                "JwtUtil.extractExpiration(token)",
                // 内存优先、DB 兜底、回填缓存的判定策略
                "blacklist.get(tokenHash)",
                "blacklist.put(tokenHash, dbExpiry)",
                "blacklist.remove(tokenHash)",
                "blacklist.entrySet().removeIf(",
                // 先内存后落库的顺序
                "blacklist.put(tokenHash, expiresAt)",
                "DAO.insert(tokenHash, expiresAt)",
                // 清理/清空的语义
                "public static void cleanupExpired(long maxAgeMs)",
                "public static void clear()",
                "public static void clearAll()",
                // 已载入条数的输出
                "已载入 ");
    }

    @Test
    @DisplayName("五个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{
                "insert", "findExpiresAt", "findUnexpired", "deleteExpiredBefore", "deleteAll"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 自取连接版必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public void insert(String tokenHash, long expiresAt)",
                "public Long findExpiresAt(String tokenHash)",
                "public List<BlacklistEntry> findUnexpired(long now)",
                "public int deleteExpiredBefore(long now)",
                "public int deleteAll()"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("双方言的「插入或无视」收进私有方法，两个重载共用")
    void dialectBranchLivesInOnePrivateMethod() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("private static String insertIgnoreSql()"),
                "双方言分支必须收进私有方法，不能在两个重载里重复");
        assertEquals(1, countOccurrences(code, "isSqlite()"),
                "方言判断只应出现一次（在私有方法里）");
        assertTrue(code.contains("ON CONFLICT(token_hash) DO NOTHING"),
                "SQLite 的插入或无视分支不能丢");
        assertTrue(code.contains("INSERT IGNORE INTO token_blacklist"),
                "MySQL 的插入或无视分支不能丢");
        // 只有 Connection 版取 SQL：自取连接版整体委托，所以只调用一次
        assertEquals(1, countOccurrences(code, "String sql = insertIgnoreSql();"),
                "SQL 只该在 Connection 版里取一次：自取连接版委托它，"
                        + "各取一次就会出现两个重载走不同分支的可能");
    }

    @Test
    @DisplayName("SQL 逐字保留：表名、列名、比较方向都没动")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 委托之后每条 SQL 只写在 Connection 版里，所以恰好 1 次。
        assertEquals(1, countOccurrences(code, "\"SELECT expires_at FROM token_blacklist WHERE token_hash = ?\""),
                "按摘要查过期时间逐字保留");
        assertEquals(1, countOccurrences(code, "\"SELECT token_hash, expires_at FROM token_blacklist WHERE expires_at > ?\""),
                "载入未过期记录的语句逐字保留（> 不是 >=）");
        assertEquals(1, countOccurrences(code, "\"DELETE FROM token_blacklist WHERE expires_at < ?\""),
                "清理语句逐字保留（< 不是 <=）");
        assertEquals(1, countOccurrences(code, "\"DELETE FROM token_blacklist\""),
                "清空整表逐字保留");
        // 只存摘要，DAO 不该知道明文
        assertFalse(code.contains("token_hash, token"), "DAO 只收摘要，不收明文 token");
    }

    @Test
    @DisplayName("降级语义：写失败不抛、读失败保守放行、清理失败静默")
    void degradationSemanticsKept() throws IOException {
        String code = stripComments(read(daoPath()));
        // 写：两个 catch（SQLException + RuntimeException）都只告警
        String insertBody = bodyOf(code, "public void insert(String tokenHash, long expiresAt)");
        assertTrue(insertBody.contains("catch (SQLException e)"),
                "落库失败要单独告警");
        assertTrue(insertBody.contains("catch (RuntimeException e)"),
                "DatabaseManager 未初始化（如单元测试环境）也要降级为内存模式");
        assertTrue(insertBody.contains("System.err.println(\"[TokenBlacklist] 警告：吊销记录落库失败，重启后可能失效: \" + e.getMessage())"),
                "落库失败的告警文案逐字保留");
        assertTrue(insertBody.contains("System.err.println(\"[TokenBlacklist] 警告：数据库未就绪，吊销仅存内存: \" + e.getMessage())"),
                "数据库未就绪的告警文案逐字保留");
        // 读：SQLException | RuntimeException 一起接，保守放行
        String findBody = bodyOf(code, "public Long findExpiresAt(String tokenHash)");
        assertTrue(findBody.contains("catch (SQLException | RuntimeException e)"),
                "查询失败要保守放行（不误伤正常用户）");
        assertTrue(findBody.contains("System.err.println(\"[TokenBlacklist] 警告：吊销名单查询失败: \" + e.getMessage())"),
                "查询失败的告警文案逐字保留");
        String loadBody = bodyOf(code, "public List<BlacklistEntry> findUnexpired(long now)");
        assertTrue(loadBody.contains("System.err.println(\"[TokenBlacklist] 警告：载入历史吊销名单失败: \" + e.getMessage())"),
                "载入失败的告警文案逐字保留");
        assertTrue(loadBody.contains("return new ArrayList<>();"),
                "载入失败要返回空列表（启动时不该让服务起不来）");
        // 清理：静默
        String cleanBody = bodyOf(code, "public int deleteExpiredBefore(long now)");
        assertTrue(cleanBody.contains("catch (SQLException | RuntimeException ignored)"),
                "清理失败不影响主流程");
        // DAO 里一个 RuntimeException 都不该往外抛
        assertFalse(code.contains("throw new RuntimeException"),
                "认证链路的降级路径不能变成抛异常：DB 挂了也要能登出、能鉴权");
    }

    @Test
    @DisplayName("哈希与过期解析留在服务层，DAO 不认识 token 明文")
    void hashingAndExpiryStayInService() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("MessageDigest"), "SHA-256 摘要属于服务层，DAO 只存摘要");
        assertFalse(dao.contains("JwtUtil"), "过期时间解析属于服务层");
        assertFalse(dao.contains("DEFAULT_RETENTION_MS"), "兜底保留期属于服务层");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("MessageDigest.getInstance(\"SHA-256\")"),
                "哈希必须留在服务层");
        assertTrue(service.contains("return System.currentTimeMillis() + DEFAULT_RETENTION_MS;"),
                "取不到 exp 时的兜底保留期必须保留");
        assertTrue(service.contains("blacklist.put(tokenHash, expiresAt);"),
                "必须先写内存再落库：保证本进程内立即生效");
    }

    @Test
    @DisplayName("内存优先、DB 兜底、回填缓存的判定顺序不能变")
    void memoryFirstThenDatabase() throws IOException {
        String service = stripComments(read(servicePath()));
        int memoryIdx = service.indexOf("Long expiresAt = blacklist.get(tokenHash);");
        int dbIdx = service.indexOf("Long dbExpiry = queryFromDatabase(tokenHash);");
        int backfillIdx = service.indexOf("blacklist.put(tokenHash, dbExpiry);");
        assertTrue(memoryIdx >= 0 && dbIdx > memoryIdx && backfillIdx > dbIdx,
                "必须先查内存、未命中再查库、查到后回填 —— 否则每次鉴权都打库");
        // 临界值：过期用 < 而不是 <=
        assertTrue(service.contains("if (expiresAt < System.currentTimeMillis())"),
                "内存里的过期判定必须用 < （临界值逐字保留）");
        assertTrue(service.contains("if (dbExpiry < System.currentTimeMillis())"),
                "DB 回来的过期判定必须用 < （临界值逐字保留）");
        assertTrue(service.contains("blacklist.entrySet().removeIf(entry -> entry.getValue() < now);"),
                "清理内存也用 < ，与 DB 的 expires_at < ? 保持一致");
        assertTrue(service.contains("DAO.deleteExpiredBefore(now);"),
                "DB 清理必须用同一个 now，不能另取时间");
    }

    @Test
    @DisplayName("clear 只清内存、clearAll 连库一起清，区别不能合并")
    void clearVersusClearAll() throws IOException {
        String service = stripComments(read(servicePath()));
        String clearBody = bodyOf(service, "public static void clear()");
        String clearAllBody = bodyOf(service, "public static void clearAll()");
        assertTrue(clearBody.contains("blacklist.clear();"), "clear 必须清内存");
        assertFalse(clearBody.contains("DAO.deleteAll()"),
                "clear 不该删库（测试与调试用，模拟重启靠它）");
        assertTrue(clearAllBody.contains("blacklist.clear();"), "clearAll 必须清内存");
        assertTrue(clearAllBody.contains("DAO.deleteAll()"), "clearAll 必须连库一起清");
    }

    @Test
    @DisplayName("载入条数的输出与空列表语义保留")
    void loadFromDatabaseOutputKept() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("System.out.println(\"[TokenBlacklist] 已载入 \" + loaded + \" 条历史吊销记录\")"),
                "载入成功的输出文案逐字保留");
        assertTrue(service.contains("DAO.findUnexpired(now)"),
                "载入必须走 DAO 的 Connection 版之外的公开重载");
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("public record BlacklistEntry(String tokenHash, long expiresAt)"),
                "两列一起取回，避免同一条记录读两次");
        assertEquals(1, countOccurrences(dao, "new BlacklistEntry(rs.getString(\"token_hash\"), rs.getLong(\"expires_at\"))"),
                "按列名取 token_hash 与 expires_at（只在 Connection 版里写一次）");
    }
}
