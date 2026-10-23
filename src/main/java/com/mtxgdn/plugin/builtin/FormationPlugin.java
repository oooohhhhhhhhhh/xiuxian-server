package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 阵法玩法模块。
 * <p>
 * 配置键: plugins.formation（默认 true）
 * 功能: 阵法布置、阵法加成
 */
public class FormationPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "formation";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "阵法系统：阵法加成";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("阵法系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("阵法系统已停用");
    }
}