package com.mtxgdn.rest;

import com.mtxgdn.i18n.I18nContext;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * 语言上下文清理过滤器。
 *
 * <p>{@link LanguageRequestFilter} 负责<b>绑定</b>语言，但没有"请求结束"钩子。
 * Jersey 的工作线程来自池、会被复用到下一个请求，因此必须显式清理，
 * 否则下一个请求会带着上一个请求的语言——这正是旧实现
 * {@code static currentLang} 那种并发串味问题的翻版。</p>
 *
 * <p>优先级设为 {@code Priorities.HEADER_DECORATOR} 之后的最高档，
 * 保证在任何可能提前结束响应的处理之后仍能跑到。</p>
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class LanguageCleanupFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        I18nContext.clear();
    }
}
