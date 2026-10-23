package com.mtxgdn.security;

import com.mtxgdn.permission.RequirePermission;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家侧的每个端点都必须声明权限码（或落在写明理由的豁免清单里）。
 *
 * <p><b>缺陷形态</b>：{@code PermissionFilter} 在方法/类上都没有 {@code @RequirePermission}
 * 时是<b>直接放行</b>的（fail-open）。于是漏写注解的玩家端点等于"任何登录用户都能调"——
 * 例如 {@code /game/item/add} 一旦漏注解，普通玩家就能给自己发物品。</p>
 *
 * <p><b>为什么不能只反射一个类</b>：玩家接口原来只有一个 {@code GameResource}，
 * 现在已按"路径前缀 + 依赖集合"拆成 {@code GamePlayerResource}、
 * {@code GameInventoryResource}、{@code GameSectResource} 等十几个资源类。
 * 写死一个类会让拆出去的那部分端点悄悄脱离本测试的覆盖 —— 那等于把这道安全门禁关掉。
 * 因此这里扫描 {@code com.mtxgdn.rest} 包里所有类级 {@code @Path} 以 {@code /game}
 * 开头的资源类（{@code ResourceConfig} 包扫描，与 {@code Main} 注册资源的方式一致）。</p>
 *
 * <p>{@code UnifiedRestResource} 被排除：它是 Command 路由的动态分发器，
 * 权限码来自 {@code RouteDefinition} 并在 {@code dispatch()} 内部校验，
 * 方法上没有（也不需要）{@code @RequirePermission}。</p>
 */
class GameEndpointPermissionGuardTest {

    /**
     * 明确不需要权限码的端点。
     *
     * <ul>
     *   <li>{@code /i18n/bundle}、{@code /i18n/languages}：语言包与语言列表，
     *       登录页在拿到 token 之前就要用（"忘记密码"等提示也要本地化）；</li>
     *   <li>{@code /players/search}：按名字搜索玩家，只返回公开字段（昵称/境界/等级），
     *       社交玩法（加好友、组队邀请）在建立关系前就要能搜到人；</li>
     *   <li>{@code /status}：服务器状态（在线人数/内存/版本），运维探活用；</li>
     *   <li>{@code /season}：当前季节与倍率，属于全局公开信息。</li>
     * </ul>
     *
     * <p>这几条都是拆分前就没有权限码的既有行为，拆分不改变任何权限语义 ——
     * 这里只是把"为什么可以没有"记录下来，防止将来有人顺手给它们加上权限码
     * 把公开接口变成需要授权。</p>
     */
    private static final Set<String> ALLOWLIST = Set.of(
            "/i18n/bundle", "/i18n/languages", "/players/search", "/status", "/season");

    private static final List<Class<? extends Annotation>> HTTP_METHODS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class, PATCH.class);

    @Test
    @DisplayName("玩家侧资源类确实被拆成了多个（防止本测试因为只扫到一个类而失去意义）")
    void gameResourcesAreSplitIntoMultipleClasses() {
        List<Class<?>> classes = gameResourceClasses();
        assertTrue(classes.size() > 1,
                "玩家接口应已按路径前缀拆成多个资源类，实际只发现: " + classes);
    }

    @Test
    @DisplayName("除公开接口外，玩家侧每个端点都必须声明 @RequirePermission")
    void everyGameEndpointDeclaresPermission() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Class<?> cls : gameResourceClasses()) {
            for (Method method : cls.getDeclaredMethods()) {
                if (!isHttpEndpoint(method)) {
                    continue;
                }
                checked++;
                Path pathAnnotation = method.getAnnotation(Path.class);
                String path = pathAnnotation != null ? pathAnnotation.value() : "";
                if (ALLOWLIST.contains(path)) {
                    continue;
                }
                if (method.getAnnotation(RequirePermission.class) == null
                        && cls.getAnnotation(RequirePermission.class) == null) {
                    offenders.add(cls.getSimpleName() + "." + method.getName()
                            + "  @Path(\"" + path + "\")");
                }
            }
        }

        assertTrue(checked > 0,
                "没有扫描到任何玩家端点 —— 包扫描失效会让本测试变成零端点全通过");
        assertTrue(offenders.isEmpty(),
                "以下玩家端点没有声明 @RequirePermission，而 PermissionFilter 在缺注解时是"
                        + "直接放行的（fail-open）—— 任何登录用户都能调用：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("豁免清单里的端点确实存在（防止清单随端点删除而失效）")
    void allowlistedEndpointsStillExist() {
        List<String> seen = new ArrayList<>();
        for (Class<?> cls : gameResourceClasses()) {
            for (Method method : cls.getDeclaredMethods()) {
                Path pathAnnotation = method.getAnnotation(Path.class);
                if (pathAnnotation != null && ALLOWLIST.contains(pathAnnotation.value())) {
                    seen.add(pathAnnotation.value());
                }
            }
        }
        for (String allowed : ALLOWLIST) {
            assertTrue(seen.contains(allowed),
                    "豁免清单里的 " + allowed + " 已经不存在于任何玩家资源类里，请清理豁免项");
        }
    }

    @Test
    @DisplayName("高价值的写端点必须带权限码（拆分后逐个钉死）")
    void highValueWriteEndpointsKeepTheirPermission() {
        assertPermission("/item/add", "admin.items.give");
        assertPermission("/player/create", "game.player.create");
        assertPermission("/sect/disband", "game.sect.manage");
        assertPermission("/market/buy", "game.market.buy");
    }

    /**
     * 收集 {@code com.mtxgdn.rest} 包下所有挂在 {@code /game} 下的显式端点资源类。
     *
     * <p>用 {@code ResourceConfig} 的包扫描（与 {@code Main} 注册资源的方式一致），
     * 这样以后新增的玩家资源类会被自动纳入，不需要再改本测试。只统计顶层类：
     * 包扫描走的是 classpath，测试自己在同包里定义的嵌套替身也会被扫到。</p>
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

    /** 在玩家侧全部资源类里找方法级 {@code @Path} 等于 {@code path} 的端点并校验权限码。 */
    private static void assertPermission(String path, String expectedPermission) {
        int matched = 0;
        for (Class<?> cls : gameResourceClasses()) {
            for (Method method : cls.getDeclaredMethods()) {
                Path pathAnnotation = method.getAnnotation(Path.class);
                if (pathAnnotation == null || !path.equals(pathAnnotation.value())) {
                    continue;
                }
                if (!isHttpEndpoint(method)) {
                    continue;
                }
                matched++;
                RequirePermission permission = method.getAnnotation(RequirePermission.class);
                assertNotNull(permission,
                        "端点 " + path + " 必须声明 @RequirePermission");
                assertTrue(expectedPermission.equals(permission.value()),
                        "端点 " + path + " 的权限码应为 " + expectedPermission
                                + "，实际: " + permission.value());
            }
        }
        assertTrue(matched > 0,
                "玩家侧资源里找不到 @Path(\"" + path + "\") 端点");
    }

    private static boolean isHttpEndpoint(Method method) {
        for (Class<? extends Annotation> annotation : HTTP_METHODS) {
            if (method.isAnnotationPresent(annotation)) {
                return true;
            }
        }
        return false;
    }
}
