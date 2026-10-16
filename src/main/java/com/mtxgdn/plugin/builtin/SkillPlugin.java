package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 技能玩法模块。
 * <p>
 * 配置键: built_in_plugins.skill（默认 true）
 * 功能: 学习技能、技能列表、战斗策略
 */
public class SkillPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "skill";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "技能系统：学习技能、技能列表、战斗策略";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("技能系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("技能系统已停用");
    }
}