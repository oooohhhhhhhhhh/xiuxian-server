package com.mtxgdn.plugin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验内置插件清单（{@link PluginCatalog}）与插件开关配置的一致性：
 * <ul>
 *   <li>全部内置玩法模块的注册名与 config/plugins.yml 的 plugins.* 键一致；</li>
 *   <li>没有重名模块（重名会导致 WebUI 启停互相覆盖、门控失效）；</li>
 *   <li>实验性插件单独成组（category=experimental），且默认值按插件自身声明；</li>
 *   <li>每个模块的开关键都写在内置模板里，避免升级后被悄悄重置。</li>
 * </ul>
 */
class BuiltInModuleSetTest {

    /** 玩法模块（不含实验性插件与消息通道适配器），与 PluginCatalog 保持一致。 */
    private static final String[][] MODULES = {
            {"exploration", "com.mtxgdn.plugin.builtin.ExplorationPlugin"},
            {"secret_realm", "com.mtxgdn.plugin.builtin.SecretRealmPlugin"},
            {"trade", "com.mtxgdn.plugin.builtin.TradePlugin"},
            {"sect", "com.mtxgdn.plugin.builtin.SectPlugin"},
            {"farm", "com.mtxgdn.plugin.builtin.FarmPlugin"},
            {"cave", "com.mtxgdn.plugin.builtin.CavePlugin"},
            {"chat", "com.mtxgdn.plugin.builtin.ChatPlugin"},
            {"friend", "com.mtxgdn.plugin.builtin.FriendPlugin"},
            {"team", "com.mtxgdn.plugin.builtin.TeamPlugin"},
            {"map", "com.mtxgdn.plugin.builtin.MapPlugin"},
            {"title", "com.mtxgdn.plugin.builtin.TitlePlugin"},
            {"technique", "com.mtxgdn.plugin.builtin.TechniquePlugin"},
            {"crafting", "com.mtxgdn.plugin.builtin.CraftingPlugin"},
            {"buff", "com.mtxgdn.plugin.builtin.BuffPlugin"},
            {"formation", "com.mtxgdn.plugin.builtin.FormationPlugin"},
            {"energy", "com.mtxgdn.plugin.builtin.EnergyPlugin"},
            {"cultivation", "com.mtxgdn.plugin.builtin.CultivationPlugin"},
            {"skill", "com.mtxgdn.plugin.builtin.SkillPlugin"},
            {"pvp", "com.mtxgdn.plugin.builtin.PvpPlugin"},
            {"world_boss", "com.mtxgdn.plugin.builtin.WorldBossPlugin"},
            {"inventory", "com.mtxgdn.plugin.builtin.InventoryPlugin"},
            {"economy", "com.mtxgdn.plugin.builtin.EconomyPlugin"},
            {"rank", "com.mtxgdn.plugin.builtin.RankPlugin"},
            {"search", "com.mtxgdn.plugin.builtin.SearchPlugin"},
            {"player", "com.mtxgdn.plugin.builtin.PlayerPlugin"}
    };

    /** 实验性插件：从玩法模块里拆出来单独登记，开关独立、默认值由插件声明。 */
    private static final String[][] EXPERIMENTAL_PLUGINS = {
            {"experimental_sect_war", "com.mtxgdn.plugin.experimental.SectWarPlugin"},
            {"experimental_energy_exchange", "com.mtxgdn.plugin.experimental.EnergyExchangePlugin"}
    };

    @Test
    void allModuleNamesUnique() {
        Set<String> names = new HashSet<>();
        for (String[] m : MODULES) {
            assertTrue(names.add(m[0]), "模块名重复: " + m[0]);
        }
        for (String[] m : EXPERIMENTAL_PLUGINS) {
            assertTrue(names.add(m[0]), "模块名重复: " + m[0]);
        }
        assertEquals(MODULES.length + EXPERIMENTAL_PLUGINS.length, names.size());
    }

    @Test
    void eachModuleClassReportsExpectedName() throws Exception {
        for (String[] m : MODULES) {
            assertPluginMeta(m[1], m[0]);
        }
    }

    @Test
    void experimentalPluginsAreSeparatedFromGameplayModules() throws Exception {
        Set<String> gameplayNames = new HashSet<>();
        for (String[] m : MODULES) {
            gameplayNames.add(m[0]);
        }
        for (String[] m : EXPERIMENTAL_PLUGINS) {
            BuiltInPlugin plugin = instantiate(m[1]);
            assertEquals(m[0], plugin.getName());
            assertEquals("experimental", plugin.getCategory(),
                    "实验性插件必须在 WebUI 单独成组: " + m[0]);
            assertTrue(plugin.getVersion().startsWith("0."),
                    "实验性插件版本应处于 0.x 阶段: " + m[0]);
            assertTrue(plugin.getDescription().contains("实验性"),
                    "实验性插件描述需标明“实验性”: " + m[0]);
            assertFalse(gameplayNames.contains(plugin.getName()));
        }
        // 宗门战为实验性内容，默认关闭；能量转化沿用旧 experimental.yml 的默认（开启）
        assertEquals(false, pluginDefault("experimental_sect_war"));
        assertEquals(true, pluginDefault("experimental_energy_exchange"));
    }

    @Test
    void catalogCoversEveryRegisteredPluginAndTemplateKey() throws Exception {
        List<BuiltInPlugin> catalog = PluginCatalog.createAll();
        Set<String> names = new HashSet<>();
        for (BuiltInPlugin plugin : catalog) {
            assertTrue(names.add(plugin.getName()), "PluginCatalog 出现重复插件: " + plugin.getName());
            // 每个插件都必须在内置 plugins.yml 模板里有开关占位，
            // 否则升级/新装后该插件只能靠代码默认值，用户在配置文件里看不到它
            String raw = PluginSwitches.getRaw(plugin.getName());
            assertNotNull(raw, "plugins.yml 模板缺少插件开关: " + plugin.getName());
            assertEquals(plugin.getDefaultEnabled(), Boolean.parseBoolean(raw),
                    "模板默认值必须与插件的 getDefaultEnabled() 一致: " + plugin.getName());
        }
        assertEquals(25 + EXPERIMENTAL_PLUGINS.length + 4, names.size(),
                "PluginCatalog 应覆盖 25 个玩法模块 + 2 个实验性插件 + 4 个消息通道适配器");
    }

    /** 校验插件类名、注册名与配置键一致。 */
    private void assertPluginMeta(String className, String expectedName) throws Exception {
        BuiltInPlugin plugin = instantiate(className);
        assertEquals(expectedName, plugin.getName(), "模块 " + className + " 注册名与配置键不一致");
        assertNotNull(plugin.getDescription());
        assertTrue(!plugin.getDescription().isEmpty());
        assertEquals("gameplay", plugin.getCategory());
        assertTrue(plugin.getDefaultEnabled(), "稳定玩法模块默认应启用");
    }

    private BuiltInPlugin instantiate(String className) throws Exception {
        return (BuiltInPlugin) Class.forName(className).getDeclaredConstructor().newInstance();
    }

    private boolean pluginDefault(String name) throws Exception {
        for (BuiltInPlugin plugin : PluginCatalog.createAll()) {
            if (plugin.getName().equals(name)) {
                return plugin.getDefaultEnabled();
            }
        }
        throw new AssertionError("PluginCatalog 缺少插件: " + name);
    }
}
