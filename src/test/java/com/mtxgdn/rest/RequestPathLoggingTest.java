package com.mtxgdn.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个请求都会跑到的过滤器里不能有 {@code System.out}。
 *
 * <p><b>缺陷形态</b>：{@code EntityBufferFilter} 原来每处理一个 POST/PUT 就
 * {@code System.out.println("[EntityBuffer] 缓冲 N bytes")}。两层问题：</p>
 * <ol>
 *   <li><b>噪音</b>：每个写请求一行，正常运行时把真正的告警淹没在刷屏里；</li>
 *   <li><b>不进日志文件</b>：{@code System.out} 只到控制台，只有 {@code GameLogger}
 *       会写文件，所以事后排查根本找不到这些行 —— 打了等于没打。</li>
 * </ol>
 *
 * <p>修复：改成 DEBUG 级别的 {@code GameLogger}（默认 INFO 下不输出，
 * 需要时调 {@code logging.level=DEBUG} 就能在日志文件里看到）。</p>
 */
class RequestPathLoggingTest {

    private static final Path ENTITY_BUFFER_FILTER =
            Paths.get("src/main/java/com/mtxgdn/rest/EntityBufferFilter.java");

    @Test
    @DisplayName("EntityBufferFilter 不得使用 System.out / System.err")
    void entityBufferFilterDoesNotPrintToStdout() throws IOException {
        String code = stripComments(read(ENTITY_BUFFER_FILTER));
        assertFalse(code.contains("System.out"),
                "EntityBufferFilter 里还有 System.out —— 每个 POST/PUT 都会打印一行，"
                        + "而且不进日志文件");
        assertFalse(code.contains("System.err"),
                "EntityBufferFilter 里还有 System.err（同样不进日志文件）");
    }

    @Test
    @DisplayName("EntityBufferFilter 必须用 GameLogger")
    void entityBufferFilterUsesGameLogger() throws IOException {
        String code = read(ENTITY_BUFFER_FILTER);
        assertTrue(code.contains("GameLogger"),
                "EntityBufferFilter 必须改用 GameLogger，才能写进日志文件");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "找不到文件: " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String stripComments(String code) {
        String noBlock = code.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\\R")) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
