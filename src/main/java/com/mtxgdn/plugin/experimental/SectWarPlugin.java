package com.mtxgdn.plugin.experimental;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 实验性插件：宗门战。
 * <p>
 * 配置键: {@code plugins.experimental_sect_war}（默认 false，见 config/plugins.yml）
 * 功能: 宗门之间的攻防战（REST {@code POST /api/game/sect/war/{sectId}}、WebSocket {@code sect_war}）
 * <p>
 * 由旧 {@code config/experimental.yml} 的 {@code experimental.sect_war} 迁移而来；
 * 停止本插件即整体关闭宗门战，相关指令/接口会被门控拒绝。
 */
public class SectWarPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "experimental_sect_war";
    }

    @Override
    public String getVersion() {
        return "0.1.0";
    }

    @Override
    public String getDescription() {
        return "实验性：宗门战（宗门间攻防战）";
    }

    @Override
    public String getCategory() {
        return "experimental";
    }

    /** 实验性内容默认关闭。 */
    @Override
    public boolean getDefaultEnabled() {
        return false;
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("实验性功能已启用: 宗门战");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("实验性功能已停用: 宗门战");
    }
}
