package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 玩家搜索玩法模块。
 * <p>
 * 配置键: plugins.search（默认 true）
 * 功能: 按名称搜索玩家
 */
public class SearchPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "搜索系统：按名称搜索玩家";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("搜索系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("搜索系统已停用");
    }
}