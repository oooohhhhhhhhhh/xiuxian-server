package com.mtxgdn.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证内置插件注册表的运行时启停 —— 即 WebUI「插件管理」页停用/启用内置模块
 * 所依赖的后端能力（此前缺失，导致内置插件永远无法停用）。
 * <p>
 * 本类使用一次性模块名（test_module / shutdown_mod），启停会把开关持久化到
 * plugins.yml，因此必须隔离配置路径，避免把探针开关写进共享测试配置。
 * 详见 {@link PluginSwitchTestSupport}。
 */
class BuiltInPluginRegistryTest {

    private final PluginSwitchTestSupport switches = PluginSwitchTestSupport.create();

    @BeforeEach
    void useIsolatedSwitchFile() {
        switches.isolate();
    }

    @AfterEach
    void restoreSwitchFile() {
        switches.restore();
    }

    private static final class CountingPlugin extends BuiltInPlugin {
        private final String name;
        int loadCalls = 0;
        int enableCalls = 0;
        int disableCalls = 0;

        CountingPlugin(String name) {
            this.name = name;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getVersion() { return "1.0.0"; }

        @Override
        public void onLoad(PluginContext context) { loadCalls++; }

        @Override
        public void onEnable(PluginContext context) { enableCalls++; }

        @Override
        public void onDisable(PluginContext context) { disableCalls++; }
    }

    private static PluginContext sharedCtx() {
        return PluginContext.createSharedContext(
                new File("target/test-plugins"), BuiltInPluginRegistryTest.class.getClassLoader());
    }

    @Test
    void runtimeDisableThenEnableTogglesModuleAndCallsLifecycle() {
        BuiltInPluginRegistry registry = new BuiltInPluginRegistry();
        CountingPlugin module = new CountingPlugin("test_module");
        registry.register(module);
        registry.init();
        registry.enableAll(sharedCtx());

        assertTrue(registry.isEnabled("test_module"));
        assertEquals(1, module.loadCalls);
        assertEquals(1, module.enableCalls);

        // 停用：调用 onDisable，状态与列表同步更新
        assertTrue(registry.disablePlugin("test_module"));
        assertFalse(registry.isEnabled("test_module"));
        assertEquals(1, module.disableCalls);
        boolean listedDisabled = registry.getStatuses().stream().anyMatch(s ->
                "test_module".equals(s.get("name"))
                        && Boolean.FALSE.equals(s.get("enabled"))
                        && Boolean.FALSE.equals(s.get("active")));
        assertTrue(listedDisabled, "停用后 getStatuses() 应反映 enabled=false / active=false");

        // 重复停用返回 false
        assertFalse(registry.disablePlugin("test_module"));

        // 重新启用：再次 onLoad + onEnable
        assertTrue(registry.enablePlugin("test_module"));
        assertTrue(registry.isEnabled("test_module"));
        assertEquals(2, module.loadCalls);
        assertEquals(2, module.enableCalls);
        boolean listedEnabled = registry.getStatuses().stream().anyMatch(s ->
                "test_module".equals(s.get("name")) && Boolean.TRUE.equals(s.get("enabled")));
        assertTrue(listedEnabled, "重新启用后 getStatuses() 应反映 enabled=true");

        // 重复启用返回 false
        assertFalse(registry.enablePlugin("test_module"));

        // 不存在的模块
        assertFalse(registry.disablePlugin("no_such_module"));
        assertFalse(registry.enablePlugin("no_such_module"));
    }

    @Test
    void shutdownDisableAllStillAllowsRuntimeReEnable() {
        BuiltInPluginRegistry registry = new BuiltInPluginRegistry();
        CountingPlugin module = new CountingPlugin("shutdown_mod");
        registry.register(module);
        registry.init();
        registry.enableAll(sharedCtx());

        registry.disableAll(sharedCtx());
        assertFalse(registry.isEnabled("shutdown_mod"));
        assertTrue(module.disableCalls >= 1);

        // 服务器运行中（disableAll 之后）仍可重新启用
        assertTrue(registry.enablePlugin("shutdown_mod"));
        assertTrue(registry.isEnabled("shutdown_mod"));
        assertEquals(2, module.enableCalls);
    }

    /**
     * 回归：运行时启停会把开关持久化到 plugins.yml（这是 WebUI 启停要能跨重启生效的前提）。
     * <p>
     * 该持久化行为正是“探针开关污染共享配置”这一假失败的根源，所以这里同时锁定两点：
     * <ul>
     *   <li>持久化确实发生（新增键被写出，行为没有被静默去掉）；</li>
     *   <li>写入的是<b>本测试隔离出来的</b>配置文件 —— 类级 {@code @BeforeEach}
     *       已把开关文件指向临时目录，因此共享测试配置不会被写脏。</li>
     * </ul>
     */
    @Test
    void runtimeTogglePersistsToIsolatedConfigFile() throws Exception {
        // 类级隔离已生效：当前生效的开关文件就是本次测试专属的临时文件
        Path isolatedConfig = PluginSwitches.getConfigPath();

        BuiltInPluginRegistry registry = new BuiltInPluginRegistry();
        registry.register(new CountingPlugin("probe_leak_check"));
        registry.init();
        registry.enableAll(sharedCtx());

        assertTrue(registry.disablePlugin("probe_leak_check"), "应能停用");

        String written = Files.readString(isolatedConfig, StandardCharsets.UTF_8);
        assertTrue(written.contains("probe_leak_check: false"),
                "运行时停用应把开关持久化到配置路径，实际内容: " + written);
    }
}
