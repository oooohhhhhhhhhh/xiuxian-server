package com.mtxgdn.rest;

import com.mtxgdn.plugin.BuiltInPlugin;
import com.mtxgdn.plugin.BuiltInPluginRegistry;
import com.mtxgdn.plugin.PluginCatalog;
import jakarta.ws.rs.Path;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块门控表必须覆盖 /game/ 下的每个接口，且模块名必须真实存在。
 *
 * <p><b>缺陷形态</b>：{@link ModuleGateFilter} 里那张
 * {@code MODULE_BY_FIRST_SEGMENT}（35 条）是"手写的第二份真相"。两个方向的漂移都<b>静默</b>：</p>
 * <ul>
 *   <li><b>漏登记</b>：新增一个 {@code /game/xxx} 接口时忘了在表里加一行，
 *       该接口就永远不会被模块开关拦住 —— 玩法被停用后它照样能用，且没有任何报错。
 *       （过滤器的 {@code module == null} 分支是直接 return 放行。）</li>
 *   <li><b>写错模块名</b>：值写成一个并不存在的插件名时，
 *       {@code isEnabledOrUnknown} 对未知名字返回 true，同样静默放行。</li>
 * </ul>
 *
 * <p>本测试把两个方向都钉死：表里的模块名必须来自 {@link PluginCatalog}，
 * 且玩家侧每个 {@code /game/xxx} 首段都必须被登记或显式豁免。</p>
 *
 * <p><b>为什么不读源码文本</b>：玩家接口原来只有一个 {@code GameResource}，
 * 现在已按"路径前缀 + 依赖集合"拆成 {@code GamePlayerResource}、
 * {@code GameInventoryResource}、{@code GameSectResource} 等多个资源类，
 * 每个类的类级 {@code @Path} 都是 {@code /game}。写死一个文件名（或一个类）会让
 * 拆出去的那部分端点悄悄脱离本测试的覆盖 —— 那等于把这道门禁关掉。这里改用
 * {@code ResourceConfig} 的包扫描（与 {@code Main} 注册资源的方式一致）反射读注解，
 * 新增资源类会被自动纳入，且不会被注释里的文字干扰。</p>
 */
class ModuleGateCoverageTest {

    /**
     * 明确不受模块门控管辖的路径首段。
     *
     * <p>目前只有 {@code i18n}（语言包下载/切换，属于基础设施而非玩法模块）
     * 与 {@code game}（类级 {@code @Path("/game")} 本身）。
     * 新增豁免必须写在这里并说明理由。</p>
     */
    private static final Set<String> EXEMPT_SEGMENTS = Set.of("i18n", "game");

