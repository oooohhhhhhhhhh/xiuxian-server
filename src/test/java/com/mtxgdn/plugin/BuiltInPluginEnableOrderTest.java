package com.mtxgdn.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 回归测试：运行时“启用”内置插件时，必须先把自身置为启用态再调用 onLoad/onEnable。
 * 否则适配器通道插件（AdapterChannelPlugin）的 onEnable → AdapterManager.startType
 * 在门控 isEnabledOrUnknown 处读到“未启用”而拒绝启动任何实例（只能关不能开）。
 * <p>
 * <b>测试隔离</b>：本测试使用一次性探针插件，而启停会把开关持久化到 plugins.yml。
 * 若不隔离，探针的 {@code enable_order_probe: false} 会残留在共享开关文件里，
 * 使<b>下一次运行</b>时探针在 {@code enableAll} 阶段被跳过 ——
 * 即“单独跑通过、连续跑/全量跑失败”的假失败（本测试此前即因此长期红灯）。
 * 详见 {@link PluginSwitchTestSupport}。
 */
class BuiltInPluginEnableOrderTest {

    private static final String PROBE = "enable_order_probe";

    private final PluginSwitchTestSupport switches = PluginSwitchTestSupport.create();

    @BeforeEach
    void useIsolatedSwitchFile() {
        switches.isolate();
    }

    @AfterEach
    void restoreSwitchFile() {
        switches.restore();
    }

    @Test
    void runtimeEnableMakesSelfGatedChecksPassDuringOnEnable() {
        BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
        final String name = PROBE;
        AtomicBoolean seenEnabledDuringOnLoad = new AtomicBoolean(false);
        AtomicBoolean seenEnabledDuringOnEnable = new AtomicBoolean(false);

        BuiltInPlugin probe = new BuiltInPlugin() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getVersion() {
                return "1.0.0";
            }

            @Override
            public void onLoad(PluginContext context) {
                seenEnabledDuringOnLoad.set(registry.isEnabledOrUnknown(name));
            }

            @Override
            public void onEnable(PluginContext context) {
                seenEnabledDuringOnEnable.set(registry.isEnabledOrUnknown(name));
            }
        };

        registry.register(probe);
        try {
            // 确保共享上下文已初始化；注册表会启用 probe 与既有测试模块（均为空壳，无副作用）
            PluginContext ctx = PluginContext.createSharedContext(
                    new File("data"), BuiltInPluginRegistry.class.getClassLoader());
            registry.enableAll(ctx);

            // 先停用（entry.active=false），随后走运行时 enableEntry 路径。
            // 若探针此前未被启用（例如它已被别的用例注册过），直接启用即可 ——
            // 本测试只关心 enableEntry 内部的“先置启用态再回调”顺序，不依赖前置状态。
            if (registry.isEnabledOrUnknown(name)) {
                Assertions.assertTrue(registry.disablePlugin(name), "前置：应能停用探针");
            }
            Assertions.assertFalse(registry.isEnabledOrUnknown(name), "前置：探针应处于停用态");

            seenEnabledDuringOnLoad.set(false);
            seenEnabledDuringOnEnable.set(false);
            Assertions.assertTrue(registry.enablePlugin(name), "运行时启用探针应成功");

            // 关键断言：onEnable 执行期间自身门控必须已放行
            Assertions.assertTrue(seenEnabledDuringOnEnable.get(),
                    "onEnable 期间查询自身 isEnabledOrUnknown 应返回 true（先置启用态再回调）");
            Assertions.assertTrue(registry.isEnabledOrUnknown(name));
        } finally {
            // 兜底：确保探针不会以启用态残留在单例里影响后续用例
            if (registry.isEnabledOrUnknown(name)) {
                registry.disablePlugin(name);
            }
        }
    }
}
