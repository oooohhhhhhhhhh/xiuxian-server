package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 能量玩法模块。
 * <p>
 * 配置键: plugins.energy（默认 true）
 * 功能: 物品转化为能量、能量兑换物品
 */
public class EnergyPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "energy";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "能量系统：物品转化能量";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("能量系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("能量系统已停用");
    }
}