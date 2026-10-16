package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 好友玩法模块。
 * <p>
 * 配置键: built_in_plugins.friend（默认 true）
 * 功能: 添加好友、好友列表
 */
public class FriendPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "friend";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "好友系统：添加好友、好友列表";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("好友系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("好友系统已停用");
    }
}
