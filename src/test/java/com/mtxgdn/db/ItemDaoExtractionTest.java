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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 背包/装备模块的 JDBC 边界：服务层不碰连接，DAO 的每个方法都能在事务里用。
 *
 * <p><b>缺陷形态</b>：{@code ItemService} 原来 964 行里 10 处
 * {@code DatabaseManager.getConnection()}，而且它是全项目被调用最广的服务之一
 * （{@code addItem}/{@code removeItem} 在几十个事务的 lambda 内被调用）。
 * 这里最危险的是 upsert 的两套方言分支在服务层和 DAO 里各写一遍就会漂移。</p>
 */
class ItemDaoExtractionTest {

    private static final Path SERVICE = Paths.get("src/main/java/com/mtxgdn/game/service/ItemService.java");
    private static final Path DAO = Paths.get("src/main/java/com/mtxgdn/db/ItemDAO.java");

    @Test
    @DisplayName("服务层不再自己开连接、不再写 SQL")
    void serviceLayerHasNoJdbc() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertFalse(code.contains("getConnection("), "服务层不该自己开连接");
        assertFalse(code.contains("PreparedStatement"), "服务层不该再写 PreparedStatement");
        assertFalse(code.contains("ResultSet"), "服务层不该再处理 ResultSet");
        assertFalse(code.contains("SELECT "), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("INSERT INTO"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("UPDATE players_items"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("DELETE FROM"), "服务层不该再出现 SQL 语句");
        assertFalse(code.contains("ON CONFLICT"), "SQLite 的 upsert 分支不该留在服务层");
        assertFalse(code.contains("ON DUPLICATE KEY"), "MySQL 的 upsert 分支不该留在服务层");
        assertFalse(code.contains("getForUpdateClause"), "方言的锁子句不该在服务层拼");
    }

    @Test
    @DisplayName("服务层仍持有 DAO，灵石分档与容量逻辑留在服务层")
    void serviceStillUsesTheDao() throws IOException {
        String code = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        assertTrue(code.contains("new ItemDAO()"), "服务层应持有 ItemDAO");
        assertTrue(code.contains("ITEM_DAO."), "服务层应通过 DAO 读写");
        // 灵石分档换算、合并压缩是纯业务
        for (String anchor : List.of("removeSpiritStonesFromAllGrades", "compressSpiritStones",
                "addSpiritStonesAuto", "exchangeSpiritStones", "getStoneName",
                "checkCapacity", "validateAddParams", "validateRemoveParams")) {
            assertTrue(code.contains(anchor), "业务逻辑 " + anchor + " 应仍在服务层");
        }
        // markDirty 是跨模块副作用，必须留在服务层
        assertTrue(code.contains("PlayerDataService.markDirty"),
                "markDirty 是缓存失效副作用，应留在服务层而不是混进 DAO");
        // 装备/使用物品的效果执行是业务
        assertTrue(code.contains("BuffEffect"), "装备 Buff 效果应仍在服务层");
        assertTrue(code.contains("PluginEventManager.fireQuietly"), "插件事件应仍在服务层");
    }

    @Test
    @DisplayName("DAO 每个 public 方法都有 Connection 首参重载")
    void everyDaoMethodHasConnectionOverload() {
        List<String> missing = new ArrayList<>();
        for (Method m : ItemDAO.class.getMethods()) {
            if (m.getDeclaringClass() != ItemDAO.class) continue;
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0) continue;
            if (m.getParameterTypes()[0] == Connection.class) continue;
            boolean found = false;
            for (Method other : ItemDAO.class.getMethods()) {
                if (other.getDeclaringClass() != ItemDAO.class) continue;
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
    @DisplayName("DAO 不管事务：没有 setAutoCommit/commit/rollback")
    void daoOwnsNoTransaction() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        int publicMethods = 0;
        for (Method m : ItemDAO.class.getMethods()) {
            if (m.getDeclaringClass() == ItemDAO.class
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
        assertFalse(code.contains("markDirty"), "DAO 不该触发缓存失效这种跨模块副作用");
    }

    @Test
    @DisplayName("upsert 的两个方言分支都还在，且只在 DAO 里出现一次")
    void upsertDialectsKeptOnce() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        assertTrue(code.contains("INSERT INTO players_items"), "背包 upsert 不能丢");
        assertTrue(code.contains("ON CONFLICT(player_id, item_key)"), "SQLite 的背包 upsert 分支不能丢");
        assertTrue(code.contains("ON DUPLICATE KEY UPDATE quantity"), "MySQL 的背包 upsert 分支不能丢");
        assertTrue(code.contains("INSERT INTO players_equipment"), "装备 upsert 不能丢");
        assertTrue(code.contains("ON CONFLICT(player_id, slot)"), "SQLite 的装备 upsert 分支不能丢");
        assertTrue(code.contains("ON DUPLICATE KEY UPDATE item_key"), "MySQL 的装备 upsert 分支不能丢");
        // 背包 upsert 与装备 upsert 各一处方言判断（自取连接的重载直接委托，
        // 不在自己体内重复分支），所以是 2 次
        assertEquals(2, countOccurrences(code, "isSqlite()"),
                "背包 upsert 与装备 upsert 各一次方言判断");
    }

    @Test
    @DisplayName("原子扣除 + 清理零数量行的两步必须都在 Connection 重载里")
    void atomicRemoveKeepsCleanupInsideTransaction() throws IOException {
        String code = stripComments(Files.readString(DAO, StandardCharsets.UTF_8));
        // 扣除与清理零行必须共用同一条连接：分开两次连接时，
        // 清理可能落在另一次提交里，中间失败会留下 quantity = 0 的僵尸行
        assertTrue(code.contains("quantity >= ?"), "原子扣除的条件不能丢（消除 TOCTOU）");
        assertEquals(2, countOccurrences(code, "cleanZeroQuantity("),
                "自取连接版与 Connection 版都要清理零数量行");
        assertTrue(code.contains("quantity <= 0"), "零数量行的判断条件不能丢");
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
