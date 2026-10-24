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
 * 技能模块的 JDBC 边界：服务层不碰连接，DAO 的每个方法都能在事务里用。
 *
 * <p><b>缺陷形态</b>：{@code SkillService} 原来 406 行里 9 处
 * {@code DatabaseManager.getConnection()}，{@code learnSkill} 把
 * "扣金币 → 扣灵石 → 写 players_skills" 塞进 {@code runTransaction} 的 lambda，
 * lambda 里又 new 了 {@code PreparedStatement}；{@code insertDefaultSkills}
 * 更是把 15 段几百字符的 SQL 字面量硬编码在服务类里。</p>
 */
class SkillDaoExtractionTest {

    private static final Path SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/SkillService.java");
    private static final Path DAO = Paths.get("src/main/java/com/mtxgdn/db/SkillDAO.java");
    private static final Path DEFAULTS = Paths.get("src/main/java/com/mtxgdn/game/config/SkillDefaults.java");

    @Test
    @DisplayName("服务层不再自己开连接、也不再写 SQL")
    void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("getConnection("), "服务层不该自己开连接");
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("createStatement"), "服务层不该再用 Statement 灌 SQL");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE "), "服务层不该再出现 SQL 语句");
    }

    @Test
    @DisplayName("服务层仍持有 DAO，且出厂数据已搬到 config 类")
    void serviceStillUsesTheDao() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertTrue(code.contains("new SkillDAO()"), "服务层应持有 SkillDAO");
        assertTrue(code.contains("SKILL_DAO."), "服务层应通过 DAO 读写");
        assertTrue(code.contains("SkillDefaults.INSERT_STATEMENTS"),
                "出厂数据应从 SkillDefaults 来，而不是写死在服务类里");
        for (String anchor : List.of("learnSkill", "learnSkillFromBook", "addProficiency",
                "getProficiencyForNextLevel", "insertDefaultSkills")) {
            assertTrue(code.contains(anchor), "业务逻辑 " + anchor + " 应仍在服务层");
        }
        // 熟练度升级的判定循环是纯业务，必须留在服务层
        assertTrue(code.contains("while (currentLevel < maxLevel)"),
                "熟练度升级循环是业务规则，应仍在服务层");
        // 灵根加成也是业务
        assertTrue(code.contains("PROFICIENCY_BOOST"), "灵根熟练度加成应仍在服务层");
    }

    @Test
    @DisplayName("出厂技能数据一条没少、一条没改")
    void defaultSkillsDataIsIntact() throws IOException {
        String code = stripComments(Files.readString(DEFAULTS, StandardCharsets.UTF_8));
        int statements = countOccurrences(code, "INSERT INTO skills");
        assertEquals(15, statements, "出厂技能应有 15 条");
        // 每条都必须自带显式 id：重复初始化靠主键冲突跳过，没有 id 就没法幂等
        assertEquals(15, countOccurrences(code, "VALUES ("));
        assertTrue(code.contains("'烈火掌'"), "数据内容被改动了");
        assertTrue(code.contains("'万象归元'"), "数据内容被改动了");
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : SkillDAO.class.getMethods()) {
            if (m.getDeclaringClass() != SkillDAO.class) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            boolean found = false;
            for (Method other : SkillDAO.class.getMethods()) {
                if (other.getDeclaringClass() != SkillDAO.class) continue;
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
    @DisplayName("DAO 不管事务：没有 setAutoCommit/commit/rollback，且 getConnection 次数不超过方法数")
    void daoOwnsNoTransaction() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        int publicMethods = 0;
        for (Method m : SkillDAO.class.getMethods()) {
            if (m.getDeclaringClass() == SkillDAO.class
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() > 0) {
                publicMethods++;
            }
        }
        int getConnectionCalls = countOccurrences(code, "getConnection(");
        assertTrue(getConnectionCalls <= publicMethods,
                "DAO 里 getConnection( 出现 " + getConnectionCalls + " 次，但 public 方法只有 "
                        + publicMethods + " 个 —— 说明有方法自己开了连接");
        assertFalse(code.contains("setAutoCommit("), "DAO 不该动 autoCommit");
        assertFalse(code.contains("runTransaction("), "DAO 不该自己开事务");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
    }

    @Test
    @DisplayName("出厂数据批量插入保留了逐条容错的语义")
    void insertDefaultsKeepsPerRowTolerance() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        // 两个重载都必须逐条 try/catch：重复初始化时 id 主键冲突是常态，
        // 一条失败不能影响其余，也不能往上抛。
        assertEquals(2, countOccurrences(code, "createStatement()"),
                "出厂数据插入应有自取连接和 Connection 两个版本");
        assertEquals(2, countOccurrences(code, "executeUpdate(skillSql)"),
                "必须是逐条执行，才能逐条跳过冲突");
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
                            file.getFileName() + " 的事务 lambda 里又开了连接："
                                    + "SQLite 下会 SQLITE_BUSY_SNAPSHOT，连接池也会被借空");
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
