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
 * 功法模块的 JDBC 边界：服务层不碰连接，DAO 的每个方法都能在事务里用。
 *
 * <p><b>缺陷形态</b>：{@code TechniqueService} 原来 460 行里 11 处
 * {@code DatabaseManager.getConnection()}，学习方法/升级方法更是把
 * "扣金币 → 扣灵石 → 写 players_techniques" 三步塞进
 * {@code runTransaction} 的 lambda，而 lambda 里又各自 new 了
 * {@code PreparedStatement}。读代码时要在业务规则和 JDBC 样板之间来回翻，
 * 而且"事务里到底用的是哪条连接"这件事只能靠肉眼确认。</p>
 */
class TechniqueDaoExtractionTest {

    private static final Path SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/TechniqueService.java");
    private static final Path DAO = Paths.get("src/main/java/com/mtxgdn/db/TechniqueDAO.java");

    @Test
    @DisplayName("服务层不再自己开连接、也不再写 SQL")
    void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("getConnection("), "服务层不该自己开连接");
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE "), "服务层不该再出现 SQL 语句");
    }

    @Test
    @DisplayName("服务层仍然持有 DAO（不是删掉了功能）")
    void serviceStillUsesTheDao() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertTrue(code.contains("new TechniqueDAO()"), "服务层应持有 TechniqueDAO");
        assertTrue(code.contains("TECHNIQUE_DAO."), "服务层应通过 DAO 读写");
        // 业务规则必须留在服务层
        for (String anchor : List.of("MAX_EQUIPPED", "applyTechniqueBonuses", "removeTechniqueBonuses",
                "insertDefaultTechniques", "learnTechnique", "upgradeTechnique")) {
            assertTrue(code.contains(anchor), "业务逻辑 " + anchor + " 应仍在服务层");
        }
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : TechniqueDAO.class.getMethods()) {
            if (m.getDeclaringClass() != TechniqueDAO.class) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            // 找同名同参个数、首参为 Connection 的重载
            boolean found = false;
            for (Method other : TechniqueDAO.class.getMethods()) {
                if (other.getDeclaringClass() != TechniqueDAO.class) continue;
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
                "这些 DAO 方法没有 Connection 首参重载，事务内调用只能另开连接"
                        + "（SQLite 下会 SQLITE_BUSY_SNAPSHOT）：\n" + String.join("\n", missing));
    }

    @Test
    @DisplayName("收 Connection 的方法不许自己开连接或碰事务")
    void connectionOverloadsNeverOpenTheirOwnConnection() throws IOException {
        // 逐个取方法体在解析上很脆（0 参/1 参重载同名），
        // 这里改成两条更强的整体约束：
        // 1) getConnection( 的出现次数必须等于 public 方法数
        //    （每个方法恰好一个"自取连接"的重载，Connection 重载一个都没有）
        // 2) DAO 里不许出现 setAutoCommit/commit/rollback
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        int publicMethods = 0;
        for (Method m : TechniqueDAO.class.getMethods()) {
            if (m.getDeclaringClass() == TechniqueDAO.class
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() > 0) {
                publicMethods++;
            }
        }
        int getConnectionCalls = countOccurrences(code, "getConnection(");
        assertTrue(getConnectionCalls <= publicMethods,
                "DAO 里 getConnection( 出现 " + getConnectionCalls + " 次，但 public 方法只有 "
                        + publicMethods + " 个 —— 说明有方法自己开了连接，"
                        + "而不是只用调用方传进来的那条（事务会被拆成两个提交单元）");
        assertFalse(code.contains("setAutoCommit("),
                "DAO 不该动 autoCommit，事务归属调用方");
        assertFalse(code.contains("runTransaction("),
                "DAO 不该自己开事务");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
    }

    @Test
    @DisplayName("DAO 不管事务：没有 setAutoCommit/commit/rollback")
    void daoOwnsNoTransaction() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        assertFalse(code.contains("setAutoCommit("), "DAO 不该动 autoCommit");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
    }

    @Test
    @DisplayName("全仓库 runTransaction 的 lambda 内不许 getConnection()")
    void noNestedConnectionInsideTransactions() throws IOException {
        try (var walk = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                // runTransaction 的声明文件自己就是拿连接的那个，跳过
                if (file.getFileName().toString().equals("DatabaseManager.java")) continue;
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                int idx = 0;
                while ((idx = code.indexOf("runTransaction(", idx)) >= 0) {
                    // 取 lambda 体（第一个 { 到配平）
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
                            file.getFileName() + " 的事务 lambda 里又开了连接："
                                    + "SQLite 下会 SQLITE_BUSY_SNAPSHOT，连接池也会被借空");
                    idx = end;
                }
            }
        }
    }

    @Test
    @DisplayName("服务层没有新增定时任务（重构前这个类就没有）")
    void serviceGainedNoBackgroundScheduler() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("scheduleAtFixedRate"),
                "TechniqueService 原来没有定时任务，重构不该顺手加一个");
        assertFalse(code.contains("ScheduledExecutorService"),
                "TechniqueService 原来没有定时任务，重构不该顺手加一个");
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
