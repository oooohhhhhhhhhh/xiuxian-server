package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮箱验证码模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code VerificationCodeService} 原来 147 行里 3 处
 * {@code DatabaseManager.getConnection()}，而它守着三条安全语义：
 * 限流（60 秒一条）、有效期（5 分钟）、失败锁定（3 次后作废全部并锁 5 分钟）。
 * 其中限流检查与插入必须落在同一条连接上 —— 分开两次连接时，两个并发请求
 * 可以同时看到"60 秒内没有发送记录"，然后双双插入，限流形同虚设，
 * 而它挡的是短信/邮件轰炸。</p>
 */
class VerificationCodeDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return VerificationCodeDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/service/VerificationCodeService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/VerificationCodeDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 验证码生成与安全参数
                "SecureRandom RANDOM",
                "CODE_LENGTH = 6",
                "EXPIRY_MINUTES = 5",
                "MAX_VERIFY_FAILURES = 3",
                "VERIFY_LOCK_MS = 5 * 60 * 1000L",
                "generateCode()",
                // 失败次数与锁定期
                "VERIFY_FAILURES.compute(",
                "rec[0] >= MAX_VERIFY_FAILURES",
                "rec[1] = now + VERIFY_LOCK_MS",
                "isVerifyLocked(email)",
                "recordVerifyFailure(email)",
                // 一次性消费：用掉即删
                "VERIFY_FAILURES.remove(email)",
                // 对玩家可见的文案
                "发送过于频繁，请稍后再试",
                // 限流窗口可配置
                "AppConfig.getInt(\"verify_code.rate_limit_seconds\"");
    }

    @Test
    @DisplayName("四个意图各一对重载，自取连接版整体委托")
    void everyIntentHasBothOverloads() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{"findLatestSentId", "insertCode", "findValidCodeId", "deleteCodes"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用。
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 自取连接版必须整体委托，不在自己体内重复 SQL。
        // 按方法签名切片而不是按缩进匹配，重新格式化不断言。
        for (String sig : new String[]{
                "public Long findLatestSentId(String email, Instant sentAfter)",
                "public void insertCode(String email, String code, Instant expiresAt, Instant sentAt)",
                "public Long findValidCodeId(String email, String code, Instant now)",
                "public int deleteCodes(String email)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"),
                    sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT INTO") || body.contains("DELETE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("SQL 逐字保留：ORDER BY id DESC LIMIT 1 一处都不能少")
    void sqlKeptVerbatim() throws IOException {
        String code = stripComments(read(daoPath()));
        // 委托之后每条 SQL 只写在 Connection 版里，所以恰好 1 次。
        assertEquals(1, countOccurrences(code, "\"SELECT id FROM verification_codes WHERE email = ? AND sent_at > ? ORDER BY id DESC LIMIT 1\""),
                "限流查询逐字保留");
        assertEquals(1, countOccurrences(code, "\"SELECT id FROM verification_codes WHERE email = ? AND code = ? AND expires_at > ? \"\n                + \"ORDER BY id DESC LIMIT 1\""),
                "校验查询逐字保留");
        assertEquals(1, countOccurrences(code, "\"INSERT INTO verification_codes (email, code, expires_at, sent_at) VALUES (?, ?, ?, ?)\""),
                "插入语句逐字保留");
        assertEquals(1, countOccurrences(code, "\"DELETE FROM verification_codes WHERE email = ?\""),
                "删除语句逐字保留（按 email 删，不是按 id 删）");
        // 限流与校验都只看最新一条：ORDER BY + LIMIT 是语义的一部分
        assertEquals(2, countOccurrences(code, "ORDER BY id DESC LIMIT 1"),
                "两个查询各一处，去掉 ORDER BY/LIMIT 会让 rs.next() 读到任意一条");
        // 有效期窗口用 > 不是 >=
        assertEquals(1, countOccurrences(code, "expires_at > ?"),
                "有效期窗口必须用 > （临界值逐字保留）");
        // 限流窗口用 > 不是 >=
        assertEquals(1, countOccurrences(code, "sent_at > ?"),
                "限流窗口必须用 >");
    }

    @Test
    @DisplayName("限流检查与插入共用同一条连接")
    void rateLimitCheckAndInsertShareOneConnection() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("DatabaseManager.runTransaction(conn -> {"),
                "限流检查与插入必须是一条事务，否则两个并发请求可以同时通过限流");
        assertTrue(service.contains("codeDao.findLatestSentId(conn, email,"),
                "限流查询必须用事务连接");
        assertTrue(service.contains("codeDao.insertCode(conn, email, code,"),
                "插入必须用同一条事务连接");
        // 服务层零 getConnection
        assertEquals(0, countOccurrences(service, "getConnection("),
                "服务层不该自己开连接");
        assertFalse(service.contains("setAutoCommit("), "事务边界已在 runTransaction 内");
        assertFalse(service.contains("conn.commit()"), "事务边界已在 runTransaction 内");
    }

    @Test
    @DisplayName("有效期窗口与失败锁定的时间基准一致")
    void expiryWindowAndLockUseConsistentTimeBase() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("Instant.now().plusSeconds(EXPIRY_MINUTES * 60L)"),
                "有效期 = 现在 + 5 分钟，逐字保留");
        assertTrue(service.contains("codeDao.findValidCodeId(email, code, Instant.now())"),
                "校验用的 now 由服务层传入，与锁定判断同源");
        assertTrue(service.contains("return rec != null && rec[1] > System.currentTimeMillis();"),
                "锁定判断用 > （临界值逐字保留）");
        // DAO 里不该自己取时间
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("Instant.now()"),
                "DAO 不该自己取当前时间：有效期窗口与失败锁定必须同源，"
                        + "否则两次取时间差出一毫秒就会让临界点的验证码时好时坏");
        assertFalse(dao.contains("System.currentTimeMillis()"),
                "DAO 不该自己取当前时间");
    }

    @Test
    @DisplayName("一次性消费：命中后删掉该邮箱全部验证码")
    void oneTimeConsumptionDeletesAllCodesForEmail() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("deleteCodes(email);"),
                "验证通过后必须删码，逐字保留");
        // 锁定时也作废全部验证码，走同一条删除
        assertTrue(service.contains("try {") && service.contains("deleteCodes(email);"),
                "达到失败阈值时也要作废该邮箱全部验证码");
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("DELETE FROM verification_codes WHERE email = ?"),
                "删除按 email 而不是按 id：留着旧码会让它被反复使用");
        assertFalse(dao.contains("DELETE FROM verification_codes WHERE id = ?"),
                "不能改成按 id 删 —— 锁定时作废的是全部验证码");
    }

    @Test
    @DisplayName("失败次数与锁定期留在服务层，DAO 不掺安全语义")
    void failureCountingStaysInService() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("MAX_VERIFY_FAILURES"), "失败阈值属于服务层");
        assertFalse(dao.contains("VERIFY_LOCK_MS"), "锁定期属于服务层");
        assertFalse(dao.contains("VERIFY_FAILURES"), "失败计数属于服务层");
        assertFalse(dao.contains("SecureRandom"), "验证码生成属于服务层");
        assertFalse(dao.contains("AppConfig"), "限流秒数的可配置属于服务层");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("if (rec[0] >= MAX_VERIFY_FAILURES)"),
                "达到阈值才锁定，逐字保留");
        assertTrue(service.contains("rec[0] = 0;"),
                "锁定后把失败计数归零，逐字保留");
        assertTrue(service.contains("if (rec == null || rec[1] > now)"),
                "锁定期内的失败要重新计数（旧记录作废），逐字保留");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("throw new RuntimeException(\"查询发送记录失败\", e)"),
                "限流查询失败文案逐字保留");
        assertTrue(dao.contains("throw new RuntimeException(\"验证码校验失败\", e)"),
                "校验失败文案逐字保留");
        assertTrue(dao.contains("throw new RuntimeException(\"清理验证码失败\", e)"),
                "清理失败文案逐字保留");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("throw new RuntimeException(\"发送过于频繁，请稍后再试\")"),
                "限流命中时抛给上层的文案逐字保留");
    }

    @Test
    @DisplayName("时间戳走 Timestamp，Instant 在服务层与 DAO 边界上转换")
    void timestampBindingKept() throws IOException {
        String code = stripComments(read(daoPath()));
        // 四个绑定位：限流窗口 1 + 过期/发送 2 + 校验 1 = 4 处（委托后只写一次）
        assertEquals(4, countOccurrences(code, "ps.setTimestamp("),
                "四个绑定位（限流窗口、过期时间、发送时间、校验时间）");
        assertTrue(code.contains("Timestamp.from(sentAfter)"), "限流窗口用 Timestamp.from 转换");
        assertTrue(code.contains("Timestamp.from(expiresAt)"), "过期时间用 Timestamp.from 转换");
        assertTrue(code.contains("Timestamp.from(sentAt)"), "发送时间用 Timestamp.from 转换");
        assertTrue(code.contains("Timestamp.from(now)"), "校验时间用 Timestamp.from 转换");
        // 参数顺序：email / code / expires_at / sent_at
        assertTrue(code.contains("ps.setString(2, code);"),
                "第二个参数是 code，顺序不能换");
        assertTrue(code.contains("ps.setTimestamp(3, Timestamp.from(expiresAt));"),
                "第三个参数是 expires_at，顺序不能换");
        assertTrue(code.contains("ps.setTimestamp(4, Timestamp.from(sentAt));"),
                "第四个参数是 sent_at，顺序不能换");
    }
}
