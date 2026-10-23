package com.mtxgdn.rest;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import com.mtxgdn.restfixture.GameCatchAllStub;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家侧端点<b>路由可达性</b>回归测试。
 *
 * <p><b>为什么需要它</b>：玩家接口原来只有一个近 3000 行的 {@code GameResource}，
 * 现在按"路径前缀 + 依赖集合"拆成了 {@code GamePlayerResource}、
 * {@code GameInventoryResource}、{@code GameSectResource} 等十几个资源类，
 * 每个类都带着同一个类级 {@code @Path("/game")}。类级路径相同、方法级子路径各自不同，
 * 拼出来的地址是否正确<b>只在运行时</b>由 Jersey 的资源模型决定 —— 反射读注解能证明
 * "路径字符串没变"，却证明不了"Jersey 真的把这条路径路由到了那个方法"。JAX-RS 的
 * 路径拼接、模板优先级、同根路径多资源类的合并，注解层面看不出来。</p>
 *
 * <p><b>做法</b>：起一个真实的 Grizzly + Jersey 服务，只注册类级 {@code @Path} 以
 * {@code /game} 开头的<b>显式端点</b>资源类（不含 {@code UnifiedRestResource} 这个
 * 按 Command 路由动态分发的兜底资源，否则它的 {@code {path:.*}} 会把负向对照路径也吃掉），
 * 再注册一个"匹配到资源方法就以 418 中止"的过滤器 —— 于是：</p>
 * <ul>
 *   <li>路由成功 → 418（资源方法体一行都不会执行，测试不会碰数据库）；</li>
 *   <li>路由失败 → 404。</li>
 * </ul>
 * <p>最后用两条不存在的路径做负向对照，确保"418 才是成功"这个判据本身有效 ——
 * 否则一个把所有请求都吞掉的过滤器会让本测试变成永远通过的空壳。</p>
 *
 * <p>另有一条断言把"测试清单"和"资源类里真实存在的端点"对齐：反射扫出来的每个端点
 * 都必须在下面两张表里出现，否则新增端点时忘了登记就会漏测（这条断言只比较集合，
 * 不比较顺序，路径模板统一用 {@code 1} 代入）。</p>
 */
class GameRouteResolutionTest {

