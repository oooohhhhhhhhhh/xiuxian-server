package com.mtxgdn.rest;

import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理台端点<b>路由可达性</b>回归测试。
 *
 * <p><b>为什么需要它</b>：管理台原来只有一个 {@code AdminResource}，现在按
 * "路径前缀 + 依赖集合"拆成了 {@code AdminPlayersResource}、{@code AdminUserResource}、
 * {@code AdminDbResource} 等十几个资源类。{@code EndpointAlignmentTest} 与
 * {@code AdminWritePermissionTest} 只用<b>反射</b>读注解 —— 它们能证明"路径字符串没变"，
 * 却证明不了"Jersey 真的把这条路径路由到了那个方法"。JAX-RS 的路径拼接、
 * 模板优先级、同根路径多资源类的合并都发生在<b>运行时</b>，注解层面看不出来。</p>
 *
 * <p><b>做法</b>：起一个真实的 Grizzly + Jersey 服务（只注册资源类，不注册任何
 * {@code @Provider} 过滤器，避免鉴权挡住请求），再注册一个"匹配到资源方法就以 418 中止"
 * 的过滤器 —— 于是：</p>
 * <ul>
 *   <li>路由成功 → 418（资源方法体一行都不会执行，测试不会碰数据库）；</li>
 *   <li>路由失败 → 404。</li>
 * </ul>
 * <p>最后用两条不存在的路径做负向对照，确保"418 才是成功"这个判据本身有效 ——
 * 否则一个把所有请求都吞掉的过滤器会让本测试变成永远通过的空壳。</p>
 *
 * <p>本测试覆盖拆分后的全部管理台端点（含原先就在 {@code AdminPlayerDataResource}
 * 里的 {@code /admin/player-data/*}）。</p>
 */
class AdminRouteResolutionTest {

    /** 匹配到资源方法的请求一律以 418 中止，避免真的执行端点逻辑。 */
    public static class AbortFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.abortWith(Response.status(418).build());
        }
    }

    private static final String[] GET_PATHS = {
            "/admin/status", "/admin/logs", "/admin/plugins", "/admin/plugins/list",
            "/admin/roles", "/admin/permissions", "/admin/groups", "/admin/users",
            "/admin/players", "/admin/players/1", "/admin/player-traces",
            "/admin/db/tables", "/admin/db/tables/users", "/admin/db/tables/users/1",
            "/admin/backup", "/admin/redeem-codes", "/admin/blacklist",
            "/admin/market/listings", "/admin/market/price", "/admin/items",
            "/admin/realms", "/admin/titles", "/admin/titles/catalog",
            "/admin/spiritual-roots", "/admin/newbie-reward/config",
            "/admin/oauth/config", "/admin/i18n/status", "/admin/system/info",
            "/admin/onebot/groups", "/admin/adapters", "/admin/map/locations",
            "/admin/map/wilderness", "/admin/player-data/list",
            "/admin/player-data/stats", "/admin/player-data/verify/1",
            "/admin/players/1/titles", "/admin/user/1/permissions",
            "/admin/stats/messages", "/admin/stats/commands",
            "/admin/me", "/admin/whoami"
    };

    private static final String[][] OTHER_PATHS = {
            {"POST", "/admin/login"}, {"POST", "/admin/shutdown"}, {"POST", "/admin/announce"},
            {"POST", "/admin/authority"}, {"POST", "/admin/adapters/reload"},
            {"PUT", "/admin/adapters"}, {"POST", "/admin/groups"}, {"POST", "/admin/groups/x"},
            {"DELETE", "/admin/groups/x"}, {"POST", "/admin/groups/x/permissions"},
            {"DELETE", "/admin/groups/x/permissions/p"},
            {"POST", "/admin/groups/x/permissions/set"},
            {"POST", "/admin/user/1/role"}, {"DELETE", "/admin/user/1/role/ADMIN"},
            {"POST", "/admin/user/create-admin"}, {"POST", "/admin/user/1/password"},
            {"DELETE", "/admin/user/1"}, {"POST", "/admin/user/1/permissions"},
            {"DELETE", "/admin/user/1/permissions/p"},
            {"POST", "/admin/database/clear_players"}, {"POST", "/admin/database/reset_all"},
            {"POST", "/admin/db/tables/users"}, {"POST", "/admin/db/tables/users/1"},
            {"DELETE", "/admin/db/tables/users/1"}, {"POST", "/admin/backup/import"},
            {"POST", "/admin/players/1/give"}, {"POST", "/admin/players/1/edit"},
            {"DELETE", "/admin/players/1"}, {"POST", "/admin/players/1/rename"},
            {"POST", "/admin/players/1/spiritual-root"}, {"POST", "/admin/players/1/title"},
            {"POST", "/admin/players/1/title/add"}, {"POST", "/admin/players/1/titles"},
            {"DELETE", "/admin/players/1/titles/T"}, {"POST", "/admin/plugins/reload/x"},
            {"POST", "/admin/plugins/unload/x"}, {"POST", "/admin/plugins/enable/x"},
            {"POST", "/admin/plugins/disable/x"}, {"POST", "/admin/plugins/load/x.jar"},
            {"POST", "/admin/plugins/watcher/start"}, {"POST", "/admin/plugins/watcher/stop"},
            {"POST", "/admin/redeem-codes"}, {"POST", "/admin/redeem-codes/1"},
            {"DELETE", "/admin/redeem-codes/1"}, {"POST", "/admin/blacklist"},
            {"DELETE", "/admin/blacklist/qq/1"}, {"DELETE", "/admin/blacklist/user/1"},
            {"POST", "/admin/onebot/groups/1/autoMute"},
            {"POST", "/admin/onebot/groups/1/muteDuration"},
            {"DELETE", "/admin/onebot/groups/1"},
            {"POST", "/admin/newbie-reward/config"}, {"POST", "/admin/oauth/config"},
            {"POST", "/admin/i18n/export"}, {"POST", "/admin/i18n/reload"},
            {"POST", "/admin/map/locations"}, {"POST", "/admin/map/locations/1"},
            {"DELETE", "/admin/map/locations/1"}, {"POST", "/admin/map/connections"},
            {"DELETE", "/admin/map/connections"}, {"POST", "/admin/map/event-bias"},
            {"DELETE", "/admin/map/event-bias"}, {"POST", "/admin/map/wilderness/restock"},
            {"POST", "/admin/player-data/export/1"}, {"POST", "/admin/player-data/export-all"}
    };

    @Test
    @DisplayName("拆分后每个管理台端点都仍然能被 Jersey 路由到（418=路由成功，404=路由丢失）")
    void everyAdminEndpointStillResolves() throws Exception {
        ResourceConfig scanned = new ResourceConfig().packages("com.mtxgdn.rest");
        ResourceConfig config = new ResourceConfig();
        List<Class<?>> resources = new ArrayList<>();
        for (Class<?> cls : scanned.getClasses()) {
            if (cls.getAnnotation(Path.class) != null) {
                config.register(cls);
                resources.add(cls);
            }
        }
        config.register(AbortFilter.class);
        assertTrue(resources.size() > 1,
                "只扫到一个资源类，说明包扫描失效或拆分被回退了: " + resources);

        int port = freePort();
        HttpServer server;
        try {
            server = GrizzlyHttpServerFactory.createHttpServer(
                    URI.create("http://127.0.0.1:" + port + "/api/"), config);
        } catch (RuntimeException e) {
            // 受限环境（禁止监听本地端口）下不误报：跳过而不是失败
            Assumptions.assumeTrue(false, "无法在本地监听端口，跳过路由冒烟检查: " + e.getMessage());
            return;
        }
        String base = "http://127.0.0.1:" + port + "/api";
        HttpClient client = HttpClient.newHttpClient();
        List<String> unmatched = new ArrayList<>();
        try {
            for (String path : GET_PATHS) {
                int status = call(client, "GET", base + path);
                if (status != 418) unmatched.add("GET " + path + " -> " + status);
            }
            for (String[] pair : OTHER_PATHS) {
                int status = call(client, pair[0], base + pair[1]);
                if (status != 418) unmatched.add(pair[0] + " " + pair[1] + " -> " + status);
            }
            assertTrue(unmatched.isEmpty(),
                    "以下管理台端点没有路由到资源方法（418 才算路由成功）:\n"
                            + String.join("\n", unmatched));

            // 负向对照：不存在的路由必须仍然是 404，否则上面的断言没有意义
            assertEquals(404, call(client, "GET", base + "/admin/definitely-not-a-route"),
                    "不存在的管理台路由应返回 404");
            assertEquals(404, call(client, "GET", base + "/admin/player"),
                    "拼错的前缀应返回 404");
        } finally {
            server.shutdownNow();
        }
    }

    /** 让操作系统分配一个空闲端口，避免固定端口在 CI 上撞车。 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int call(HttpClient client, String method, String url) throws Exception {
        HttpRequest.BodyPublisher body = "POST".equals(method) || "PUT".equals(method)
                ? HttpRequest.BodyPublishers.ofString("{}")
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method(method, body)
                .header("Content-Type", "application/json")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
