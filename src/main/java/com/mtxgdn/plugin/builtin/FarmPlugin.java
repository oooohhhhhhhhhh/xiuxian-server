package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 农场玩法模块。
 * <p>
 * 配置键: built_in_plugins.farm（默认 true）
 * 功能: 种植、浇水、施肥、收获、扩建
 */
public class FarmPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "farm";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "农场系统：种植灵药、浇灌、收获";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("农场系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("农场系统已停用");
    }
}