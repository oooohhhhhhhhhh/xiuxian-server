package com.mtxgdn.security;

import com.mtxgdn.rest.AdminAuthFilter;
import com.mtxgdn.rest.JwtAuthFilter;
import com.mtxgdn.rest.LanguageRequestFilter;
import com.mtxgdn.rest.ModuleGateFilter;
import com.mtxgdn.rest.PermissionFilter;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫 JAX-RS 过滤器的执行顺序。
 *
 * <p>为什么需要这个测试：{@link JwtAuthFilter} 把 {@code userId} 写进请求上下文，
 * {@link PermissionFilter} 读它。JAX-RS 规范规定未标注 {@code @Priority} 的过滤器
 * 按 {@link Priorities#USER}（5000）处理，所以只要有人漏掉 {@code @Priority}，
 * 权限校验就会先于鉴权执行、{@code userId} 恒为 null，导致
 * {@code GameResource} 全部带 {@code @RequirePermission} 的端点恒返回 401。
 * 这个 bug 曾经真实存在于代码里，而当时的测试全是反射/源码级断言，没有任何一个
 * 能发现它——因为问题只在运行时容器里才体现。
 *
 * <p>本测试用注解反射把顺序固化成可执行断言，成本极低，能防止回归。
 */
class AuthFilterOrderTest {

    /** 取过滤器的生效优先级：显式 @Priority 优先，否则 JAX-RS 默认 USER=5000。 */
    private static int priorityOf(Class<?> filterClass) {
        Priority annotation = filterClass.getAnnotation(Priority.class);
        return annotation != null ? annotation.value() : Priorities.USER;
    }

    @Test
    @DisplayName("JwtAuthFilter 必须显式声明 @Priority，不能依赖默认值")
    void jwtAuthFilterMustDeclarePriority() {
        assertNotNull(JwtAuthFilter.class.getAnnotation(Priority.class),
                "JwtAuthFilter 缺少 @Priority：默认 5000 会排在 PermissionFilter(2000) 之后，"
                        + "导致 userId 未写入就做权限校验，所有 @RequirePermission 玩家端点恒 401");
    }

    @Test
    @DisplayName("鉴权必须早于权限校验：JwtAuthFilter < PermissionFilter")
    void jwtAuthFilterRunsBeforePermissionFilter() {
        int jwt = priorityOf(JwtAuthFilter.class);
        int permission = priorityOf(PermissionFilter.class);
        assertTrue(jwt < permission,
                "JwtAuthFilter 优先级 " + jwt + " 必须小于 PermissionFilter 优先级 " + permission
                        + "，否则 PermissionFilter 读到的 userId 恒为 null");
    }

    @Test
    @DisplayName("鉴权必须晚于语言协商与管理端鉴权")
    void jwtAuthFilterRunsAfterLanguageAndAdminFilters() {
        int jwt = priorityOf(JwtAuthFilter.class);
        assertTrue(jwt > priorityOf(LanguageRequestFilter.class),
                "JwtAuthFilter 必须在 LanguageRequestFilter 之后：401 的错误信息需要先完成语言协商");
        assertTrue(jwt > priorityOf(AdminAuthFilter.class),
                "JwtAuthFilter 必须在 AdminAuthFilter 之后：/admin/* 由管理端过滤器负责，"
                        + "其写入的 adminRole 不应被玩家过滤器覆盖");
    }

    @Test
    @DisplayName("模块门控必须早于权限校验")
    void moduleGateRunsBeforePermissionFilter() {
        assertTrue(priorityOf(ModuleGateFilter.class) < priorityOf(PermissionFilter.class),
                "ModuleGateFilter 必须先于 PermissionFilter：玩法停用时应在权限检查之前就 403，"
                        + "避免把「模块已停用」误报成「权限不足」");
    }

    @Test
    @DisplayName("关键过滤器优先级数值固定，防止无意改动")
    void prioritiesArePinned() {
        assertEquals(Priorities.AUTHENTICATION, priorityOf(AdminAuthFilter.class));
        assertEquals(Priorities.AUTHENTICATION + 10, priorityOf(JwtAuthFilter.class));
        assertEquals(Priorities.AUTHORIZATION - 50, priorityOf(ModuleGateFilter.class));
        assertEquals(Priorities.AUTHORIZATION, priorityOf(PermissionFilter.class));
    }
}
