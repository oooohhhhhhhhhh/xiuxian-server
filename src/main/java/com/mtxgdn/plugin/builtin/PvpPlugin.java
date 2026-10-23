package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 战斗玩法模块。
 * <p>
 * 配置键: plugins.pvp（默认 true）
 * 功能: 挑战其他修士、决斗系统
 */
public class PvpPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "pvp";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "战斗系统：挑战、决斗";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("战斗系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("战斗系统已停用");
    }
}