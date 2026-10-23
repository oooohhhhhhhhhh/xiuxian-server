package com.mtxgdn.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jar 路径必须做百分号解码，否则路径含空格/中文时扫描器会静默失效。
 *
 * <p><b>缺陷形态</b>：{@code CommandScanner} / {@code ItemScanner} /
 * {@code ExplorationEventScanner} / {@code SecretRealmScanner} 都写
 * {@code String jarPath = url.getPath()}，然后直接 {@code new JarFile(jarPath)}。
 * {@link URL#getPath()} 返回的是<b>未解码</b>的百分号编码形式：</p>
 *
 * <ul>
 *   <li>空格：{@code C:\Program Files\x.jar} → {@code /C:/Program%20Files/x.jar}</li>
 *   <li>中文：{@code E:\修仙\x.jar} → {@code /E:/%E4%BF%AE%E4%BB%99/x.jar}</li>
 * </ul>
 *
 * <p>用这种字符串打开 jar 会失败，而失败又被扫描器里宽容的 catch 吞掉 ——
 * 表现为"所有指令/物品/秘境/探索事件凭空消失"，且没有任何报错。
 * 开发机路径是纯 ASCII，所以这类问题在测试里也发现不了。</p>
 */
class JarPathResolverTest {

    @Test
    @DisplayName("路径中的空格（%20）必须被还原")
    void decodesSpace() throws Exception {
        Path dir = Files.createTempDirectory("jar path space ");
        try {
            Path jar = Files.createFile(dir.resolve("app.jar"));
            URL url = new URL("jar:" + jar.toUri().toURL() + "!/com/mtxgdn/onebot");
            assertTrue(url.getPath().contains("%20"), "前置条件：URL.getPath 确实是未解码的");

            Path resolved = JarPathResolver.resolve(url);
            assertEquals(jar.toAbsolutePath(), resolved.toAbsolutePath());
            assertTrue(Files.exists(resolved), "还原后的路径必须真实存在");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("路径中的中文必须被还原（UTF-8 多字节）")
    void decodesChinese() throws Exception {
        Path dir = Files.createTempDirectory("修仙服务端 ");
        try {
            Path jar = Files.createFile(dir.resolve("修仙.jar"));
            URL url = new URL("jar:" + jar.toUri().toURL() + "!/com/mtxgdn");
    

            Path resolved = JarPathResolver.resolve(url);
            assertEquals(jar.toAbsolutePath(), resolved.toAbsolutePath(),
                    "含中文的 jar 路径必须能还原，否则扫描器打不开 jar、全部内容消失");
            assertTrue(Files.exists(resolved));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("!/... 之后的部分要被截掉")
    void stripsEntryPart() throws Exception {
        Path dir = Files.createTempDirectory("jarstrip");
        try {
            Path jar = Files.createFile(dir.resolve("a.jar"));
            URL url = new URL("jar:" + jar.toUri().toURL() + "!/com/mtxgdn/x/Y.class");
            assertEquals(jar.toAbsolutePath(), JarPathResolver.resolve(url).toAbsolutePath());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    @DisplayName("普通 file: URL 直接可用")
    void handlesPlainFileUrl() throws Exception {
        Path file = Files.createTempFile("plain", ".jar");
        try {
            assertEquals(file.toAbsolutePath(),
                    JarPathResolver.resolve(file.toUri().toURL()).toAbsolutePath());
            assertEquals(file.toFile().getAbsolutePath(),
                    JarPathResolver.resolveFile(file.toUri().toURL()).getAbsolutePath());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("加号不能被当成空格（那是表单编码的规则，不是路径的）")
    void doesNotTurnPlusIntoSpace() {
        assertEquals("/tmp/my+mod/x.jar", JarPathResolver.decodePath("/tmp/my+mod/x.jar"));
        assertEquals("/tmp/my mod/x.jar", JarPathResolver.decodePath("/tmp/my%20mod/x.jar"));
    }

    @Test
    @DisplayName("非法百分号序列原样保留，不抛异常")
    void keepsMalformedPercentEscapes() {
        assertEquals("/tmp/100%/x.jar", JarPathResolver.decodePath("/tmp/100%/x.jar"));
        assertEquals("/tmp/a%zz/x.jar", JarPathResolver.decodePath("/tmp/a%zz/x.jar"));
    }

    @Test
    @DisplayName("null 抛 IllegalArgumentException")
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> JarPathResolver.resolve(null));
    }

    @Test
    @DisplayName("真实 jar（本工程 target/classes 目录形态）也能解析")
    void resolvesRealClasspathEntry() throws Exception {
        URL url = JarPathResolver.class.getResource("JarPathResolver.class");
        assertTrue(url != null, "测试类自身必须能被定位");
        // 目录形态下 protocol 是 file，resolve 应能拿到存在的位置
        File resolved = JarPathResolver.resolveFile(url);
        assertTrue(resolved.exists() || resolved.getParentFile().exists(),
                "解析出的路径应真实存在: " + resolved);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            });
        }
    }
}
