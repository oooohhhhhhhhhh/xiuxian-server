package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 经济玩法模块。
 * <p>
 * 配置键: plugins.economy（默认 true）
 * 功能: 拍卖行、灵石存取、商店、签到、灵石加速/提纯/回收/兑换
 */
public class EconomyPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "economy";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "经济系统：拍卖、灵庄、商店、签到、灵石流转";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("经济系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("经济系统已停用");
    }
}