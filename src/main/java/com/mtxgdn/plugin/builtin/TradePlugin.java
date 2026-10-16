package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 坊市交易玩法模块。
 * <p>
 * 配置键: built_in_plugins.trade（默认 true）
 * 功能: 物品上架、购买、取消挂单、市场列表
 */
public class TradePlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "trade";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "坊市交易系统：挂单上架、购买、市场流通";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("坊市交易系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("坊市交易系统已停用");
    }
}