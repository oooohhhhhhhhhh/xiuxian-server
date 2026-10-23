package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 炼丹锻造玩法模块。
 * <p>
 * 配置键: plugins.crafting（默认 true）
 * 功能: 配方查看、合成炼制
 */
public class CraftingPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "crafting";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "炼丹锻造系统：配方、炼制";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("炼丹锻造系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("炼丹锻造系统已停用");
    }
}