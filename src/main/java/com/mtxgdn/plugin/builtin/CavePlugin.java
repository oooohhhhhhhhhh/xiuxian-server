package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 洞府玩法模块。
 * <p>
 * 配置键: built_in_plugins.cave（默认 true）
 * 功能: 灵力收集、洞府升级
 */
public class CavePlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "cave";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "洞府系统：灵力收集、洞府修炼";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("洞府系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("洞府系统已停用");
    }
}