    @Test
    @DisplayName("表里的模块名必须都是真实注册过的内置插件")
    void mappedModulesExist() {
        Set<String> registered = registeredPluginNames();
        List<String> bogus = new ArrayList<>();
        for (Map.Entry<String, String> e : ModuleGateFilter.moduleByFirstSegment().entrySet()) {
            if (!registered.contains(e.getValue())) {
                bogus.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertTrue(bogus.isEmpty(),
                "以下映射指向了不存在的内置插件名：未知名字会被 isEnabledOrUnknown 当成"
                        + "「启用」直接放行，等于门控失效：\n" + String.join("\n", bogus)
                        + "\n实际注册的插件: " + registered);
    }

    @Test
    @DisplayName("玩家侧每个 /game/xxx 首段都必须被门控表覆盖或显式豁免")
    void everyGamePathIsGated() {
        Set<String> segments = gameResourceFirstSegments();
        assertFalse(segments.isEmpty(), "没能从 /game 资源类解析出任何 /game 路径");

        Map<String, String> gate = ModuleGateFilter.moduleByFirstSegment();
        Set<String> ungated = new TreeSet<>();
        for (String segment : segments) {
            if (gate.containsKey(segment) || EXEMPT_SEGMENTS.contains(segment)) {
                continue;
            }
            ungated.add(segment);
        }
        assertTrue(ungated.isEmpty(),
                "以下 /game/xxx 路径首段既不在模块门控表里、也没有显式豁免 —— "
                        + "停用对应玩法后这些接口仍然可用，且不会有任何报错：\n" + ungated
                        + "\n（要么在 ModuleGateFilter.MODULE_BY_FIRST_SEGMENT 里登记，"
                        + "要么在 ModuleGateCoverageTest.EXEMPT_SEGMENTS 里写明豁免理由）");
    }

    @Test
    @DisplayName("玩家侧资源类确实被拆成了多个（防止本测试因为只扫到一个类而失去意义）")
    void gameResourcesAreSplitIntoMultipleClasses() {
        List<Class<?>> classes = gameResourceClasses();
        assertTrue(classes.size() > 1,
                "玩家接口应已按路径前缀拆成多个资源类，实际只发现: " + classes);
    }

    @Test
    @DisplayName("豁免清单不能无限膨胀：每一条都要是已知的基础设施路径")
    void exemptionsStayMinimal() {
        Set<String> knownInfrastructure = Set.of("i18n", "game");
        for (String exempt : EXEMPT_SEGMENTS) {
            assertTrue(knownInfrastructure.contains(exempt),
                    "未知的豁免项 " + exempt + "：豁免意味着该路径永不受模块开关管辖，"
                            + "必须谨慎且写明理由");
        }
        assertTrue(EXEMPT_SEGMENTS.size() <= 3,
                "豁免清单已经 " + EXEMPT_SEGMENTS.size() + " 条，是否该改成登记到门控表里了？");
    }

    // ==================== 夹具 ====================

    private static Set<String> registeredPluginNames() {
        Set<String> names = new LinkedHashSet<>();
        for (BuiltInPlugin plugin : PluginCatalog.createAll()) {
            names.add(plugin.getName());
        }
        assertFalse(names.isEmpty(), "PluginCatalog.createAll() 返回了空清单");
        assertTrue(BuiltInPluginRegistry.getInstance() != null);
        return names;
    }

    /**
     * 反射收集玩家侧所有 {@code /game/xxx} 路径的一级分段。
     *
     * <p>{@code UnifiedRestResource} 被排除：它是 Command 路由的动态分发器，
     * 方法级路径是 {@code {path:.*}} 这种兜底模板，本身不对应任何具体玩法接口。</p>
     */
    private static Set<String> gameResourceFirstSegments() {
        Set<String> segments = new TreeSet<>();
        for (Class<?> cls : gameResourceClasses()) {
            for (Method method : cls.getDeclaredMethods()) {
                Path path = method.getAnnotation(Path.class);
                if (path == null) {
                    continue;
                }
                String value = path.value();
                if (!value.startsWith("/")) {
                    continue;
                }
                String[] parts = value.split("/");
                if (parts.length < 2 || parts[1].isEmpty()) {
                    continue;
                }
                segments.add(parts[1]);
            }
        }
        return segments;
    }

    /**
     * {@code com.mtxgdn.rest} 包里类级 {@code @Path} 以 {@code /game} 开头的显式端点资源类。
     *
     * <p>只统计顶层类：{@code ResourceConfig.packages(...)} 扫的是 classpath，测试自己在
     * 同包里定义的嵌套替身也会被扫到，不能把它们当成被测资源类。</p>
     */
    private static List<Class<?>> gameResourceClasses() {
        List<Class<?>> result = new ArrayList<>();
        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");
        for (Class<?> cls : config.getClasses()) {
            if (cls.getEnclosingClass() != null) {
                continue;
            }
            Path path = cls.getAnnotation(Path.class);
            if (path == null || !path.value().startsWith("/game")) {
                continue;
            }
            if ("UnifiedRestResource".equals(cls.getSimpleName())) {
                continue;
            }
            result.add(cls);
        }
        return result;
    }
}
