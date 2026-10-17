package com.mtxgdn.plugin;

import com.mtxgdn.plugin.builtin.AdapterChannelPlugin;
import com.mtxgdn.plugin.builtin.BuffPlugin;
import com.mtxgdn.plugin.builtin.CavePlugin;
import com.mtxgdn.plugin.builtin.ChatPlugin;
import com.mtxgdn.plugin.builtin.CraftingPlugin;
import com.mtxgdn.plugin.builtin.CultivationPlugin;
import com.mtxgdn.plugin.builtin.EconomyPlugin;
import com.mtxgdn.plugin.builtin.EnergyPlugin;
import com.mtxgdn.plugin.builtin.ExplorationPlugin;
import com.mtxgdn.plugin.builtin.FarmPlugin;
import com.mtxgdn.plugin.builtin.FormationPlugin;
import com.mtxgdn.plugin.builtin.FriendPlugin;
import com.mtxgdn.plugin.builtin.InventoryPlugin;
import com.mtxgdn.plugin.builtin.MapPlugin;
import com.mtxgdn.plugin.builtin.PlayerPlugin;
import com.mtxgdn.plugin.builtin.PvpPlugin;
import com.mtxgdn.plugin.builtin.RankPlugin;
import com.mtxgdn.plugin.builtin.SearchPlugin;
import com.mtxgdn.plugin.builtin.SecretRealmPlugin;
import com.mtxgdn.plugin.builtin.SectPlugin;
import com.mtxgdn.plugin.builtin.SkillPlugin;
import com.mtxgdn.plugin.builtin.TeamPlugin;
import com.mtxgdn.plugin.builtin.TechniquePlugin;
import com.mtxgdn.plugin.builtin.TitlePlugin;
import com.mtxgdn.plugin.builtin.TradePlugin;
import com.mtxgdn.plugin.builtin.WorldBossPlugin;
import com.mtxgdn.plugin.experimental.EnergyExchangePlugin;
import com.mtxgdn.plugin.experimental.SectWarPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置插件清单 —— 全部编译在主 jar 中的玩法模块、实验性插件与消息通道适配器的唯一登记处。
 * <p>
 * 该清单是下列逻辑的共同事实来源：
 * <ul>
 *   <li>{@link com.mtxgdn.Main} 启动时把清单注册进 {@link BuiltInPluginRegistry}；</li>
 *   <li>{@link PluginSwitches#migrateLegacyKeys()} 与测试据此核对
 *       {@code plugins.<name>} 与旧配置键是否一一对应。</li>
 * </ul>
 * 新增内置插件时只需在此登记一次。
 */
public final class PluginCatalog {

    private PluginCatalog() {
    }

    /**
     * 创建全部内置插件实例（每次调用返回全新实例，便于测试隔离）。
     */
    public static List<BuiltInPlugin> createAll() {
        List<BuiltInPlugin> plugins = new ArrayList<>();

        // ---- 基础玩法模块（核心体验，默认全部启用）----
        plugins.add(new ExplorationPlugin());
        plugins.add(new SecretRealmPlugin());
        plugins.add(new TradePlugin());
        plugins.add(new SectPlugin());
        plugins.add(new FarmPlugin());
        plugins.add(new CavePlugin());

        // ---- 社交玩法模块 ----
        plugins.add(new ChatPlugin());
        plugins.add(new FriendPlugin());
        plugins.add(new RankPlugin());
        plugins.add(new SearchPlugin());

        // ---- 移动/养成玩法模块 ----
        plugins.add(new MapPlugin());
        plugins.add(new TeamPlugin());
        plugins.add(new TitlePlugin());
        plugins.add(new PlayerPlugin());

        // ---- 修炼/战斗玩法模块 ----
        plugins.add(new CultivationPlugin());
        plugins.add(new SkillPlugin());
        plugins.add(new PvpPlugin());
        plugins.add(new WorldBossPlugin());
        plugins.add(new InventoryPlugin());

        // ---- 经济/制造玩法模块 ----
        plugins.add(new EconomyPlugin());
        plugins.add(new EnergyPlugin());
        plugins.add(new TechniquePlugin());
        plugins.add(new CraftingPlugin());
        plugins.add(new BuffPlugin());
        plugins.add(new FormationPlugin());

        // ---- 实验性插件（独立分组，开关与稳定玩法分离，默认值由插件自身声明）----
        plugins.add(new SectWarPlugin());
        plugins.add(new EnergyExchangePlugin());

        // ---- 消息通道适配器（类型级总开关；实例级 enabled 仍在 config/adapters.json）----
        plugins.add(new AdapterChannelPlugin("onebot", "OneBot",
                "OneBot WebSocket/截图 消息通道（实例配置见 config/adapters.json）"));
        plugins.add(new AdapterChannelPlugin("qq", "QQ官方",
                "QQ 官方机器人消息通道（实例配置见 config/adapters.json）"));
        plugins.add(new AdapterChannelPlugin("minecraft", "Minecraft",
                "Minecraft 内嵌服务器消息通道（实例配置见 config/adapters.json）"));
        plugins.add(new AdapterChannelPlugin("minecraft-player", "Minecraft玩家通道",
                "Minecraft 玩家直连消息通道（实例配置见 config/adapters.json）"));

        return plugins;
    }
}
