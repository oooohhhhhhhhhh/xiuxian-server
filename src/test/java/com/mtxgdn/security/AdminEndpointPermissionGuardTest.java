package com.mtxgdn.security;

import com.mtxgdn.permission.RequirePermission;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理后台的每个端点都必须声明权限码。
 *
 * <p><b>缺陷形态</b>：{@code PermissionFilter} 在方法/类上都没有 {@code @RequirePermission}
 * 时是<b>直接放行</b>的：</p>
 *
 * <pre>{@code
 * if (requiredPermission == null) { return; }   // fail-open
 * }</pre>
 *
 * <p>于是漏写注解的端点等于"任何能进管理后台的账号都能调"。实际情况：
 * {@code /admin/logs}（读全部服务端日志）、{@code /admin/players}（枚举所有玩家及其数据）、
 * {@code /admin/plugins}（插件清单）三个端点都没有注解 —— 一个只有
 * {@code admin.login} 的低权限账号就能把这些全读走。</p>
 *
 * <p>本测试把管理台<b>所有</b>资源类（{@code com.mtxgdn.rest} 包里类级 {@code @Path}
 * 以 {@code /admin} 开头的那些）里的 HTTP 端点过一遍，除白名单外必须带
 * {@code @RequirePermission}，防止以后新增端点时再漏。</p>
 *
 * <p><b>为什么不能只反射一个类</b>：管理台原来只有一个 {@code AdminResource}，
 * 现在已按"路径前缀 + 依赖集合"拆成 {@code AdminPlayersResource}、
 * {@code AdminUserResource}、{@code AdminDbResource} 等多个资源类。写死一个类会让
 * 拆出去的那部分端点悄悄脱离本测试的覆盖 —— 那等于把这道安全门禁关掉。</p>
 */
class AdminEndpointPermissionGuardTest {

    /**
     * 明确不需要权限码的端点。
     *
     * <ul>
     *   <li>{@code /login}：登录本身，未登录时也要能调；</li>
     *   <li>{@code /whoami}、{@code /me}：只回调用者自己的身份信息，不含他人数据。</li>
     * </ul>
     */
    private static final Set<String> ALLOWLIST = Set.of("/login", "/whoami", "/me");

    private static final List<Class<? extends Annotation>> HTTP_METHODS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class, PATCH.class);

    /**
     * 收集 {@code com.mtxgdn.rest} 包下所有挂在 {@code /admin} 下的资源类。
     *
     * <p>用 {@code ResourceConfig} 的包扫描（与 {@code Main} 注册资源的方式一致），
     * 这样以后新增的管理台资源类会被自动纳入，不需要再改本测试。</p>
     */
    private static List<Class<?>> adminResourceClasses() {
        List<Class<?>> result = new ArrayList<>();
        org.glassfish.jersey.server.ResourceConfig config =
                new org.glassfish.jersey.server.ResourceConfig().packages("com.mtxgdn.rest");
        for (Class<?> cls : config.getClasses()) {
            Path path = cls.getAnnotation(Path.class);
            if (path != null && path.value().startsWith("/admin")) {
                result.add(cls);
            }
        }
        return result;
    }

    @Test
    @DisplayName("管理台资源类确实被拆成了多个（防止本测试因为只扫到一个类而失去意义）")
    void adminResourcesAreSplitIntoMultipleClasses() {
        List<Class<?>> classes = adminResourceClasses();
        assertTrue(classes.size() > 1,
                "管理台资源应已按路径前缀拆成多个类，实际只发现: " + classes);
    }

    @Test
    @DisplayName("除登录/自身信息外，管理台每个端点都必须声明 @RequirePermission")
    void everyAdminEndpointDeclaresPermission() {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (Class<?> cls : adminResourceClasses()) {
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
                "没有扫描到任何管理台端点 —— 包扫描失效会让本测试变成零端点全通过");
        assertTrue(offenders.isEmpty(),
                "以下管理端点没有声明 @RequirePermission，而 PermissionFilter 在缺注解时是"
                        + "直接放行的（fail-open）—— 任何能登录后台的账号都能调用：\n"
                        + String.join("\n", offenders));
    }

    @Test
    @DisplayName("三个曾经漏注解的端点现在都带上了权限码")
    void previouslyUnprotectedEndpointsAreCovered() {
        assertPermission("/logs", "admin.logs.view");
        // 只读接口按本项目自己的划分用 admin.status（"读用 admin.status、写用 admin.users.manage"）。
        // admin.users.manage 不在 ADMIN 的默认权限里，挂在读接口上等于把版主/管理员
        // 看玩家列表的能力一起砍掉了；写接口（/players/{id}/edit 等）仍要求 admin.users.manage。
        assertPermission("/players", "admin.status");
        assertPermission("/plugins", "admin.plugins.manage");
    }

    /**
     * 在管理台全部资源类里找方法级 {@code @Path} 等于 {@code path} 的端点。
     *
     * <p>同一路径可能在多个类里出现（例如 {@code /players/{id}} 的 GET 与 DELETE），
     * 因此这里逐条校验：只要存在就必须带对权限码，且至少要存在一条。</p>
     */
    private static void assertPermission(String path, String expectedPermission) {
        int matched = 0;
        Map<String, String> actual = new LinkedHashMap<>();
        for (Class<?> cls : adminResourceClasses()) {
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
                assertEquals(expectedPermission, permission.value(),
                        "端点 " + path + " 的权限码应为 " + expectedPermission
                                + "，实际: " + permission.value());
                actual.put(cls.getSimpleName() + "." + method.getName(), permission.value());
            }
        }
        assertTrue(matched > 0,
                "管理台资源里找不到 @Path(\"" + path + "\") 端点，实际扫到: " + actual.keySet());
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
