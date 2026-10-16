package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 探索/游历玩法模块。
 * <p>
 * 配置键: built_in_plugins.exploration（默认 true）
 * 功能: 游历探索事件、随机遭遇、奖励结算
 */
public class ExplorationPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "exploration";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "游历探索系统：随机事件、冒险奖励";
    }

    @Override
    public void onEnable(PluginContext context) {
        // 探索服务已在 ServiceRegistry 中注册，此处仅作启停管理
        context.getLogger().info("探索系统已启用（游历冷却 " + com.mtxgdn.game.service.ExplorationService.class.getSimpleName() + "）");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("探索系统已停用");
    }
}