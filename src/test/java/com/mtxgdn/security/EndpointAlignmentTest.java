package com.mtxgdn.security;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.command.CommandScanner;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.rest.AdminResource;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三通道端点对齐回归测试。
 * <p>
 * 背景：洞府（cave）与阵法（formation）此前只有 OneBot 子命令、没有任何 addRoute，
 * 导致 QQ 端能玩、Web/REST 端完全访问不到。本测试锁定已补齐的 HTTP 路由，
 * 防止将来重构时被误删。
 * <p>
 * 命令通过 {@code Command} 构造函数自注册进 {@link CommandRegistry}，而发现依赖
 * {@link CommandScanner} 扫描包（与 Main.initializeCommands 一致），因此测试需先扫描。
 */
class EndpointAlignmentTest {

    @BeforeAll
    static void scanCommands() {
        // 命令通过构造函数自注册，发现依赖包扫描（与 Main.initializeCommands 一致）。
        // 同 JVM 内其它测试可能已扫描过，重复扫描只会重复注册同一实例，无副作用。
        CommandScanner.scanAndRegister("com.mtxgdn.onebot.command");
        assertTrue(CommandRegistry.count() > 0, "命令扫描失败：未注册任何命令");
    }

    /** 收集全部 HTTP 路由：path -> (method -> 权限码)。 */
    private static Map<String, Map<String, String>> collectHttpRoutes() {
        Map<String, Map<String, String>> routes = new HashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            List<RouteDefinition> defs = cmd.getRestEndpoints();
            if (defs == null) continue;
            for (RouteDefinition def : defs) {
                if (def.getPath() == null || def.getPath().isBlank()) continue;
                routes.computeIfAbsent(def.getPath(), k -> new HashMap<>())
                        .put(def.getMethod(), def.getPermission());
            }
        }
        return routes;
    }

    /** 断言某路径的 HTTP 方法正确（用 containsKey 而非取值比较——值里存的是权限码）。 */
    private static void assertMethod(Map<String, Map<String, String>> routes,
                                     String path, String method) {
        assertTrue(routes.containsKey(path), "缺少路由 " + path);
        assertTrue(routes.get(path).containsKey(method),
                path + " 应以 " + method + " 暴露，实际方法: " + routes.get(path).keySet());
    }

    /** 断言某路径的权限码正确。 */
    private static void assertPermission(Map<String, Map<String, String>> routes,
                                         String path, String method, String perm) {
        assertTrue(routes.containsKey(path), "缺少路由 " + path);
        String actual = routes.get(path).get(method);
        assertNotNull(actual, path + " (" + method + ") 必须声明权限码，否则任何登录用户都能操作");
        assertEquals(perm, actual, path + " 权限码不符");
    }

    @Test
    @DisplayName("对齐: 洞府(cave) 必须暴露 HTTP 路由，否则 Web 端无法访问洞府系统")
    void testCaveRoutesExposed() {
        Map<String, Map<String, String>> routes = collectHttpRoutes();

        assertMethod(routes, "cave/info", "GET");
        assertMethod(routes, "cave/create", "POST");
        assertMethod(routes, "cave/collect", "POST");
        assertMethod(routes, "cave/meditate", "POST");
        assertMethod(routes, "cave/levelup", "POST");

        // 权限码不得为空——否则任何登录用户都能操作
        assertPermission(routes, "cave/info", "GET", "game.cave.manage");
        assertPermission(routes, "cave/create", "POST", "game.cave.manage");
        assertPermission(routes, "cave/collect", "POST", "game.cave.manage");
        assertPermission(routes, "cave/meditate", "POST", "game.cave.manage");
        assertPermission(routes, "cave/levelup", "POST", "game.cave.manage");
    }

    @Test
    @DisplayName("对齐: 阵法(formation) 必须暴露 HTTP 路由，否则 Web 端无法访问阵法系统")
    void testFormationRoutesExposed() {
        Map<String, Map<String, String>> routes = collectHttpRoutes();

        assertMethod(routes, "formation/info", "GET");
        assertMethod(routes, "formation/list", "GET");
        assertMethod(routes, "formation/place", "POST");
        assertMethod(routes, "formation/upgrade", "POST");
        assertMethod(routes, "formation/remove", "POST");

        assertPermission(routes, "formation/info", "GET", "game.cultivate");
        assertPermission(routes, "formation/list", "GET", "game.cultivate");
        assertPermission(routes, "formation/place", "POST", "game.cultivate");
        assertPermission(routes, "formation/upgrade", "POST", "game.cultivate");
        assertPermission(routes, "formation/remove", "POST", "game.cultivate");
    }

    @Test
    @DisplayName("对齐: 新增路由不得与既有路径冲突（同路径同方法只能注册一次）")
    void testNoRouteCollisions() {
        Map<String, Set<String>> seen = new HashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            List<RouteDefinition> defs = cmd.getRestEndpoints();
            if (defs == null) continue;
            for (RouteDefinition def : defs) {
                if (def.getPath() == null || def.getPath().isBlank()) continue;
                String key = def.getMethod() + " " + def.getPath();
                Set<String> owners = seen.computeIfAbsent(key, k -> new HashSet<>());
                owners.add(cmd.getClass().getSimpleName());
            }
        }
        // 同 path+method 被两个不同 Command 注册 = 后者不可达，属配置错误
        StringBuilder dup = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : seen.entrySet()) {
            if (e.getValue().size() > 1) {
                dup.append("\n  ").append(e.getKey()).append(" 由多个命令注册: ").append(e.getValue());
            }
        }
        assertTrue(dup.length() == 0, "存在重复路由（后者永远不可达）:" + dup);
    }

    @Test
    @DisplayName("对齐: 洞府/阵法命令的 module 必须与 ModuleGateFilter 映射一致，否则关不掉")
    void testModuleDeclaredForGate() {
        Set<String> caveModules = new HashSet<>();
        Set<String> formationModules = new HashSet<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            String simple = cmd.getClass().getSimpleName();
            if ("CaveCommand".equals(simple)) caveModules.add(cmd.getModule());
            if ("FormationCommand".equals(simple)) formationModules.add(cmd.getModule());
        }
        assertTrue(caveModules.contains("cave"), "CaveCommand 应声明 module=cave");
        assertTrue(formationModules.contains("formation"), "FormationCommand 应声明 module=formation");
    }

    @Test
    @DisplayName("对齐: 行情(price) 必须暴露 HTTP 路由，否则 Web 端查不到成交行情")
    void testMarketPriceRouteExposed() {
        Map<String, Map<String, String>> routes = collectHttpRoutes();

        assertMethod(routes, "market/price", "GET");
        // 行情是只读查询，复用查看坊市的权限即可
        assertPermission(routes, "market/price", "GET", "game.market.view");
    }

    @Test
    @DisplayName("对齐: 坊市命令的 module 必须为 trade，否则插件停用时仍可交易")
    void testMarketModuleDeclaredForGate() {
        String[] marketCommands = {
                "MarketCommand", "MarketPriceCommand", "ListItemCommand",
                "BuyItemCommand", "CancelListingCommand", "MyListingsCommand"
        };
        Map<String, String> modules = new HashMap<>();
        for (Command cmd : CommandRegistry.getAllUnique()) {
            modules.put(cmd.getClass().getSimpleName(), cmd.getModule());
        }
        for (String simple : marketCommands) {
            assertTrue(modules.containsKey(simple), "坊市命令 " + simple + " 未被注册/扫描到");
            assertEquals("trade", modules.get(simple),
                    simple + " 应声明 module=trade，否则停用交易插件后该命令仍然可达");
        }
    }

    // ==================== 管理台坊市接口（WebUI 走 /api/admin/*） ====================

    /**
     * WebUI 的 api.js 以 {@code /api/admin} 为基址，而玩家侧的坊市接口挂在
     * {@code /api/game/market/*} 下 —— 两者路径空间不同，WebUI 无法直接复用玩家接口。
     * 因此管理台必须有自己的一套 /admin/market/* 接口，本测试锁定它们存在且权限码正确。
     */
    @Test
    @DisplayName("对齐: 管理台坊市接口必须存在（WebUI 走 /api/admin，无法复用 /game/market）")
    void testAdminMarketEndpointsExist() throws Exception {
        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");
        Set<String> found = new HashSet<>();
        for (Class<?> cls : config.getClasses()) {
            if (!AdminResource.class.isAssignableFrom(cls)) continue;
            // 类级 @Path("/admin") + 方法级 @Path("/market/...")
            if (cls.getAnnotation(jakarta.ws.rs.Path.class) == null) continue;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                jakarta.ws.rs.Path p = m.getAnnotation(jakarta.ws.rs.Path.class);
                if (p == null) continue;
                if (p.value().startsWith("/market/")) {
                    found.add(p.value());
                    RequirePermission rp = m.getAnnotation(RequirePermission.class);
                    assertNotNull(rp, "管理台接口 " + p.value() + " 必须声明 @RequirePermission");
                    assertEquals("admin.economy", rp.value(),
                            "管理台坊市接口 " + p.value() + " 应使用 admin.economy 权限");
                    assertNotNull(m.getAnnotation(jakarta.ws.rs.GET.class),
                            "管理台坊市接口 " + p.value() + " 应为只读 GET");
                }
            }
        }
        assertTrue(found.contains("/market/listings"),
                "缺少管理台挂单总览接口 /admin/market/listings，实际: " + found);
        assertTrue(found.contains("/market/price"),
                "缺少管理台行情接口 /admin/market/price，实际: " + found);
    }

    /**
     * WebUI 是纯静态资源，接线散落在 index.html / app.js / layout.js 三处，
     * 任何一处漏改都会导致「侧边栏点了没反应」这种静默失败。这里做一致性校验。
     */
    @Test
    @DisplayName("对齐: WebUI 坊市页三处接线必须齐全（导航项 / tab 容器 / 模块注册）")
    void testWebUiMarketModuleWired() throws Exception {
        String html = readResource("webadmin/index.html");
        String appJs = readResource("webadmin/assets/js/app.js");
        String layoutJs = readResource("webadmin/assets/js/modules/layout.js");
        String marketJs = readResource("webadmin/assets/js/modules/market.js");

        assertTrue(html.contains("id=\"tab-market\""),
                "index.html 缺少坊市 tab 容器 (id=tab-market)");
        assertTrue(html.contains("data-tab=\"market\""),
                "index.html 缺少坊市侧边栏导航项 (data-tab=market)");
        assertTrue(appJs.contains("./modules/market.js"),
                "app.js 未 import 坊市模块");
        assertTrue(appJs.contains("market,") || appJs.contains("market\n"),
                "app.js 未把坊市模块挂到 window.app.modules");

        // layout.js 三处必须齐全：标题、管理员可见标签、加载器。
        // 注意不能只用 contains("market") —— 该词在文件里出现多次，漏掉任何一处都能蒙混过关。
        assertTrue(layoutJs.contains("market: '🏪 坊市管理'"),
                "layout.js 的 TAB_TITLES 缺少坊市标题");
        assertTrue(layoutJs.matches("(?s).*ADMIN_TABS\\s*=\\s*\\[[^\\]]*'market'[^\\]]*\\].*"),
                "layout.js 的 ADMIN_TABS 未包含 'market'（管理员侧边栏不会显示该页）");
        assertTrue(layoutJs.contains("market: () => window.app.modules.market.load()"),
                "layout.js 的 TAB_LOADERS 缺少坊市加载器（点开标签页会空白）");

        assertNotNull(marketJs, "market.js 模块文件缺失");
        assertTrue(marketJs.contains("export async function load"),
                "market.js 必须导出 load()（layout.js 的 TAB_LOADERS 依赖它）");
    }

    // ==================== 管理台地图接口（WebUI 走 /api/admin/*） ====================

    /**
     * 通用表管理只能看到 map_locations 的裸行（境界是数字、邻接在另一张表、加成是两列
     * DOUBLE），管理员没法从后台确认"问道峰到底要什么境界"。地图页依赖下面两个接口，
     * 本测试锁定它们存在且权限码正确。
     */
    @Test
    @DisplayName("对齐: 管理台地图接口必须存在（WebUI 走 /api/admin，无法复用 /game/map）")
    void testAdminMapEndpointsExist() throws Exception {
        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");
        Set<String> found = new HashSet<>();
        // 按 path 收集 HTTP 方法集合：集合资源本来就允许 GET（总览）+ POST（新增）共存，
        // 旧写法"每个同 path 的方法都必须是 GET"会把这个合法组合误判为违规。
        Map<String, Set<String>> methodsByPath = new HashMap<>();
        for (Class<?> cls : config.getClasses()) {
            if (!AdminResource.class.isAssignableFrom(cls)) continue;
            if (cls.getAnnotation(jakarta.ws.rs.Path.class) == null) continue;
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                jakarta.ws.rs.Path p = m.getAnnotation(jakarta.ws.rs.Path.class);
                if (p == null) continue;
                if (!p.value().startsWith("/map/")) continue;
                found.add(p.value());
                RequirePermission rp = m.getAnnotation(RequirePermission.class);
                assertNotNull(rp, "管理台接口 " + p.value() + " 必须声明 @RequirePermission");
                assertEquals("admin.database.access", rp.value(),
                        "管理台地图接口 " + p.value() + " 应使用 admin.database.access 权限");
                methodsByPath.computeIfAbsent(p.value(), k -> new HashSet<>()).add(httpMethodOf(m));
            }
        }
        assertTrue(found.contains("/map/locations"),
                "缺少地图总览接口 /admin/map/locations，实际: " + found);
        assertTrue(found.contains("/map/locations/{id}"),
                "缺少地图编辑接口 /admin/map/locations/{id}，实际: " + found);

        // 总览必须是只读 GET：地图页读的是它，若被改成写接口，打开页面就会改数据
        assertTrue(methodsByPath.getOrDefault("/map/locations", Set.of()).contains("GET"),
                "地图总览 /admin/map/locations 必须提供只读 GET，实际: "
                        + methodsByPath.get("/map/locations"));
        assertTrue(methodsByPath.getOrDefault("/map/locations/{id}", Set.of()).contains("POST"),
                "地图编辑 /admin/map/locations/{id} 必须提供 POST，实际: "
                        + methodsByPath.get("/map/locations/{id}"));

        // 增删与连接/偏置编辑：管理台"补齐"的那部分，缺了管理员就只能改库
        assertTrue(methodsByPath.getOrDefault("/map/locations", Set.of()).contains("POST"),
                "缺少新建地点接口 POST /admin/map/locations");
        assertTrue(methodsByPath.getOrDefault("/map/locations/{id}", Set.of()).contains("DELETE"),
                "缺少删除地点接口 DELETE /admin/map/locations/{id}");
        assertTrue(found.contains("/map/connections"), "缺少连接编辑接口 /admin/map/connections");
        assertTrue(found.contains("/map/event-bias"), "缺少事件偏置编辑接口 /admin/map/event-bias");
        assertTrue(found.contains("/map/wilderness"), "缺少野外分片总览接口 /admin/map/wilderness");
        assertTrue(found.contains("/map/wilderness/restock"), "缺少野外补货接口 /admin/map/wilderness/restock");
    }

    /** 取方法上的 HTTP 动词注解；本测试只关心地图接口用到的这几种。 */
    private static String httpMethodOf(java.lang.reflect.Method m) {
        if (m.getAnnotation(jakarta.ws.rs.GET.class) != null) return "GET";
        if (m.getAnnotation(jakarta.ws.rs.POST.class) != null) return "POST";
        if (m.getAnnotation(jakarta.ws.rs.PUT.class) != null) return "PUT";
        if (m.getAnnotation(jakarta.ws.rs.DELETE.class) != null) return "DELETE";
        return "UNKNOWN";
    }

    /** WebUI 是纯静态资源，接线散落在三处，任何一处漏改都会"侧边栏点了没反应"。 */
    @Test
    @DisplayName("对齐: WebUI 地图页三处接线必须齐全（导航项 / tab 容器 / 模块注册）")
    void testWebUiMapModuleWired() throws Exception {
        String html = readResource("webadmin/index.html");
        String appJs = readResource("webadmin/assets/js/app.js");
        String layoutJs = readResource("webadmin/assets/js/modules/layout.js");
        String mapJs = readResource("webadmin/assets/js/modules/map.js");

        assertTrue(html.contains("id=\"tab-map\""),
                "index.html 缺少地图 tab 容器 (id=tab-map)");
        assertTrue(html.contains("data-tab=\"map\""),
                "index.html 缺少地图侧边栏导航项 (data-tab=map)");
        assertTrue(appJs.contains("./modules/map.js"),
                "app.js 未 import 地图模块");
        assertTrue(appJs.contains("map,") || appJs.contains("map\n"),
                "app.js 未把地图模块挂到 window.app.modules");

        assertTrue(layoutJs.contains("map: '🗺️ 地图管理'"),
                "layout.js 的 TAB_TITLES 缺少地图标题");
        assertTrue(layoutJs.matches("(?s).*ADMIN_TABS\\s*=\\s*\\[[^\\]]*'map'[^\\]]*\\].*"),
                "layout.js 的 ADMIN_TABS 未包含 'map'（管理员侧边栏不会显示该页）");
        assertTrue(layoutJs.contains("map: () => window.app.modules.map.load()"),
                "layout.js 的 TAB_LOADERS 缺少地图加载器（点开标签页会空白）");

        assertNotNull(mapJs, "map.js 模块文件缺失");
        assertTrue(mapJs.contains("export async function load"),
                "map.js 必须导出 load()（layout.js 的 TAB_LOADERS 依赖它）");
    }

    /**
     * 读取 WebUI 文本资源。
     * <p>
     * 必须优先读源码目录 {@code src/main/resources}，而不是 classpath：测试运行时
     * {@code target/classes} 里是<b>上一次构建</b>拷贝的资源，若只读 classpath，
     * 改了 src 里的 html/js 而没重新 process-resources 时，测试仍会对着旧副本通过
     * （假绿）。源码目录不存在时（例如只跑 jar 的极端场景）才回退 classpath。
     */
    private static String readResource(String path) throws Exception {
        java.io.File src = new java.io.File("src/main/resources/" + path);
        if (src.isFile()) {
            return java.nio.file.Files.readString(src.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (java.io.InputStream is = EndpointAlignmentTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "WebUI 资源缺失: " + path + "（源码目录与 classpath 均未找到）");
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
