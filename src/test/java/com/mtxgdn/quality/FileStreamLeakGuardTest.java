package com.mtxgdn.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件流必须关闭 —— 静态守卫。
 *
 * <p><b>缺陷形态</b>：{@code BlacklistService.loadRawList()} 与
 * {@code OneBotGroupConfigService.loadRawList()} 都写成
 * {@code yaml.load(Files.newInputStream(configPath))}。SnakeYAML 的 {@code load}
 * <b>不会关闭</b>传入的流，于是每次调用泄漏一个文件句柄。而这两个方法都在消息过滤/路由链路上，
 * 每条群消息都会走一遍 —— 跑久了句柄耗尽，表现为"配置文件突然读不出来"，
 * 严重时整个进程再也开不了文件（Windows 上尤其明显，因为默认句柄上限更低）。</p>
 *
 * <p>这种泄漏靠单元测试很难稳定复现（要真的把句柄耗尽），所以用静态扫描守卫：
 * 源码里每一处 {@code Files.newInputStream(...)} 都必须出现在 {@code try (} 头部
 * （即 try-with-resources），否则用例失败。</p>
 */
class FileStreamLeakGuardTest {

    /** 允许"上一行/本行是 try ( 头部"的容忍行数。 */
    private static final int TRY_LOOKBACK_LINES = 3;

    @Test
    @DisplayName("src/main/java 下不得出现未关闭的 Files.newInputStream")
    void noUnclosedFileInputStreams() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!line.contains("Files.newInputStream(")) {
                        continue;
                    }
                    if (line.contains("//")) {
                        // 注释里提到不算
                        int codeStart = line.indexOf("Files.newInputStream(");
                        if (line.indexOf("//") < codeStart) {
                            continue;
                        }
                    }
                    if (!isInsideTryWithResources(lines, i)) {
                        offenders.add(file + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下位置的 Files.newInputStream 不在 try-with-resources 里，会泄漏文件句柄：\n"
                        + String.join("\n", offenders));
    }

    /** 本行或前几行是否以 {@code try (} 开头。 */
    private static boolean isInsideTryWithResources(List<String> lines, int index) {
        int from = Math.max(0, index - TRY_LOOKBACK_LINES);
        for (int i = from; i <= index; i++) {
            if (lines.get(i).contains("try (")) {
                return true;
            }
        }
        return false;
    }
}
