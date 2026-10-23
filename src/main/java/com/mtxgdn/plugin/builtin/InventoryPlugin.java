package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 背包/装备玩法模块。
 * <p>
 * 配置键: plugins.inventory（默认 true）
 * 功能: 背包管理、物品使用、装备穿戴与强化
 */
public class InventoryPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "inventory";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "背包/装备系统：物品、装备、强化";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("背包/装备系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("背包/装备系统已停用");
    }
}