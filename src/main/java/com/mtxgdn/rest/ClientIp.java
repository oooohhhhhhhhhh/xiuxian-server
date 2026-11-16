package com.mtxgdn.rest;

import com.mtxgdn.util.AppConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.glassfish.grizzly.http.server.Request;

/**
 * 取"请求来自哪里"，只用于限流分桶。
 *
 * <p>这段逻辑原本埋在 {@code Auth} 里（登录与邮箱验证码的限流都用它），
 * 现在 QQ 网页绑定也要按来源限流。复制一份是危险的 —— 两个安全修复
 * （V2 不无条件信任 {@code X-Forwarded-For}、V8 不从 Jersey 属性表取地址）
 * 只会在其中一份里落地，另一份继续带着洞。所以抽出来，谁要限流谁调这里。</p>
 *
 * <h3>两条历史修复，一条都不能退回去</h3>
 * <ol>
 *   <li><b>不无条件信任 XFF</b>：仅在 {@code security.trust_proxy_headers=true}
 *       （部署在可信反代之后）时才采信，且取<b>最后一段</b> —— 首段由客户端完全可控，
 *       拿它当 key 等于让攻击者随便换桶，把按 IP 的限流清零；</li>
 *   <li><b>地址从 Grizzly 的 {@code Request} 取</b>：Jersey 的
 *       {@code ContainerRequestContext} 属性表里并没有它（只有 TracingLogger），
 *       早先的实现因此永远回退成 {@code "global"}，全服共用一个桶。</li>
 * </ol>
 *
 * <p>实在拿不到地址时回退 {@code "global"}：宁可粗粒度限流（所有人共享额度），
 * 也不放行伪造来源。</p>
 */
final class ClientIp {

    private ClientIp() {
    }

    /**
     * 限流用的来源标识。
     *
     * @param requestContext 由资源注入的请求上下文（取 XFF 头用）
     * @param grizzlyReq     Grizzly 请求（取 TCP 对端地址用），可为 null
     */
    static String bucket(ContainerRequestContext requestContext, Request grizzlyReq) {
        if (AppConfig.getBoolean("security.trust_proxy_headers", false)) {
            String fwd = requestContext.getHeaderString("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                String[] hops = fwd.split(",");
                // 最后一跳由可信反代写入，客户端无法伪造
                return hops[hops.length - 1].trim();
            }
        }
        return remoteAddress(requestContext, grizzlyReq);
    }

    /** TCP 直连地址；容器不支持时回退为全局桶。 */
    static String remoteAddress(ContainerRequestContext requestContext, Request grizzlyReq) {
        String remote = null;
        if (grizzlyReq != null) {
            try {
                remote = grizzlyReq.getRemoteAddr();
            } catch (Throwable ignored) {
                // 落到下面的属性表兜底
            }
        }
        if (remote == null) {
            try {
                Object addr = requestContext.getProperty("org.glassfish.grizzly.http.server.Request");
                if (addr instanceof Request req) {
                    remote = req.getRemoteAddr();
                }
            } catch (Throwable ignored) {
                // 非 Grizzly 容器或属性不可用时走回退
            }
        }
        return (remote != null && !remote.isBlank()) ? remote : "global";
    }
}
