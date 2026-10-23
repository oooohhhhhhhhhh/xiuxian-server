package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志文件必须滚动清理，默认级别不该是 DEBUG。
 *
 * <p><b>缺陷形态</b>：{@code GameLogger} 每次启动新建
 * {@code server-yyyyMMdd-HHmmss.log}，<b>从不删除旧的</b>；
 * 而 {@code logging.level} 默认是 {@code DEBUG}。
 * 两者叠加的后果是：每重启一次就多一个几十 MB 的文件，{@code log/} 只增不减，
 * 长时间运维的服务器最终被日志撑满磁盘 —— 而磁盘满会连带数据库写失败，
 * 表现为"莫名其妙所有写操作都报错"。</p>
 *
 * <p>修复：新增 {@code logging.max_files}（默认 10），启动时只保留最新的若干个。
 * 本测试用一个临时目录验证清理逻辑本身。</p>
 */
class GameLoggerRetentionTest {

    @Test
    @DisplayName("超出 max_files 时删除最旧的日志文件")
    void prunesOldestFiles(@TempDir Path tempDir) throws Exception {
        // 造 15 个旧日志文件，名字格式与真实一致（时间戳字典序即时间序）
        for (int i = 1; i <= 15; i++) {
            Files.writeString(tempDir.resolve(String.format("server-202601%02d-120000.log", i)),
                    "old\n", StandardCharsets.UTF_8);
        }
        // 非日志文件不该被碰
        Path keepMe = tempDir.resolve("important.txt");
        Files.writeString(keepMe, "keep\n", StandardCharsets.UTF_8);

        invokePrune(tempDir, 10);

        List<String> remaining = listServerLogs(tempDir);
        assertEquals(10, remaining.size(), "应只保留最近 10 个，实际: " + remaining);
        assertTrue(remaining.contains("server-20260115-120000.log"), "最新的必须保留");
        assertTrue(!remaining.contains("server-20260101-120000.log"), "最旧的必须被删除");
        assertTrue(Files.exists(keepMe), "非日志文件绝不能被删");
    }

    @Test
    @DisplayName("max_files <= 0 表示不清理")
    void nonPositiveMaxFilesKeepsEverything(@TempDir Path tempDir) throws Exception {
        for (int i = 1; i <= 12; i++) {
            Files.writeString(tempDir.resolve(String.format("server-202601%02d-120000.log", i)),
                    "old\n", StandardCharsets.UTF_8);
        }
        invokePrune(tempDir, 0);
        assertEquals(12, listServerLogs(tempDir).size(), "max_files=0 表示保留全部");
    }

    @Test
    @DisplayName("文件数不超过上限时不做任何删除")
    void underLimitKeepsAll(@TempDir Path tempDir) throws Exception {
        for (int i = 1; i <= 3; i++) {
            Files.writeString(tempDir.resolve(String.format("server-202601%02d-120000.log", i)),
                    "old\n", StandardCharsets.UTF_8);
        }
        invokePrune(tempDir, 10);
        assertEquals(3, listServerLogs(tempDir).size());
    }

    // ==================== 夹具 ====================

    /** pruneOldLogFiles 是私有方法，这里用反射调用它并临时覆盖 max_files 配置。 */
    private static void invokePrune(Path dir, int maxFiles) throws Exception {
        try {
            AppConfig.setRuntimeOverride("logging.max_files", String.valueOf(maxFiles));
            java.lang.reflect.Method m = GameLogger.class
                    .getDeclaredMethod("pruneOldLogFiles", Path.class);
            m.setAccessible(true);
            m.invoke(null, dir);
        } finally {
            AppConfig.clearRuntimeOverrides();
        }
    }

    private static List<String> listServerLogs(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("server-") && n.endsWith(".log"))
                    .sorted()
                    .toList();
        }
    }
}
