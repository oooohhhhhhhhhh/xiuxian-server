package com.mtxgdn.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 插件开关测试的配置隔离工具。
 *
 * <p><b>为什么需要它</b>：{@link BuiltInPluginRegistry} 的运行时启停会把开关
 * <b>持久化</b>到 {@code plugins.yml}。测试若用一次性探针模块（如
 * {@code enable_order_probe}）去验证启停，却不隔离配置路径，探针的 {@code false}
 * 就会残留在共享的测试开关文件里。后果是<b>下一次运行</b>时该探针在
 * {@code enableAll} 阶段被跳过，断言随之失败 —— 表现为「单独跑通过、
 * 全量跑/连续跑失败」的假失败，且极难排查（本仓库的
 * {@code BuiltInPluginEnableOrderTest} 即因此长期红灯）。
 *
 * <p>用法：在 {@code @BeforeEach} 调用 {@link #isolate()}，
 * 在 {@code @AfterEach} 调用 {@link #restore()}。
 */
final class PluginSwitchTestSupport {

    private Path savedConfigPath;
    private Path savedLegacyPath;

    private PluginSwitchTestSupport() {
    }

    /** 新建隔离实例（每个测试类持有一个字段即可）。 */
    static PluginSwitchTestSupport create() {
        return new PluginSwitchTestSupport();
    }

    /**
     * 把开关文件指向一次性临时目录，并记录原路径以便恢复。
     * 临时文件只含空的 {@code plugins:} 节点，因此测试内的启停不会影响共享配置。
     */
    void isolate() {
        try {
            savedConfigPath = PluginSwitches.getConfigPath();
            savedLegacyPath = readStaticPath("legacyExperimentalPath");

            Path dir = Files.createTempDirectory("plugin-switch-isolated-");
            Path configPath = dir.resolve("plugins.yml");
            Files.writeString(configPath, "plugins:\n", StandardCharsets.UTF_8);
            PluginSwitches.useConfigPathsForTest(configPath, dir.resolve("experimental.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException("创建隔离的插件开关配置失败", e);
        }
    }

    /** 恢复原有开关文件路径。 */
    void restore() {
        PluginSwitches.useConfigPathsForTest(savedConfigPath, savedLegacyPath);
    }

    /** 读取 {@link PluginSwitches} 的私有静态路径字段（legacyExperimentalPath）。 */
    private static Path readStaticPath(String fieldName) {
        try {
            Field field = PluginSwitches.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Path) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 PluginSwitches." + fieldName + " 失败", e);
        }
    }
}
