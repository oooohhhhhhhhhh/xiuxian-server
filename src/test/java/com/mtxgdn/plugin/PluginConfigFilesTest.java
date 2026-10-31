package com.mtxgdn.plugin;

import com.mtxgdn.util.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件独立配置文件（{@code config/plugins/<name>.yml}）的行为与完整性。
 *
 * <p>这组测试守的是"拆分之后不会悄悄少东西"：</p>
 * <ul>
 *   <li>每个内置插件都有且只有一份配置文件；</li>
 *   <li>每份文件都能被 YAML 解析，且都有 {@code enabled}；</li>
 *   <li>参数归属表登记的前缀确实能从插件文件里读到；</li>
 *   <li>插件文件的值优先于 application.yml，但 {@code -D} 仍然最高。</li>
 * </ul>
 */
class PluginConfigFilesTest {

    private Path savedDir;
    private Path tempDir;

    @BeforeEach
    void swapDir() throws Exception {
        savedDir = PluginConfigFiles.getConfigDir();
        tempDir = Files.createTempDirectory("plugin-configs-");
    }

    @AfterEach
    void restoreDir() {
        PluginConfigFiles.useConfigDirForTest(savedDir);
    }

    // ==================== 清单完整性 ====================

    @Test
    @DisplayName("每个内置插件都有独立配置文件，模板清单与 PluginCatalog 完全一致")
    void everyBuiltInPluginHasAConfigFile() {
        // PluginCatalog 是"有哪些插件"的唯一事实来源
        TreeSet<String> catalogNames = new TreeSet<>();
        for (BuiltInPlugin plugin : PluginCatalog.createAll()) {
            catalogNames.add(plugin.getName().toLowerCase(java.util.Locale.ROOT));
        }

        // 模板清单是"释放哪些配置文件"的显式列表（jar 内不能列目录）
        TreeSet<String> templateNames = new TreeSet<>(PluginSwitches.TEMPLATE_PLUGIN_FILES);

        assertEquals(catalogNames, templateNames,
                "模板清单与插件清单必须一一对应。差集: 仅插件有="
                        + minus(catalogNames, templateNames)
                        + " 仅模板有=" + minus(templateNames, catalogNames));

        // 每个模板都必须真的存在于 classpath
        List<String> missing = new ArrayList<>();
        for (String name : templateNames) {
            String resource = "plugin-configs/" + name + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    missing.add(resource);
                }
            } catch (Exception e) {
                missing.add(resource + "(" + e.getMessage() + ")");
            }
        }
        assertTrue(missing.isEmpty(), "classpath 里缺少这些插件配置模板: " + missing);
    }

    @Test
    @DisplayName("每份插件配置都能解析，且都带 enabled 开关")
    void everyConfigFileParsesAndDeclaresEnabled() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String name : PluginSwitches.TEMPLATE_PLUGIN_FILES) {
            String resource = "plugin-configs/" + name + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(in, "模板不存在: " + resource);
                Object raw = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(raw instanceof Map<?, ?> map)) {
                    problems.add(resource + " 顶层不是 Map");
                    continue;
                }
                if (!map.containsKey(PluginConfigFiles.ENABLED_KEY)) {
                    problems.add(resource + " 缺少 enabled 开关");
                } else if (!(map.get(PluginConfigFiles.ENABLED_KEY) instanceof Boolean)) {
                    problems.add(resource + " 的 enabled 不是布尔值: "
                            + map.get(PluginConfigFiles.ENABLED_KEY));
                }
            }
        }
        assertTrue(problems.isEmpty(), "插件配置有问题: " + problems);
    }

    @Test
    @DisplayName("模板里的 enabled 与插件自身默认值一致")
    void templateEnabledMatchesPluginDefault() throws Exception {
        List<String> mismatches = new ArrayList<>();
        for (BuiltInPlugin plugin : PluginCatalog.createAll()) {
            String name = plugin.getName().toLowerCase(java.util.Locale.ROOT);
            String resource = "plugin-configs/" + name + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    mismatches.add(name + ": 模板缺失");
                    continue;
                }
                Object raw = new Yaml().load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                Object enabled = ((Map<?, ?>) raw).get(PluginConfigFiles.ENABLED_KEY);
                boolean actual = Boolean.TRUE.equals(enabled);
                if (actual != plugin.getDefaultEnabled()) {
                    mismatches.add(name + ": 模板=" + actual
                            + " 但插件默认=" + plugin.getDefaultEnabled());
                }
            }
        }
        assertTrue(mismatches.isEmpty(),
                "模板的 enabled 必须与插件默认值一致，否则新安装的行为与代码声明不符: " + mismatches);
    }

    // ==================== 参数归属 ====================

    @Test
    @DisplayName("归属表登记的每个前缀都能从对应插件文件里读到同名参数")
    void declaredPrefixesResolveToPluginFiles() throws Exception {
        // 把真实模板释放到临时目录，再逐前缀核对
        Files.createDirectories(tempDir);
        for (String name : PluginSwitches.TEMPLATE_PLUGIN_FILES) {
            String resource = "plugin-configs/" + name + ".yml";
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                Files.write(tempDir.resolve(name + ".yml"), in.readAllBytes());
            }
        }
        PluginConfigFiles.useConfigDirForTest(tempDir);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : PluginConfigFiles.getPrefixOwnerMap().entrySet()) {
            String prefix = entry.getKey();   // 如 "world_boss."
            String owner = entry.getValue();  // 如 "world_boss"
            Map<String, String> values = PluginConfigFiles.getPluginValues(owner);
            if (values.isEmpty()) {
                problems.add(owner + ": 没有配置文件");
                continue;
            }
            // 该前缀下至少要有一项出现在插件文件里，否则归属登记是空话
            String shortPrefix = prefix;
            boolean any = values.keySet().stream().anyMatch(k -> !k.equals(PluginConfigFiles.ENABLED_KEY));
            if (!any) {
                problems.add(owner + ": 文件里除了 enabled 没有任何参数，"
                        + "但归属表把 " + shortPrefix + "* 归给了它");
            }
        }
        assertTrue(problems.isEmpty(), "归属登记与文件内容不符: " + problems);
    }

    @Test
    @DisplayName("通用设置不归属任何插件（它们留在 application.yml）")
    void generalSettingsHaveNoOwner() {
        String[] generalKeys = {
                "server.port", "database.url", "jwt.secret", "logging.level",
                "admin.username", "smtp.host", "network.read_timeout_ms",
                "performance.command_threads", "app.name", "i18n.default-language"
        };
        for (String key : generalKeys) {
            assertNull(PluginConfigFiles.ownerOf(key),
                    key + " 是通用设置，不应归属任何插件 —— 拆进插件文件会让运维找不到");
        }
    }

    @Test
    @DisplayName("插件参数归属到正确的插件")
    void pluginParametersMapToTheirOwner() {
        assertEquals("world_boss", PluginConfigFiles.ownerOf("world_boss.hp_multiplier"));
        assertEquals("sect", PluginConfigFiles.ownerOf("sect.contest_days"));
        assertEquals("trade", PluginConfigFiles.ownerOf("market.listing_duration_hours"));
        assertEquals("buff", PluginConfigFiles.ownerOf("buff.expire_sweep_seconds"));
        assertEquals("inventory", PluginConfigFiles.ownerOf("inventory.base_capacity"));
        assertEquals("inventory", PluginConfigFiles.ownerOf("equipment.max_durability"),
                "装备耐久归背包模块（物品与装备是同一套持有关系）");
        assertEquals("cultivation", PluginConfigFiles.ownerOf("breakthrough.pity_bonus"));
        assertEquals("map", PluginConfigFiles.ownerOf("wilderness.dir"));
        assertEquals("onebot", PluginConfigFiles.ownerOf("onebot.send_mode"));
    }

    // ==================== 取值优先级 ====================

    @Test
    @DisplayName("插件文件的值优先于 application.yml，但 -D 系统属性仍然最高")
    void pluginFileOverridesApplicationYmlButNotSystemProperty() throws Exception {
        String key = "world_boss.hp_multiplier";

        // application.yml 模板里的值是 12
        String fromYml = AppConfig.get(key);
        assertNotNull(fromYml, "前置条件：application.yml 里应有 " + key);
        assertEquals("12", fromYml);

        // 插件文件写一个不同的值 → 应当优先
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("world_boss.yml"),
                "enabled: true\nhp_multiplier: 99\n", StandardCharsets.UTF_8);
        PluginConfigFiles.useConfigDirForTest(tempDir);
        assertEquals("99", AppConfig.get(key),
                "插件文件是参数的新归属地，必须优先于 application.yml");

        // -D 系统属性必须仍然压过一切（运维应急通道）
        System.setProperty(key, "777");
        try {
            assertEquals("777", AppConfig.get(key),
                    "-D 系统属性必须最高，否则运维的应急开关失效");
        } finally {
            System.clearProperty(key);
        }

        // 清掉插件文件后回落到 application.yml
        PluginConfigFiles.useConfigDirForTest(Files.createTempDirectory("empty-"));
        assertEquals("12", AppConfig.get(key), "插件文件没写该项时应回落到 application.yml");
    }

    @Test
    @DisplayName("插件文件里没写的参数回落到 application.yml（升级不丢设置）")
    void missingPluginKeyFallsBackToApplicationYml() throws Exception {
        Files.createDirectories(tempDir);
        // 只写 enabled，不写任何参数
        Files.writeString(tempDir.resolve("sect.yml"), "enabled: true\n", StandardCharsets.UTF_8);
        PluginConfigFiles.useConfigDirForTest(tempDir);

        // application.yml 里的 sect.leader_absent_days = 14
        assertEquals("14", AppConfig.get("sect.leader_absent_days"),
                "插件文件缺该项时必须回落到 application.yml，否则升级后参数全变默认值");
    }

    @Test
    @DisplayName("插件文件的 enabled 优先于旧 plugins.yml")
    void pluginFileEnabledWinsOverLegacyPluginsYml() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("farm.yml"), "enabled: false\n", StandardCharsets.UTF_8);
        PluginConfigFiles.useConfigDirForTest(tempDir);

        PluginSwitches.Resolved resolved = PluginSwitches.resolve("farm", true);
        assertEquals(PluginSwitches.Source.PLUGIN_FILE, resolved.getSource(),
                "有独立文件时，开关来源必须是 PLUGIN_FILE");
        assertFalse(resolved.isEnabled(), "插件文件里 enabled: false 必须生效");
    }

    // ==================== 工具 ====================

    private static List<String> minus(TreeSet<String> a, TreeSet<String> b) {
        List<String> result = new ArrayList<>(a);
        result.removeAll(b);
        return result;
    }
}
