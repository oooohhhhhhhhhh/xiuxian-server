package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 秘境玩法模块。
 * <p>
 * 配置键: plugins.secret_realm（默认 true）
 * 功能: 秘境列表、进入秘境、秘境探索奖励
 */
public class SecretRealmPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "secret_realm";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "秘境探索系统：多层次秘境、高境界秘境差异化";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("秘境系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("秘境系统已停用");
    }
}