    /** 匹配到资源方法的请求一律以 418 中止，避免真的执行端点逻辑。 */
    public static class AbortFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.abortWith(Response.status(418).build());
        }
    }

    /**
     * 只在"匹配到的资源类不是兜底类"时以 418 中止，否则以兜底类的状态码标明"兜底赢了"。
     *
     * <p>这样同一个过滤器既能证明路由可达，又能证明<b>显式路径优先于兜底模板</b> ——
     * 后者是本测试唯一能区分"请求真的进了 Game*Resource"与"被 UnifiedRestResource 的
     * {@code {path:.*}} 吞掉"的手段。</p>
     */
    public static class ExplicitRouteProbe implements ContainerRequestFilter {
        @Context
        private ResourceInfo resourceInfo;

        @Override
        public void filter(ContainerRequestContext requestContext) {
            Class<?> matched = resourceInfo != null ? resourceInfo.getResourceClass() : null;
            boolean explicit = matched != null && matched != GameCatchAllStub.class;
            requestContext.abortWith(Response.status(
                    explicit ? 418 : GameCatchAllStub.CATCH_ALL_STATUS).build());
        }
    }

    /** 路径模板统一代入这个值，便于把"请求清单"与"反射扫出的端点"逐条对齐。 */
    private static final String TEMPLATE_VALUE = "1";

    private static final String[] GET_PATHS = {
            "/game/buff", "/game/chat/private/1", "/game/chat/world",
            "/game/crafting/recipes", "/game/daily", "/game/equipment",
            "/game/farm/plots", "/game/friend/list", "/game/friend/pending",
            "/game/i18n/bundle", "/game/i18n/lang", "/game/i18n/languages",
            "/game/inventory", "/game/item/registry", "/game/map",
            "/game/map/locations", "/game/market", "/game/market/my_listings",
            "/game/market/price", "/game/player", "/game/players",
            "/game/players/search", "/game/raid/realms", "/game/rank",
            "/game/realm/breakthrough/preview", "/game/realm/config", "/game/season",
            "/game/secret_realm/areas", "/game/sect/applications", "/game/sect/members",
            "/game/sect/top", "/game/sect/warehouse", "/game/skill/my",
            "/game/skills", "/game/spiritual_roots", "/game/status",
            "/game/team/info", "/game/technique/my", "/game/techniques",
            "/game/title/active", "/game/title/all", "/game/title/my"
    };

    private static final String[][] OTHER_PATHS = {
            {"POST", "/game/chat/private"}, {"POST", "/game/chat/world"},
            {"POST", "/game/crafting/craft"}, {"POST", "/game/cultivate/start"},
            {"POST", "/game/cultivate/stop"}, {"POST", "/game/daily/morning_cultivation"},
            {"POST", "/game/economy/currency-exchange"}, {"POST", "/game/equipment/enhance"},
            {"POST", "/game/equipment/equip"}, {"POST", "/game/equipment/unequip"},
            {"POST", "/game/exploration"}, {"POST", "/game/farm/clear"},
            {"POST", "/game/farm/expand"}, {"POST", "/game/farm/fertilize"},
            {"POST", "/game/farm/fertilize-all"}, {"POST", "/game/farm/harvest"},
            {"POST", "/game/farm/harvest-all"}, {"POST", "/game/farm/pesticide"},
            {"POST", "/game/farm/plant"}, {"POST", "/game/farm/water"},
            {"POST", "/game/farm/water-all"}, {"POST", "/game/friend/accept"},
            {"POST", "/game/friend/add"}, {"POST", "/game/friend/reject"},
            {"POST", "/game/friend/remove"}, {"POST", "/game/heal"},
            {"POST", "/game/i18n/lang"}, {"POST", "/game/item/add"},
            {"POST", "/game/item/use"}, {"POST", "/game/map/travel/1"},
            {"POST", "/game/market/buy"}, {"POST", "/game/market/cancel"},
            {"POST", "/game/market/list"}, {"POST", "/game/player/create"},
            {"POST", "/game/player/data/export"}, {"POST", "/game/player/data/restore"},
            {"POST", "/game/pvp/challenge"}, {"POST", "/game/raid/enter"},
            {"POST", "/game/realm/breakthrough"}, {"POST", "/game/realm/consolidate"},
            {"POST", "/game/secret_realm/enter"}, {"POST", "/game/sect/appoint"},
            {"POST", "/game/sect/approve/1"}, {"POST", "/game/sect/create"},
            {"POST", "/game/sect/disband"}, {"POST", "/game/sect/donate"},
            {"POST", "/game/sect/join/1"}, {"POST", "/game/sect/kick/1"},
            {"POST", "/game/sect/leave"}, {"POST", "/game/sect/levelup"},
            {"POST", "/game/sect/reject/1"}, {"POST", "/game/sect/take"},
            {"POST", "/game/sect/transfer/1"}, {"POST", "/game/sect/war/1"},
            {"POST", "/game/skill/learn"}, {"POST", "/game/team/accept"},
            {"POST", "/game/team/create"}, {"POST", "/game/team/invite"},
            {"POST", "/game/team/leave"}, {"POST", "/game/technique/equip"},
            {"POST", "/game/technique/learn"}, {"POST", "/game/technique/unequip"},
            {"POST", "/game/technique/upgrade"}, {"POST", "/game/title/equip"},
            {"POST", "/game/title/unequip"}
    };

    @Test
    @DisplayName("拆分后每个玩家端点都仍然能被 Jersey 路由到（418=路由成功，404=路由丢失）")
    void everyGameEndpointStillResolves() throws Exception {
        List<Class<?>> resources = gameResourceClasses();
        assertTrue(resources.size() > 1,
                "只扫到一个 /game 资源类，说明包扫描失效或拆分被回退了: " + resources);

        ResourceConfig config = new ResourceConfig();
        for (Class<?> cls : resources) {
            config.register(cls);
        }
        config.register(AbortFilter.class);

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
                    "以下玩家端点没有路由到资源方法（418 才算路由成功）:\n"
                            + String.join("\n", unmatched));

            // 负向对照：不存在的路由必须仍然是 404，否则上面的断言没有意义
            assertEquals(404, call(client, "GET", base + "/game/definitely-not-a-route"),
                    "不存在的玩家路由应返回 404");
            assertEquals(404, call(client, "GET", base + "/game/sect"),
                    "少一段子路径的前缀应返回 404");
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("显式端点必须优先于 /game 下的兜底分发器（否则请求会被 Command 路由吞掉）")
    void explicitPathsWinOverCommandCatchAll() throws Exception {
        List<Class<?>> resources = gameResourceClasses();
        assertTrue(resources.size() > 1,
                "只扫到一个 /game 资源类，说明包扫描失效或拆分被回退了: " + resources);

        ResourceConfig config = new ResourceConfig();
        for (Class<?> cls : resources) {
            config.register(cls);
        }
        config.register(GameCatchAllStub.class);
        config.register(ExplicitRouteProbe.class);

        int port = freePort();
        HttpServer server;
        try {
            server = GrizzlyHttpServerFactory.createHttpServer(
                    URI.create("http://127.0.0.1:" + port + "/api/"), config);
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "无法在本地监听端口，跳过路由优先级检查: " + e.getMessage());
            return;
        }
        String base = "http://127.0.0.1:" + port + "/api";
        HttpClient client = HttpClient.newHttpClient();
        List<String> swallowed = new ArrayList<>();
        try {
            for (String path : GET_PATHS) {
                int status = call(client, "GET", base + path);
                if (status != 418) swallowed.add("GET " + path + " -> " + status);
            }
            for (String[] pair : OTHER_PATHS) {
                int status = call(client, pair[0], base + pair[1]);
                if (status != 418) swallowed.add(pair[0] + " " + pair[1] + " -> " + status);
            }
            assertTrue(swallowed.isEmpty(),
                    "以下端点被 /game 的兜底模板抢走了（418=显式资源类命中，599=兜底命中）:\n"
                            + String.join("\n", swallowed));

            // 正向对照：没有任何显式端点登记的路径应当落到兜底模板（599），
            // 说明本测试的判据能区分"显式命中"与"兜底命中"，不是恒 418 的空壳。
            assertEquals(GameCatchAllStub.CATCH_ALL_STATUS, call(client, "GET", base + "/game/definitely-not-a-route"),
                    "未登记的路径应落到兜底模板（599）");
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    @DisplayName("路由冒烟清单必须覆盖 /game 资源类里真实存在的每个端点")
    void requestInventoryCoversEveryDeclaredEndpoint() {
        Set<String> declared = new TreeSet<>();
        for (Class<?> cls : gameResourceClasses()) {
            String classPath = cls.getAnnotation(Path.class).value();
            for (Method method : cls.getDeclaredMethods()) {
                String verb = httpVerbOf(method);
                if (verb == null) {
                    continue;
                }
                Path sub = method.getAnnotation(Path.class);
                String subPath = sub != null ? sub.value() : "";
                declared.add(verb + " " + concrete(classPath + subPath));
            }
        }
        assertFalse(declared.isEmpty(),
                "没能从 /game 资源类里扫出任何端点 —— 包扫描失效会让本测试变成零端点全通过");

        Set<String> tested = new LinkedHashSet<>();
        for (String path : GET_PATHS) {
            tested.add("GET " + concrete(path));
        }
        for (String[] pair : OTHER_PATHS) {
            tested.add(pair[0] + " " + concrete(pair[1]));
        }

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(tested);
        Set<String> stale = new TreeSet<>(tested);
        stale.removeAll(declared);

        assertTrue(missing.isEmpty(),
                "以下端点存在于资源类里，却没有登记进本测试的请求清单（会漏测路由）:\n"
                        + String.join("\n", missing));
        assertTrue(stale.isEmpty(),
                "以下请求登记在测试清单里，但资源类里已经没有对应端点（清单过期或端点被删）:\n"
                        + String.join("\n", stale));
    }

    /** 把路径模板（{@code {id}}）替换成固定值，便于集合比较。 */
    private static String concrete(String path) {
        return path.replaceAll("\\{[^}]+\\}", TEMPLATE_VALUE);
    }

    /**
     * 收集 {@code com.mtxgdn.rest} 包里所有类级 {@code @Path} 以 {@code /game} 开头的资源类。
     *
     * <p>用 {@code ResourceConfig} 的包扫描（与 {@code Main} 注册资源的方式一致），
     * 这样以后新增的玩家资源类会被自动纳入，不需要再改本测试。</p>
     *
     * <p>{@code UnifiedRestResource} 被显式排除：它是 Command 路由的动态分发器，
     * 用 {@code @Path("{path:.*}")} 兜底吃掉整个 {@code /game} 路径空间，权限码在
     * {@code dispatch()} 内部按 {@code RouteDefinition} 校验。把它注册进来会让
     * 负向对照路径也命中（返回 204 而不是 404），本测试的判据就失效了。</p>
     *
     * <p>同时只统计<b>顶层类</b>：{@code ResourceConfig.packages(...)} 扫的是 classpath，
     * 同包里的嵌套类（例如测试自己写的夹具）也会被扫到，那会把"测试替身"误当成
     * "被测端点"。（{@link GameCatchAllStub} 因此放在 {@code com.mtxgdn.restfixture}
     * 包里，避免被这个扫描捡到。）</p>
     */
    private static List<Class<?>> gameResourceClasses() {
        List<Class<?>> result = new ArrayList<>();
        ResourceConfig scanned = new ResourceConfig().packages("com.mtxgdn.rest");
        for (Class<?> cls : scanned.getClasses()) {
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

    private static String httpVerbOf(Method method) {
        for (Class<? extends Annotation> annotation : List.of(GET.class, POST.class,
                PUT.class, DELETE.class, PATCH.class)) {
            if (method.isAnnotationPresent(annotation)) {
                return annotation.getSimpleName();
            }
        }
        return null;
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
