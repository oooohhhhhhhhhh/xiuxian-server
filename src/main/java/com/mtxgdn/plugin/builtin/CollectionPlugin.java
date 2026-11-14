package com.mtxgdn.plugin.builtin;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.PluginContext;

/**
 * 全图鉴玩法模块。
 * <p>
 * 配置键: plugins.collection（默认 true）；奖励配置见 config/collection.yml
 * 功能: 记录玩家收集到的物品/称号/功法/技能/秘境/游历事件/地图地点/配方，
 *       每解锁一条发一次奖励，集齐一个分类再发一次集齐奖励。
 */
public class CollectionPlugin extends BuiltInPlugin {

    @Override
    public String getName() {
        return "collection";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "全图鉴：收集物品/称号/功法等，逐条解锁并发奖励";
    }

    @Override
    public void onEnable(PluginContext context) {
        context.getLogger().info("全图鉴已启用");
    }

    @Override
    public void onDisable(PluginContext context) {
        context.getLogger().info("全图鉴已停用");
    }
}
