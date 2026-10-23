package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 地图玩法模块。
 * <p>
 * 配置键: plugins.map（默认 true）
 * 功能: 查看地图、移动、旅行
 */
public class MapPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "map";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "地图系统：查看地图、移动旅行";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("地图系统已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("地图系统已停用");
    }
}
