package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 修炼玩法模块。
 * <p>
 * 配置键: built_in_plugins.cultivation（默认 true）
 * 功能: 打坐修炼、突破境界、转世重修、每日天象与晨修
 */
public class CultivationPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "cultivation";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "修炼系统：打坐、突破、转世、天象晨修";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("修炼系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("修炼系统已停用");
    }
}