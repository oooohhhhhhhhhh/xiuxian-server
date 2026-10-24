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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 好友模块的 JDBC 边界：服务层不碰连接，接受申请的两步共用同一条连接。
 *
 * <p><b>缺陷形态</b>：{@code FriendService} 原来 290 行里 7 处
 * {@code DatabaseManager.getConnection()}，而且接受好友申请是
 * "改申请状态 → 补一条反向记录" 两步 —— 原实现靠把 {@code conn}
 * 手工传给 {@code insertReverseRelationship} 保证两步同连接。
 * 这个约定只存在于作者脑子里，抽 DAO 时一旦让两步各自自取连接，
 * 就会静默地变成两次独立提交。</p>
 */
class FriendDaoExtractionTest {

    private static final Path SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/FriendService.java");
    private static final Path DAO = Paths.get("src/main/java/com/mtxgdn/db/FriendDAO.java");

    @Test
    @DisplayName("服务层不再自己写 SQL、不再处理 ResultSet")
    void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT OR REPLACE"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE friends"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("DELETE FROM"), "服务层不该再出现 SQL 语句");
    }

    @Test
    @DisplayName("服务层仍持有 DAO，去重与补全逻辑留在服务层")
    void serviceStillUsesTheDao() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertTrue(code.contains("new FriendDAO()"), "服务层应持有 FriendDAO");
        assertTrue(code.contains("FRIEND_DAO."), "服务层应通过 DAO 读写");
        // 双向去重是纯业务规则
        assertTrue(code.contains("seenFriendIds"), "好友列表去重应仍在服务层");
        assertTrue(code.contains("populateFriendPlayerInfo"), "补全好友名称/境界应仍在服务层");
        assertTrue(code.contains("getRealmName"), "境界名翻译应仍在服务层");
        // 拜访玩法是纯业务
        assertTrue(code.contains("visitFriend"), "拜访玩法应仍在服务层");
        assertTrue(code.contains("DAILY_VISITS"), "每日拜访限制应仍在服务层");
    }

    @Test
    @DisplayName("接受申请的两步必须共用同一条连接")
    void acceptRequestSharesOneConnection() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        // 只允许 acceptRequest 里出现一次 getConnection（用于把同一条 conn 传给两步）
        assertEquals(1, countOccurrences(code, "getConnection("),
                "服务层只应在 acceptRequest 里取一次连接，把同一条 conn 传给两步；"
                        + "多余的自取连接会让两步各自提交");
        // 关键：两步必须用同一个 conn 变量
        int acceptStart = code.indexOf("public boolean acceptRequest(");
        int acceptEnd = code.indexOf("public boolean rejectRequest(");
        assertTrue(acceptStart >= 0 && acceptEnd > acceptStart, "找不到 acceptRequest 方法");
        String acceptBody = code.substring(acceptStart, acceptEnd);
        assertTrue(acceptBody.contains("FRIEND_DAO.acceptRequest(conn,"),
                "改申请状态必须用调用方的 conn");
        assertTrue(acceptBody.contains("FRIEND_DAO.insertReverseRelationship(conn,"),
                "补反向记录必须用同一条 conn，否则中间失败会留下"
                        + "「申请已接受但没有反向记录」的脏数据，对方列表里看不到你");
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : FriendDAO.class.getMethods()) {
            if (m.getDeclaringClass() != FriendDAO.class) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            boolean found = false;
            for (Method other : FriendDAO.class.getMethods()) {
                if (other.getDeclaringClass() != FriendDAO.class) continue;
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
        for (Method m : FriendDAO.class.getMethods()) {
            if (m.getDeclaringClass() == FriendDAO.class
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
    @DisplayName("SQLite / MySQL 两个方言分支都保留了")
    void bothDialectsKept() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        assertTrue(code.contains("INSERT OR REPLACE INTO friends"), "SQLite 分支不能丢");
        assertTrue(code.contains("ON DUPLICATE KEY UPDATE"), "MySQL 分支不能丢");
        assertTrue(code.contains("DatabaseManager.isSqlite()"), "方言选择逻辑不能丢");
    }

    @Test
    @DisplayName("待处理申请只 SELECT 5 列，按列数填字段的逻辑不能丢")
    void pendingRequestsColumnCountHandling() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        assertTrue(code.contains("getMetaData().getColumnCount()"),
                "按列数决定填哪些字段的逻辑不能丢：findFriends 查 6 列，"
                        + "findPendingRequests 只查 5 列，硬读 updated_at 会抛「无效的列名」");
        assertTrue(code.contains("f.setUpdatedAt("), "6 列时要填 updated_at");
        assertTrue(code.contains("f.setCreatedAt("), "5 列以上都要填 created_at");
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
