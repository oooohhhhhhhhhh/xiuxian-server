package com.mtxgdn.plugin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证内置模块停用后其指令被命令注册表门控：
 * CommandRegistry.get() 视同指令不存在，使 OneBot/QQ/MC 等所有指令入口统一失效。
 * <p>
 * 本测试用一次性模块名 gate_test_mod 做启停，会持久化开关，因此隔离配置路径，
 * 避免污染共享测试配置（详见 {@link PluginSwitchTestSupport}）。
 */
class BuiltInPluginGateTest {

    private static final String MODULE = "gate_test_mod";

    private final PluginSwitchTestSupport switches = PluginSwitchTestSupport.create();

    @BeforeEach
    void useIsolatedSwitchFile() {
        switches.isolate();
    }

    private static final class TestCommand extends Command {
        TestCommand(String module) {
            super(new String[]{"gatetest", "gt"}, "测试命令", "/gatetest", "测试");
            if (module != null) setModule(module);
        }

        @Override
        public void execute(CommandContext ctx) {
            ctx.reply("ok");
        }
    }

    @AfterEach
    void reEnableModuleAndRestoreSwitchFile() {
        // 恢复模块启用状态，避免污染其他测试
        BuiltInPluginRegistry.getInstance().enablePlugin(MODULE);
        switches.restore();
    }

    @Test
    void disabledModuleHidesCommandFromRegistry() {
        BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
        registry.register(new BuiltInPlugin() {
            @Override public String getName() { return MODULE; }
            @Override public String getVersion() { return "1.0.0"; }
        });
        registry.init();
        registry.enableAll(PluginContext.createSharedContext(
                new File("target/test-plugins"), getClass().getClassLoader()));

        Command cmd = new TestCommand(MODULE);

        // 启用时指令正常可取
        assertTrue(cmd.isModuleEnabled());
        assertSame(cmd, CommandRegistry.get("gatetest"));

        // 停用后：get() 返回 null（视同指令不存在），getRaw() 仍可取回并标记为停用
        assertTrue(registry.disablePlugin(MODULE));
        assertFalse(cmd.isModuleEnabled());
        assertNull(CommandRegistry.get("gatetest"));
        assertSame(cmd, CommandRegistry.getRaw("gatetest"));

        // 重新启用后恢复
        assertTrue(registry.enablePlugin(MODULE));
        assertTrue(cmd.isModuleEnabled());
        assertSame(cmd, CommandRegistry.get("gatetest"));
    }

    @Test
    void commandWithoutModuleIsNeverGated() {
        Command cmd = new TestCommand(null);
        assertTrue(cmd.isModuleEnabled());
        assertSame(cmd, CommandRegistry.get("gatetest"));
    }
}
