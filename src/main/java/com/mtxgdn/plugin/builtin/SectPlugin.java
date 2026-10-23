package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 宗门玩法模块。
 * <p>
 * 配置键: plugins.sect（默认 true）
 * 功能: 创建宗门、宗门管理、宗门仓库、宗门战争
 */
public class SectPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "sect";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "宗门系统：建宗立派、宗门仓库、宗门战争";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("宗门系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("宗门系统已停用");
    }
}