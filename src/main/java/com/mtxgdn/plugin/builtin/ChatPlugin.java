package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 聊天玩法模块。
 * <p>
 * 配置键: built_in_plugins.chat（默认 true）
 * 功能: 世界频道、私聊消息
 */
public class ChatPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "chat";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "聊天系统：世界频道、私聊";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("聊天系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("聊天系统已停用");
    }
}
