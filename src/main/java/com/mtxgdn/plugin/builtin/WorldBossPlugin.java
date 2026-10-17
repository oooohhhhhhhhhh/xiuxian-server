package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 世界 Boss 玩法模块（B6）。
 * <p>
 * 配置键: plugins.world_boss（默认 true）
 * 功能: 全服共享血量的世界 Boss、按伤害分红、伤害贡献榜
 */
public class WorldBossPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "world_boss";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "世界Boss：全服共享血量的 Boss，按伤害比例分红，另有超发熔断";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("世界Boss系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("世界Boss系统已停用");
    }
}
