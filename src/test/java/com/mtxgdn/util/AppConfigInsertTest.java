package com.mtxgdn.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 AppConfig.update 在目标父节缺失时能补建祖先节。
 * <p>
 * 内置插件的 WebUI 启停通过 AppConfig.update 持久化 built_in_plugins.* 键，
 * 而旧版安装的 config/application.yml 里没有 built_in_plugins 父节 ——
 * 若 update 只是把子键裸插到文件末尾，会产生无父节的缩进行、破坏 YAML 解析，
 * 导致重启后配置损坏。本测试守护这一补建行为。
 */
class AppConfigInsertTest {

    private Path configFile;
    private boolean existed;
    private String original;

    @BeforeEach
    void backup() throws Exception {
        configFile = AppConfig.getJarDir().resolve("config").resolve("application.yml");
        existed = Files.exists(configFile);
        if (existed) {
            original = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void restore() throws Exception {
        if (existed) {
            Files.writeString(configFile, original, StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    void updateCreatesMissingParentSectionAndStaysParseable() throws Exception {
        // 父节 built_in_plugins 不存在（模拟旧安装）时，必须能补建且保持 YAML 可解析
        AppConfig.update(Collections.singletonMap("built_in_plugins.testmod", false));

        String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
        Map<String, Object> root = new Yaml().load(content);
        assertNotNull(root, "补建后的 application.yml 必须能被 YAML 解析");
        Object section = root.get("built_in_plugins");
        assertNotNull(section, "必须生成 built_in_plugins 父节");
        assertTrue(section instanceof Map, "built_in_plugins 必须是映射节");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) section).get("testmod"));
    }

    @Test
    void multipleUpdatesAccumulateInsideSameSection() throws Exception {
        AppConfig.update(Collections.singletonMap("built_in_plugins.alpha", true));
        AppConfig.update(Collections.singletonMap("built_in_plugins.beta", false));

        String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
        Map<String, Object> root = new Yaml().load(content);
        Object section = root.get("built_in_plugins");
        assertNotNull(section);
        Map<?, ?> bip = (Map<?, ?>) section;
        assertEquals(Boolean.TRUE, bip.get("alpha"));
        assertEquals(Boolean.FALSE, bip.get("beta"));
    }

    @Test
    void existingTopLevelSectionsRemainIntact() throws Exception {
        AppConfig.update(Collections.singletonMap("built_in_plugins.zzz", true));

        String content = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
        Map<String, Object> root = new Yaml().load(content);
        Object app = root.get("app");
        assertNotNull(app, "原有 app 顶层段不能被破坏");
        assertEquals("修仙世界", ((Map<?, ?>) app).get("name"));
    }
}
