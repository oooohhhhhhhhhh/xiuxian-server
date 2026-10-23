package com.mtxgdn.restfixture;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 测试替身：与 {@code UnifiedRestResource} 同形状的 {@code /game} 兜底分发器。
 *
 * <p><b>为什么需要它</b>：{@code UnifiedRestResource} 用 {@code @Path("{path:.*}")}
 * 把整个 {@code /game} 路径空间兜底吃掉，玩家侧的真实端点必须<b>优先于</b>它命中。
 * 这条"显式路径胜过兜底模板"的排序是 JAX-RS 运行时行为，注解层面看不出来，
 * 只能真起一个 Jersey 容器去验证。而真的注册 {@code UnifiedRestResource} 有两个麻烦：
 * 它在构造函数里读 {@code CommandRegistry} 并写一个静态 {@code routesLoaded} 标记，
 * 会给同一 JVM 里的其它测试留下副作用。</p>
 *
 * <p><b>为什么不放在 {@code com.mtxgdn.rest} 包里</b>：那个包会被
 * {@code new ResourceConfig().packages("com.mtxgdn.rest")} 整包扫描 ——
 * 本替身的 {@code GET /game/{path:.*}} 与 {@code UnifiedRestResource} 的同名路由完全重复，
 * 一旦一起被扫进同一个容器，Jersey 会因为"重复路由"直接建容器失败，
 * 把毫不相干的 {@code AdminRouteResolutionTest} 一起搞挂。因此放在扫描范围之外的包里，
 * 由需要它的测试显式 {@code register(...)}。</p>
 *
 * <p>本类只在测试中使用，不进入生产代码。</p>
 */
@Path("/game")
public class GameCatchAllStub {

    /** 兜底命中时返回的状态码：与"显式端点命中"的 418 区分开。 */
    public static final int CATCH_ALL_STATUS = 599;

    @GET
    @Path("{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleGet(@PathParam("path") String path) {
        return Response.status(CATCH_ALL_STATUS).build();
    }

    @POST
    @Path("{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handlePost(@PathParam("path") String path, String body) {
        return Response.status(CATCH_ALL_STATUS).build();
    }
}
