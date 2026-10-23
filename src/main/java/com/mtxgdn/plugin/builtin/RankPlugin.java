package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 排行玩法模块。
 * <p>
 * 配置键: plugins.rank（默认 true）
 * 功能: 查看排行榜
 */
public class RankPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "rank";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "排行系统：查看排行榜";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("排行系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("排行系统已停用");
    }
}