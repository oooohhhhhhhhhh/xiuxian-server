package com.mtxgdn.plugin;

import com.mtxgdn.util.YamlFileEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证插件开关迁移到独立配置文件 {@code config/plugins.yml}：
 * <ul>
 *   <li>新文件的值优先；</li>
 *   <li>旧 {@code application.yml} 的 built_in_plugins.* 与旧 experimental.yml 作为只读兜底；</li>
 *   <li>首次启动把旧键自动迁移到新文件（不丢用户原先关掉的模块）；</li>
 *   <li>运行时启停写回新文件且保留注释排版。</li>
 * </ul>
 * 测试通过 {@link PluginSwitches#useConfigPathsForTest} 把文件基准指向 target 下的临时目录，
 * 不触碰真实 config/ 目录。
 */
class PluginSwitchesTest {

    private static final String MODULE = "switches_test_module";
    private static final String EXPERIMENTAL_KEY = "switches_test_exp";

    private Path savedExternalPath;
    private Path savedLegacyPath;
    private Path savedPluginConfigDir;
    private Path newConfigPath;
    private Path legacyExperimentalPath;

    @BeforeEach
    void swapConfigPaths() throws Exception {
        savedExternalPath = PluginSwitches.getConfigPath();
        savedLegacyPath = readLegacyPath();
        savedPluginConfigDir = PluginConfigFiles.getConfigDir();

        Path dir = Files.createTempDirectory("plugin-switches-");
        newConfigPath = dir.resolve("plugins.yml");
        legacyExperimentalPath = dir.resolve("experimental.yml");
        PluginSwitches.useConfigPathsForTest(newConfigPath, legacyExperimentalPath);
        // 插件独立配置目录也要隔离成空目录：本类的用例测的是"旧 plugins.yml 兜底"这条路径，
        // 若真实的 config/plugins/ 可见，set() 会优先写插件文件，测的就不是同一件事了。
        PluginConfigFiles.useConfigDirForTest(Files.createTempDirectory("plugin-configs-empty-"));
    }

    @AfterEach
    void restoreConfigPaths() {
        PluginSwitches.useConfigPathsForTest(savedExternalPath, savedLegacyPath);
        PluginSwitches.useLegacyAppValuesForTest(null);
        PluginConfigFiles.useConfigDirForTest(savedPluginConfigDir);
    }

    // ==================== 读取优先级 ====================

    @Test
    @DisplayName("新配置文件的值优先于旧键兜底")
    void configFileValueWinsOverLegacy() throws Exception {
        setLegacyAppConfigKey(MODULE, false);

        // 旧键 = false，新文件 = true → 以新文件为准
        writeNewConfig("plugins:\n  " + MODULE + ": true\n");
        assertEquals(PluginSwitches.Source.CONFIG_FILE, PluginSwitches.resolve(MODULE, true).getSource());
        assertTrue(PluginSwitches.isEnabled(MODULE, true), "plugins.yml 的值必须优先于旧键");

        // 新文件 = false 时同样以新文件为准
        writeNewConfig("plugins:\n  " + MODULE + ": false\n");
        assertFalse(PluginSwitches.isEnabled(MODULE, true));
    }

    @Test
    @DisplayName("新文件缺键时回退旧键，旧键也没有则用插件默认值")
    void fallsBackToLegacyKeysThenDefaults() throws Exception {
        // 旧 application.yml 把模块关掉了：升级后不能悄悄又打开
        setLegacyAppConfigKey(MODULE, false);
        writeNewConfig("plugins:\n  other_module: true\n");

        assertEquals(PluginSwitches.Source.LEGACY, PluginSwitches.resolve(MODULE, true).getSource());
        assertFalse(PluginSwitches.isEnabled(MODULE, true), "旧键 false 必须继续生效");

        // 旧 experimental.yml 的实验性开关同样兜底
        PluginSwitches.useConfigPathsForTest(newConfigPath, writeLegacyExperimental(
                "experimental:\n  " + EXPERIMENTAL_KEY + ": false\n"));
        assertEquals(PluginSwitches.Source.LEGACY,
                PluginSwitches.resolve(EXPERIMENTAL_KEY, true).getSource());
        assertFalse(PluginSwitches.isEnabled(EXPERIMENTAL_KEY, true));

        // 两处都没有 → 插件自身默认值
        assertEquals(PluginSwitches.Source.DEFAULT, PluginSwitches.resolve("never_configured", false).getSource());
        assertFalse(PluginSwitches.isEnabled("never_configured", false));
        assertEquals(PluginSwitches.Source.DEFAULT, PluginSwitches.resolve("never_configured", true).getSource());
        assertTrue(PluginSwitches.isEnabled("never_configured", true));
    }

    // ==================== 首次启动迁移 ====================

    @Test
    @DisplayName("迁移把旧 built_in_plugins / experimental 开关写到 plugins 节点下")
    void migrateLegacyKeysWritesIntoPluginsSection() throws Exception {
        setLegacyAppConfigKey(MODULE, false);
        writeLegacyExperimental("experimental:\n  " + EXPERIMENTAL_KEY + ": false\n");
        // 目标文件存在但没有目标键（模拟升级后的 plugins.yml）
        writeNewConfig("plugins:\n  farm: true   # 保留注释\n");

        int migrated = PluginSwitches.migrateLegacyKeys(List.of(MODULE));

        assertEquals(2, migrated, "应迁移 built_in_plugins 与 experimental 各一项");
        Map<String, Object> root = new Yaml().load(Files.readString(newConfigPath, StandardCharsets.UTF_8));
        assertNotNull(root, "迁移后的 plugins.yml 必须能被 YAML 解析");
        assertTrue(root.get("plugins") instanceof Map, "必须写在 plugins 节点下");
        Map<?, ?> plugins = (Map<?, ?>) root.get("plugins");
        assertEquals(Boolean.FALSE, plugins.get(MODULE), "旧 built_in_plugins=false 必须原样迁移");
        assertEquals(Boolean.FALSE, plugins.get(EXPERIMENTAL_KEY), "旧 experimental=false 必须原样迁移");
        assertTrue(Files.readString(newConfigPath, StandardCharsets.UTF_8).contains("# 保留注释"),
                "迁移不能破坏原有注释");

        // 迁移后即从新文件取值，来源不再是 LEGACY
        assertEquals(PluginSwitches.Source.CONFIG_FILE, PluginSwitches.resolve(MODULE, true).getSource());
        assertFalse(PluginSwitches.isEnabled(MODULE, true));
    }

    @Test
    @DisplayName("旧配置里没有的开关不会被迁移写出")
    void migrateSkipsKeysAbsentFromLegacyConfig() throws Exception {
        // 确保两处旧配置都不存在（前置测试可能设置过旧键兜底）
        PluginSwitches.useLegacyAppValuesForTest(Map.of());
        writeNewConfig("plugins:\n  farm: true\n");

        assertEquals(0, PluginSwitches.migrateLegacyKeys(List.of(MODULE)), "无旧键时不应写出任何条目");
        Object parsed = new Yaml().load(Files.readString(newConfigPath, StandardCharsets.UTF_8));
        Map<?, ?> plugins = (Map<?, ?>) ((Map<?, ?>) parsed).get("plugins");
        assertEquals(1, plugins.size(), "不应凭空多出配置项");
        assertFalse(plugins.containsKey("undefined_module"), "未配置的键不得出现");
    }

    // ==================== 运行时写回 ====================

    @Test
    @DisplayName("运行时启停写回 plugins.yml，保留注释与排版")
    void setWritesBackPreservingCommentsAndLayout() throws Exception {
        writeNewConfig("# 头部注释\nplugins:\n  farm: true            # 农场\n  sect: true\n");

        assertTrue(PluginSwitches.set(MODULE, false), "新增键应产生写盘变更");
        assertTrue(PluginSwitches.set("farm", false), "已有键的值变更应被写入");

        String content = Files.readString(newConfigPath, StandardCharsets.UTF_8);
        Object parsed = new Yaml().load(content);
        Map<?, ?> plugins = (Map<?, ?>) ((Map<?, ?>) parsed).get("plugins");
        assertEquals(Boolean.FALSE, plugins.get(MODULE));
        assertEquals(Boolean.FALSE, plugins.get("farm"));
        assertEquals(Boolean.TRUE, plugins.get("sect"), "未涉及的键必须保持原值");
        assertTrue(content.contains("# 头部注释"), "文件头注释必须保留");
        assertTrue(content.contains("# 农场"), "行内注释必须保留");

        // 写回后立即生效，且重复写入同值视为无变化
        assertFalse(PluginSwitches.isEnabled("farm", true));
        assertFalse(PluginSwitches.set("farm", false), "同值写入不应再改文件");
    }

    @Test
    @DisplayName("插件有独立配置文件时，启停写回该文件而不是 plugins.yml")
    void setWritesToPluginFileWhenItExists() throws Exception {
        // @BeforeEach 已把插件配置目录隔离成空目录，这里只放一个 farm.yml
        Path pluginDir = Files.createTempDirectory("plugin-configs-set-");
        Path farmFile = pluginDir.resolve("farm.yml");
        Files.writeString(farmFile, "# 农场\nenabled: true\n", StandardCharsets.UTF_8);
        PluginConfigFiles.useConfigDirForTest(pluginDir);

        writeNewConfig("# 头部注释\nplugins:\n  farm: true\n");

        assertTrue(PluginSwitches.set("farm", false), "写插件文件应产生变更");

        // 插件文件被改了，且注释保留
        String pluginContent = Files.readString(farmFile, StandardCharsets.UTF_8);
        assertEquals(Boolean.FALSE,
                ((Map<?, ?>) new Yaml().load(pluginContent)).get("enabled"),
                "开关应写回插件自己的文件");
        assertTrue(pluginContent.contains("# 农场"), "插件文件的注释必须保留");

        // 旧 plugins.yml 不该被动 —— 它的值仍写着 true
        String legacy = Files.readString(newConfigPath, StandardCharsets.UTF_8);
        assertEquals(Boolean.TRUE,
                ((Map<?, ?>) ((Map<?, ?>) new Yaml().load(legacy)).get("plugins")).get("farm"),
                "有插件文件时不应再写旧 plugins.yml");

        // 生效值是插件文件的 false
        assertFalse(PluginSwitches.isEnabled("farm", true));
        assertEquals(PluginSwitches.Source.PLUGIN_FILE,
                PluginSwitches.resolve("farm", true).getSource());
    }

    // ==================== 工具方法 ====================

    private void writeNewConfig(String yaml) throws Exception {
        Files.createDirectories(newConfigPath.getParent());
        Files.writeString(newConfigPath, yaml, StandardCharsets.UTF_8);
        PluginSwitches.useConfigPathsForTest(newConfigPath, legacyExperimentalPath);
    }

    /** 写入旧 experimental.yml 并让 PluginSwitches 重新读取，返回其路径。 */
    private Path writeLegacyExperimental(String yaml) throws Exception {
        Files.createDirectories(legacyExperimentalPath.getParent());
        Files.writeString(legacyExperimentalPath, yaml, StandardCharsets.UTF_8);
        PluginSwitches.useConfigPathsForTest(newConfigPath, legacyExperimentalPath);
        return legacyExperimentalPath;
    }

    /**
     * 模拟旧安装遗留的 {@code application.yml} 键：{@code built_in_plugins.<key>}。
     * <p>{@link AppConfig} 的映射是启动期一次性加载的不可变快照，测试用
     * {@link PluginSwitches#useLegacyAppValuesForTest} 替换该旧键来源，
     * 既覆盖“旧键只读兜底”路径，又不改动仓库里真实的 config/application.yml。
     */
    private void setLegacyAppConfigKey(String key, boolean value) {
        PluginSwitches.useLegacyAppValuesForTest(
                Map.of(PluginSwitches.LEGACY_KEY_PREFIX + key, String.valueOf(value)));
    }

    private static Path readLegacyPath() throws Exception {
        Field field = PluginSwitches.class.getDeclaredField("legacyExperimentalPath");
        field.setAccessible(true);
        return (Path) field.get(null);
    }
}
