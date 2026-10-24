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
 * DAO 抽取的通用断言。
 *
 * <p>每个「服务类 → DAO」的重构都产出两个约束：服务层不再碰 JDBC，DAO 的每个
 * public 方法都有 {@code Connection} 首参重载。这些断言和具体业务无关，
 * 所以抽出来复用；业务特有的部分（哪些逻辑必须留在服务层、哪些原语义不能丢）
 * 由各服务的测试自己写。</p>
 *
 * <p>子类实现 {@link #daoClass()}、{@link #servicePath()}、{@link #daoPath()}，
 * 需要时覆盖 {@link #serviceAnchors()} 与 {@link #daoForbiddenTokens()}。</p>
 */
abstract class DaoExtractionAssertions {

    /** 被抽取的 DAO 的 Class。 */
    protected abstract Class<?> daoClass();

    /** 服务类源文件路径（相对工作目录）。 */
    protected abstract String servicePath();

    /** DAO 源文件路径（相对工作目录）。 */
    protected abstract String daoPath();

    /** 必须留在服务层的业务锚点（默认无）。 */
    protected List<String> serviceAnchors() {
        return List.of();
    }

    /** DAO 里不许出现的记号（默认无，子类按需补充）。 */
    protected List<String> daoForbiddenTokens() {
        return List.of();
    }

    @Test
    @DisplayName("服务层不再自己开连接、不再写 SQL")
    public final void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(read(servicePath()));
        assertFalse(code.contains("getConnection("), "服务层不该自己开连接");
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("createStatement"), "服务层不该再用 Statement 灌 SQL");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT OR "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE players"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("DELETE FROM"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("ON CONFLICT"), "upsert 的方言分支不该留在服务层");
        assertFalse(code.contains("ON DUPLICATE KEY"), "upsert 的方言分支不该留在服务层");
        for (String anchor : serviceAnchors()) {
            assertTrue(code.contains(anchor), "业务逻辑 " + anchor + " 应仍在服务层");
        }
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    public final void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : daoClass().getMethods()) {
            if (m.getDeclaringClass() != daoClass()) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            boolean found = false;
            for (Method other : daoClass().getMethods()) {
                if (other.getDeclaringClass() != daoClass()) continue;
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
    @DisplayName("DAO 不管事务：没有 setAutoCommit/commit/rollback，getConnection 次数不超过方法数")
    public final void daoOwnsNoTransaction() throws IOException {
        String code = stripComments(read(daoPath()));
        int publicMethods = 0;
        for (Method m : daoClass().getMethods()) {
            if (m.getDeclaringClass() == daoClass()
                    && java.lang.reflect.Modifier.isPublic(m.getModifiers())
                    && m.getParameterCount() > 0) {
                publicMethods++;
            }
        }
        int getConnectionCalls = countOccurrences(code, "getConnection(");
        assertTrue(getConnectionCalls <= publicMethods,
                "DAO 里 getConnection( 出现 " + getConnectionCalls + " 次，但 public 方法只有 "
                        + publicMethods + " 个 —— 说明有方法自己开了连接，"
                        + "而不是只用调用方传进来的那条");
        assertFalse(code.contains("setAutoCommit("), "DAO 不该动 autoCommit");
        assertFalse(code.contains("runTransaction("), "DAO 不该自己开事务");
        assertFalse(code.contains("commit("), "DAO 不该提交事务");
        assertFalse(code.contains("rollback("), "DAO 不该回滚事务");
        for (String token : daoForbiddenTokens()) {
            assertFalse(code.contains(token), "DAO 不该出现 " + token);
        }
    }

    // ==================== 夹具 ====================

    protected static String read(String relative) throws IOException {
        Path path = Paths.get(relative);
        assertTrue(Files.exists(path), "找不到源文件：" + relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 读入并去掉注释，子类做文本断言时一律走这里。 */
    protected static String readStripped(String relative) throws IOException {
        return stripComments(read(relative));
    }

    /**
     * 取出某个方法签名之后的完整代码体（含签名，到与之配平的花括号为止）。
     *
     * <p>用花括号配平而不是按缩进或定长截断：增删一个方法不会让后面的断言
     * 整体错位，重新格式化也不会断。找不到方法体右花括号时返回签名后的全部内容。</p>
     */
    protected static String bodyOf(String code, String methodSignature) {
        int start = code.indexOf(methodSignature);
        assertTrue(start >= 0, "找不到方法签名：" + methodSignature);
        int i = code.indexOf('{', start + methodSignature.length());
        if (i < 0) {
            return code.substring(start);
        }
        int depth = 0;
        for (; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(start, i + 1);
                }
            }
        }
        return code.substring(start);
    }

    protected static void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    protected static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    protected static int countOccurrences(String haystack, String needle) {
        int count = 0, from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) { count++; from += needle.length(); }
        return count;
    }
}
