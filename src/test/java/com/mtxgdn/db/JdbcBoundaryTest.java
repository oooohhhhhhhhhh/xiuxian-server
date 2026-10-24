package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局 JDBC 边界：{@code com.mtxgdn.db} 包之外不许再出现
 * {@code DatabaseManager.getConnection()}。
 *
 * <p><b>为什么要有这道测试</b>：项目里原本有 325 处 {@code getConnection()} 散落在
 * 43 个文件里。每抽一个 DAO 就少一处，但只要没有全局约束，下一个功能就会
 * 在新的服务类里再开一条连接 —— 而"在事务里另开连接"这个错误在 SQLite 下
 * 的表现是 {@code SQLITE_BUSY_SNAPSHOT}，在连接池上的表现是借空后卡满 10 秒
 * 抛 {@code Connection is not available}，堆栈还指向一条毫不相干的业务查询。
 * 这类 bug 极难定位，必须在结构上禁止。</p>
 *
 * <p><b>规则</b>：只有 {@code com.mtxgdn.db} 包内的类（DAO 与
 * {@code DatabaseManager} 自身）可以开连接。包外的每一处都必须登记在
 * {@link #ALLOWED} 里并写明理由 —— 允许的是"经过论证的例外"，
 * 不是"忘了改的地方"。</p>
 */
class JdbcBoundaryTest {

    /** 包外仍允许开连接的位置，value 是必须写清的理由。 */
    private static final Map<String, String> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put("com/mtxgdn/game/service/FriendService.java",
                "acceptRequest 取一次连接，把同一条 conn 传给 DAO 的"
                        + "「改申请状态」与「补反向记录」两步。两步必须共用连接，"
                        + "否则中间失败会留下「申请已接受但没有反向记录」的脏数据，"
                        + "对方的好友列表里看不到你。不是自取自用。");
    }

    @Test
    @DisplayName("com.mtxgdn.db 包之外没有未登记的 getConnection()")
    void noConnectionOpenedOutsideDaoPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var walk = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = Paths.get("src/main/java").relativize(file).toString()
                        .replace('\\', '/');
                // db 包内是 DAO 与 DatabaseManager 自身，允许开连接
                if (relative.startsWith("com/mtxgdn/db/")) continue;
                if (ALLOWED.containsKey(relative)) continue;

                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains("DatabaseManager.getConnection(")) {
                    violations.add(relative + " 有 " + countOccurrences(code, "DatabaseManager.getConnection(") + " 处");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "这些类在 com.mtxgdn.db 包之外自己开了连接。"
                        + "请把 SQL 抽到对应的 DAO（每个方法一对重载：自取连接版 + "
                        + "Connection 首参版），事务内一律用 Connection 版；"
                        + "确有理由必须保留的，登记进 JdbcBoundaryTest.ALLOWED 并写明理由：\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("登记在案的例外都还在用，没有变成死条目")
    void allowlistHasNoStaleEntries() throws IOException {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, String> entry : ALLOWED.entrySet()) {
            Path file = Paths.get("src/main/java", entry.getKey());
            if (!Files.exists(file)) {
                stale.add(entry.getKey() + "（文件已不存在）");
                continue;
            }
            String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!code.contains("DatabaseManager.getConnection(")) {
                stale.add(entry.getKey() + "（已经不再开连接，应从白名单移除）");
            }
        }
        assertTrue(stale.isEmpty(),
                "白名单里有过期条目，清掉它们，否则真正的例外会被淹掉：\n  "
                        + String.join("\n  ", stale));
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
