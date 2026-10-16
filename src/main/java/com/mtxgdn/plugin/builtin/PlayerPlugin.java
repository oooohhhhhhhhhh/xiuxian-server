package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 角色基础玩法模块。
 * <p>
 * 配置键: built_in_plugins.player（默认 true）
 * 功能: 角色状态、疗伤、灵根查看
 */
public class PlayerPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "player";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "角色系统：状态、疗伤、灵根";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("角色系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("角色系统已停用");
    }
}