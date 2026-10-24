package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兑换码模块的 JDBC 边界：服务层不碰连接，兑换流程的四步共用同一条连接。
 *
 * <p><b>缺陷形态</b>：{@code RedeemCodeService} 原来 346 行里 7 处
 * {@code DatabaseManager.getConnection()}，而 {@code doRedeem} 是四步事务 ——
 * 查是否已兑换 → 插兑换记录 → 递增使用次数 → 发奖励。原实现靠把 {@code conn}
 * 手工传给三个 private 方法保证同连接，其中一个方法还区分"撞主键返回文案"和
 * "其它异常上抛"。抽 DAO 时这些约定全靠肉眼维持。</p>
 */
class RedeemCodeDaoExtractionTest {

    private static final Path SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/RedeemCodeService.java");
    private static final Path DAO = Paths.get("src/main/java/com/mtxgdn/db/RedeemCodeDAO.java");

    @Test
    @DisplayName("服务层不再自己开连接、不再写 SQL")
    void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("getConnection("), "服务层不该自己开连接");
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("createStatement"), "服务层不该再用 Statement");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("DELETE "), "服务层不该再出现 SQL 语句");
    }

    @Test
    @DisplayName("服务层仍持有 DAO，奖励文案拼装与过期判断留在服务层")
    void serviceStillUsesTheDao() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertTrue(code.contains("new RedeemCodeDAO()"), "服务层应持有 RedeemCodeDAO");
        assertTrue(code.contains("REDEEM_DAO."), "服务层应通过 DAO 读写");
        // 纯业务必须留在服务层
        for (String anchor : List.of("rewardMsg", "ItemRegistry.contains",
                "isExpired", "RedeemCode.STATUS_EXPIRED", "doRedeem", "createCode", "updateCode")) {
            assertTrue(code.contains(anchor), "业务逻辑 " + anchor + " 应仍在服务层");
        }
        // items_json 的序列化是服务层职责（DAO 只收字符串）
        assertTrue(code.contains("gson.toJson(items)"), "items 的 JSON 序列化应留在服务层");
    }

    @Test
    @DisplayName("兑换流程的四步必须全部显式传 conn")
    void doRedeemStepsShareOneConnection() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        int start = code.indexOf("public String doRedeem(");
        // 注释已 strip，用 setStatus 私有方法作为右边界（它在 doRedeem 之后）
        int end = code.indexOf("private void setStatus(", start);
        assertTrue(start >= 0 && end > start, "找不到 doRedeem 方法");
        String body = code.substring(start, end);
        for (String call : List.of("REDEEM_DAO.hasRedeemed(conn,", "REDEEM_DAO.insertRedemption(conn,",
                "REDEEM_DAO.incrementUses(conn,")) {
            assertTrue(body.contains(call), "兑换流程的 " + call + " 必须显式传 conn，"
                    + "否则这一步会单独提交：奖励发了但兑换记录没写上，同一个码就能反复刷");
        }
        // 事务里发奖励也必须用 conn
        assertTrue(body.contains("playerService.addGold(conn,"), "发金币必须走事务连接");
        assertTrue(body.contains("itemService.addSpiritStones(conn,"), "发灵石必须走事务连接");
        assertTrue(body.contains("playerService.addExperience(conn,"), "发经验必须走事务连接");
        assertTrue(body.contains("itemService.addItem(conn,"), "发物品必须走事务连接");
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : RedeemCodeDAO.class.getMethods()) {
            if (m.getDeclaringClass() != RedeemCodeDAO.class) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            boolean found = false;
            for (Method other : RedeemCodeDAO.class.getMethods()) {
                if (other.getDeclaringClass() != RedeemCodeDAO.class) continue;
                if (!other.getName().equals(m.getName())) continue;
                if (other.getParameterCount() != m.getParameterCount() + 1) continue;
                if (other.getParameterTypes()[0] != Connection.class) continue;
                boolean tailMatch = true;
                for (int i = 0; i < m.getParameterCount(); i++) {
                    if (other.getParameterTypes()[i + 1] != m.getParameterTypes()[i]) { tailMatch = false; break; }
                }
                if (tailMatch) { found = true; break; }
            }
            if (!found) missing.add(m.getName() + "(" + m.getParameterCount() + " 参)");
        }
        assertTrue(missing.isEmpty(),
                "这些 DAO 方法没有 Connection 首参重载：\n" + String.join("\n", missing));
    }

    @Test
    @DisplayName("DAO 不管事务：没有 setAutoCommit/commit/rollback")
    void daoOwnsNoTransaction() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        int publicMethods = 0;
        for (Method m : RedeemCodeDAO.class.getMethods()) {
            if (m.getDeclaringClass() == RedeemCodeDAO.class
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() > 0) {
                publicMethods++;
            }
        }
        int getConnectionCalls = countOccurrences(code, "getConnection(");
        assertTrue(getConnectionCalls <= publicMethods,
                "DAO 里 getConnection( 出现 " + getConnectionCalls + " 次，但 public 方法只有 "
                        + publicMethods + " 个");
        assertFalse(code.contains("setAutoCommit("), "DAO 不该动 autoCommit");
        assertFalse(code.contains("runTransaction("), "DAO 不该自己开事务");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
    }

    @Test
    @DisplayName("撞主键返回文案、其它异常上抛的语义不能丢")
    void duplicateKeyReturnsMessageOthersRethrow() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        // insertRedemption 必须区分两种 SQLException
        assertTrue(code.contains("isDuplicateKeyError(e)"), "撞主键要能被识别");
        assertTrue(code.contains("throw e"), "非主键冲突的异常必须原样上抛，不能吞");
        // 文案匹配不能改成 SQLState
        assertTrue(code.contains("\"unique\""), "靠文案匹配：SQLite/MySQL 的 SQLState 不同");
        assertTrue(code.contains("\"duplicate\""), "靠文案匹配");
        assertTrue(code.contains("\"primary key\""), "靠文案匹配");
    }

    @Test
    @DisplayName("不限次兑换码不能被误判成用满")
    void incrementUsesKeepsUnlimitedSemantics() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        // maxUses <= 0 表示不限次，此时 CASE WHEN 的第二个条件不成立，状态保持原值
        assertTrue(code.contains("current_uses + 1 >= ? AND ? > 0"),
                "maxUses <= 0（不限次）时不能把状态翻成 redeemed，这个 AND ? > 0 不能丢");
        assertTrue(code.contains("ELSE status END"), "非用满时状态必须保持原值");
    }

    @Test
    @DisplayName("expires_at 的空值处理不能丢")
    void nullTimestampHandlingKept() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        assertEquals(2, countOccurrences(code, "setNull("),
                "createCode 与 updateCode 都要把空 expires_at 写成 NULL");
        assertTrue(code.contains("Types.TIMESTAMP"), "必须显式指定 TIMESTAMP 类型");
    }

    private static void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    @Test
    @DisplayName("全仓库 runTransaction 的 lambda 内不许 getConnection()")
    void noNestedConnectionInsideTransactions() throws IOException {
        try (var walk = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("DatabaseManager.java")) continue;
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                int idx = 0;
                while ((idx = code.indexOf("runTransaction(", idx)) >= 0) {
                    int brace = code.indexOf('{', idx);
                    if (brace < 0) break;
                    int depth = 0, end = brace;
                    for (int i = brace; i < code.length(); i++) {
                        char c = code.charAt(i);
                        if (c == '{') depth++;
                        else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
                    }
                    String body = code.substring(brace, end);
                    assertFalse(body.contains("getConnection()"),
                            file.getFileName() + " 的事务 lambda 里又开了连接");
                    idx = end;
                }
            }
        }
    }

    // ==================== 夹具 ====================

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }
}
