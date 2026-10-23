package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * Buff 玩法模块。
 * <p>
 * 配置键: plugins.buff（默认 true）
 * 功能: 增益效果查看与管理
 */
public class BuffPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "buff";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Buff系统：增益效果";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("Buff系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("Buff系统已停用");
    }
}