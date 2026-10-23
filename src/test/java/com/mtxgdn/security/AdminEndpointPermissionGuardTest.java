package com.mtxgdn.security;

import com.mtxgdn.permission.RequirePermission;
import com.mtxgdn.rest.AdminResource;
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
import java.util.List;
import java.util.Set;

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
 * <p>本测试把 {@link AdminResource} 里所有 HTTP 端点过一遍，除白名单外必须带
 * {@code @RequirePermission}，防止以后新增端点时再漏。</p>
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

    @Test
    @DisplayName("除登录/自身信息外，AdminResource 的每个端点都必须声明 @RequirePermission")
    void everyAdminEndpointDeclaresPermission() {
        List<String> offenders = new ArrayList<>();

        for (Method method : AdminResource.class.getDeclaredMethods()) {
            if (!isHttpEndpoint(method)) {
                continue;
            }
            Path pathAnnotation = method.getAnnotation(Path.class);
            String path = pathAnnotation != null ? pathAnnotation.value() : "";
            if (ALLOWLIST.contains(path)) {
                continue;
            }
            if (method.getAnnotation(RequirePermission.class) == null
                    && AdminResource.class.getAnnotation(RequirePermission.class) == null) {
                offenders.add(method.getName() + "  @Path(\"" + path + "\")");
            }
        }

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

    private static void assertPermission(String path, String expectedPermission) {
        for (Method method : AdminResource.class.getDeclaredMethods()) {
            Path pathAnnotation = method.getAnnotation(Path.class);
            if (pathAnnotation == null || !path.equals(pathAnnotation.value())) {
                continue;
            }
            RequirePermission permission = method.getAnnotation(RequirePermission.class);
            assertTrue(permission != null,
                    "端点 " + path + " 必须声明 @RequirePermission");
            assertTrue(expectedPermission.equals(permission.value()),
                    "端点 " + path + " 的权限码应为 " + expectedPermission
                            + "，实际: " + permission.value());
            return;
        }
        throw new AssertionError("AdminResource 里找不到 @Path(\"" + path + "\") 端点");
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
