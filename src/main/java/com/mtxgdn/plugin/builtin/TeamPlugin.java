package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 团队玩法模块。
 * <p>
 * 配置键: plugins.team（默认 true）
 * 功能: 创建团队、组队副本
 */
public class TeamPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "team";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "团队系统：创建团队、组队副本";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("团队系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("团队系统已停用");
    }
}
