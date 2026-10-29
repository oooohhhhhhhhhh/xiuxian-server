package com.mtxgdn.plugin;

import com.mtxgdn.game.sect.WarResult;
import com.mtxgdn.game.service.SectService;
import com.mtxgdn.i18n.I18nManager;
import com.mtxgdn.plugin.experimental.EnergyExchangePlugin;
import com.mtxgdn.plugin.experimental.SectWarPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实验性插件门控测试：实验性内容已从 {@code config/experimental.yml}
 * 迁移为内置插件（{@code plugins.experimental_*}），因此必须与普通玩法模块一样，
 * 停用后所有入口（服务层 → REST / WebSocket / 指令）都被拒绝。
 * <p>
 * 服务层门控读的是全局注册表单例，而 {@link BuiltInPluginRegistry#init()} 只生效一次，
 * 故本测试不重建单例，而是把开关文件指向独立临时配置后，
 * 用公开的运行时启停 API 关闭开关、断言被拦下、再恢复 —— 断言不依赖测试执行顺序。
 * 未初始化数据库，故门控放行后的分支会因缺少数据库连接而失败 ——
 * 测试只区分“是否被实验性开关拦截”。
 *
 * <p><b>为什么要显式 load 语言包</b>：本测试是纯单元测试，不继承
 * {@code TestBase}（那会连带初始化数据库，而这里刻意不初始化）。但门控
 * 拒绝时返回的是 {@code I18n.t("sect.sect_war_is_experimental_content")}，
 * 语言包没加载时 {@code I18n.t} 会把 key 本身原样返回 —— 于是"提示应说明
 * 这是实验性内容"这条断言必然失败。这曾经让本测试从初始导入起就一直红着。</p>
 */
class ExperimentalPluginGateTest {

    @BeforeAll
    static void loadLanguageBundle() {
        // 幂等：其他继承 TestBase 的测试类可能已经加载过
        I18nManager.load();
    }

    private static final String SECT_WAR = "experimental_sect_war";
    private static final String ENERGY_EXCHANGE = "experimental_energy_exchange";

    private Path savedConfigPath;
    private Path savedLegacyPath;

    @BeforeEach
    void pointAtIsolatedSwitchFile() throws Exception {
        // 独立开关文件：本测试会改开关，不能污染 target/test-config/plugins.yml 里其他测试的取值
        savedConfigPath = PluginSwitches.getConfigPath();
        savedLegacyPath = readStaticPath("legacyExperimentalPath");
        Path dir = Files.createTempDirectory("experimental-gate-");
        Path configPath = dir.resolve("plugins.yml");
        Files.writeString(configPath, "plugins:\n  " + SECT_WAR + ": true\n  "
                + ENERGY_EXCHANGE + ": true\n", StandardCharsets.UTF_8);
        PluginSwitches.useConfigPathsForTest(configPath, dir.resolve("experimental.yml"));

        // 只注册本测试要用的两个实验性插件：单例可能已被其他测试初始化过
        // （init() 只生效一次），故这枚注册是幂等的；register 不触发任何后台任务，
        // 后面用运行时启停 API 控制开关，断言不依赖测试执行顺序。
        BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
        registry.register(new SectWarPlugin());
        registry.register(new EnergyExchangePlugin());
        registry.enablePlugin(SECT_WAR);
        registry.enablePlugin(ENERGY_EXCHANGE);
    }

    @AfterEach
    void restoreState() {
        PluginSwitches.useConfigPathsForTest(savedConfigPath, savedLegacyPath);
    }

    @Test
    @DisplayName("宗门战停用时被服务层拒绝，开启后放行")
    void sectWarIsGatedByItsPluginSwitch() {
        assertFalse(pluginDefault(SECT_WAR), "宗门战插件自身默认值应为关闭（实验性内容默认不开放）");
        assertTrue(BuiltInPluginRegistry.isModuleEnabled(SECT_WAR), "前置：测试内已把开关打开");

        BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
        assertTrue(registry.disablePlugin(SECT_WAR), "应能停用实验性插件");

        WarResult blocked = new SectService().declareWar(1L, 2L);
        assertFalse(blocked.success());
        String blockedMessage = blocked.message();
        // 不直接断言中文文案：文案本身由语言包决定，改了措辞不该让门控测试变红。
        // 要钉的是两件事：提示已经翻译过（不是裸露的 key），且说的是"实验性内容"。
        assertFalse(blockedMessage.equals("sect.sect_war_is_experimental_content"),
                "停用时的提示必须是翻译后的文案，不能把 i18n key 露给用户: " + blockedMessage);
        assertTrue(blockedMessage.contains("实验性"),
                "停用时的提示应说明这是实验性内容: " + blockedMessage);

        // 重新启用后门控放行：后续失败来自业务/数据库校验，而不是实验性开关
        assertTrue(registry.enablePlugin(SECT_WAR));
        assertTrue(BuiltInPluginRegistry.isModuleEnabled(SECT_WAR));
        assertFalse(warResultMessage().contains("实验性"),
                "启用后不该再被实验性开关拦下: " + warResultMessage());
    }

    @Test
    @DisplayName("等价交换插件默认开启，且可独立于其他插件启停")
    void energyExchangeIsIndependentFromOtherPlugins() {
        assertTrue(pluginDefault(ENERGY_EXCHANGE), "等价交换默认开启（与旧 experimental.yml 一致）");
        assertTrue(BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE));

        BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
        assertTrue(registry.disablePlugin(ENERGY_EXCHANGE));
        assertFalse(BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE));
        // 宗门战开关不受影响：两个实验性插件各自独立
        assertTrue(BuiltInPluginRegistry.isModuleEnabled(SECT_WAR),
                "关掉等价交换不应影响宗门战开关");

        assertTrue(registry.enablePlugin(ENERGY_EXCHANGE));
        assertTrue(BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE));
    }

    @Test
    @DisplayName("未知插件名不得被当成“已停用”")
    void unknownPluginNameIsTreatedAsEnabled() {
        assertTrue(BuiltInPluginRegistry.isModuleEnabled("totally_unknown_plugin"),
                "未注册的名字一律放行，避免误封未知模块的指令");
    }

    /** 调用宗门战并返回其消息；门控放行后的异常也转为消息文本，便于统一断言。 */
    private String warResultMessage() {
        try {
            WarResult result = new SectService().declareWar(1L, 2L);
            return result.message();
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** 插件自身声明的默认值（config/plugins.yml 缺失该键时的取值）。 */
    private boolean pluginDefault(String name) {
        for (BuiltInPlugin plugin : PluginCatalog.createAll()) {
            if (plugin.getName().equals(name)) {
                return plugin.getDefaultEnabled();
            }
        }
        throw new AssertionError("PluginCatalog 缺少插件: " + name);
    }

    private static Path readStaticPath(String fieldName) throws Exception {
        Field field = PluginSwitches.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Path) field.get(null);
    }
}
