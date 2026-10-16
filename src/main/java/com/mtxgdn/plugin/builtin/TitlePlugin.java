package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 称号玩法模块。
 * <p>
 * 配置键: built_in_plugins.title（默认 true）
 * 功能: 查看称号、装备称号
 */
public class TitlePlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "title";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "称号系统：成就、称号装备";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("称号系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("称号系统已停用");
    }
}
