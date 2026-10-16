package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 功法玩法模块。
 * <p>
 * 配置键: built_in_plugins.technique（默认 true）
 * 功能: 功法学习、装备、升级
 */
public class TechniquePlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "technique";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "功法系统：功法学习、装备、升级";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("功法系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("功法系统已停用");
    }
}