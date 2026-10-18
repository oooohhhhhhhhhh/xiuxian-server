package com.mtxgdn.rest;

import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18nContext;
import com.mtxgdn.i18n.I18nManager;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * 语言协商过滤器：把每个 REST 请求绑定到一门语言。
 *
 * <p>协商优先级（从高到低，第一个命中的生效）：</p>
 * <ol>
 *   <li>{@code ?lang=} 查询参数 —— 最短逃生口，调试与显式切换用</li>
 *   <li>{@code X-Lang} 请求头 —— 比 Accept-Language 更适合 API，不会被浏览器伪装</li>
 *   <li>{@code Accept-Language} —— 标准浏览器通路，取第一个可解析项</li>
 *   <li>服务端默认 {@code i18n.default-language}</li>
 * </ol>
 *
 * <p><b>刻意不读玩家偏好（{@code players.lang}）</b>：那需要 userId，而本过滤器
 * 必须跑在 JwtAuthFilter <b>之前</b>（AUTHORIZATION 之后仍可能 401，401 的错误信息
 * 也要本地化）。玩家偏好在指令/REST 业务层按 userId 单独解析，见
 * {@code LanguageResolver}。</p>
 *
 * <p>未知或非法的语言代码<b>静默落到下一级</b>，不报错也不返回 400——
 * 语言协商失败不该让业务请求本身失败。</p>
 *
 * <p>优先级设为 {@code Priorities.AUTHENTICATION}：排在 JwtAuthFilter（默认
 * AUTHENTICATION）附近，保证语言先于任何可能返回错误的处理被绑定。
 * 线程由 Jersey 管理，每个请求一个线程，因此 {@link I18nContext#clear()}
 * 放在 finally 里是必须的——否则线程被池复用到下一个请求时会带着上一个请求的语言。</p>
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class LanguageRequestFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        GameLanguage resolved;
        try {
            resolved = negotiate(ctx);
        } catch (Exception e) {
            // 协商过程（如读参数）出任何异常都不该让请求失败
            resolved = I18nManager.serverDefault();
        }
        I18nContext.set(resolved);
    }

    /** 便于测试与复用的协商逻辑。 */
    static GameLanguage negotiate(ContainerRequestContext ctx) {
        // 1. ?lang=
        String fromQuery = ctx.getUriInfo().getQueryParameters().getFirst("lang");
        GameLanguage lang = GameLanguage.parse(fromQuery);
        if (lang != null) {
            return lang;
        }

        // 2. X-Lang
        lang = GameLanguage.parse(ctx.getHeaderString("X-Lang"));
        if (lang != null) {
            return lang;
        }

        // 3. Accept-Language：取第一个能解析的项
        String accept = ctx.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        if (accept != null && !accept.isBlank()) {
            for (String part : accept.split(",")) {
                // 形如 "en-US;q=0.9"，取分号前的语言标签。
                // 这里刻意不用 part.split(";")[0]：Java 的 "".split(";") 返回
                // 长度 0 的数组，遇到 ";;,," 这种畸形头就会抛 AIOOBE。
                int semi = part.indexOf(';');
                String tag = (semi >= 0 ? part.substring(0, semi) : part).trim();
                if (tag.isEmpty()) {
                    continue;
                }
                lang = GameLanguage.parse(tag);
                if (lang != null) {
                    return lang;
                }
            }
        }

        // 4. 服务端默认
        return I18nManager.serverDefault();
    }
}
