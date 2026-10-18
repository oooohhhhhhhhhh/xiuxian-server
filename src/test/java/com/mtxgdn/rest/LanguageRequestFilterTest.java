package com.mtxgdn.rest;

import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18nContext;
import com.mtxgdn.i18n.I18nManager;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语言协商链路的优先级测试。
 *
 * <p>这一层的价值在于"顺序"和"失败不报错"两条：{@code ?lang=} 必须压过
 * {@code X-Lang}，{@code X-Lang} 必须压过 {@code Accept-Language}；
 * 而任何一级给出无法识别的代码时，都必须<b>静默</b>落到下一级，
 * 绝不能让语言协商失败把业务请求带崩。</p>
 *
 * <p>{@link LanguageRequestFilter#negotiate} 是包级私有静态方法，
 * 所以本测试放在 {@code com.mtxgdn.rest} 包里，直接驱动协商逻辑，
 * 不需要起 Jersey 容器。</p>
 */
class LanguageRequestFilterTest {

    @BeforeEach
    void setUp() {
        I18nManager.load();
        I18nContext.clear();
    }

    @AfterEach
    void tearDown() {
        I18nContext.clear();
    }

    // ==================== 优先级 ====================

    @Test
    @DisplayName("?lang= 优先级最高，压过 X-Lang 与 Accept-Language")
    void queryWinsOverEverything() {
        ContainerRequestContext ctx = ctx("zh_cn", "en_us", "en-US,en;q=0.9");
        assertEquals(GameLanguage.ZH_CN, LanguageRequestFilter.negotiate(ctx));
    }

    @Test
    @DisplayName("X-Lang 压过 Accept-Language")
    void headerWinsOverAcceptLanguage() {
        ContainerRequestContext ctx = ctx(null, "en_us", "zh-CN,zh;q=0.9");
        assertEquals(GameLanguage.EN_US, LanguageRequestFilter.negotiate(ctx));
    }

    @Test
    @DisplayName("Accept-Language 取第一个能解析的项，跳过不认识的")
    void acceptLanguageTakesFirstParsable() {
        ContainerRequestContext ctx = ctx(null, null, "ja-JP,en-US;q=0.9,zh-CN;q=0.8");
        assertEquals(GameLanguage.EN_US, LanguageRequestFilter.negotiate(ctx),
                "ja-JP 不认识，应跳到 en-US 而不是直接回落默认");
    }

    @Test
    @DisplayName("全部缺失时回落服务端默认语言")
    void fallsBackToServerDefault() {
        ContainerRequestContext ctx = ctx(null, null, null);
        assertEquals(I18nManager.serverDefault(), LanguageRequestFilter.negotiate(ctx));
    }

    // ==================== 失败不报错 ====================

    @Test
    @DisplayName("无法识别的 ?lang= 静默落到下一级，不抛异常")
    void unknownQueryFallsThrough() {
        ContainerRequestContext ctx = ctx("klingon", "en_us", null);
        assertEquals(GameLanguage.EN_US, LanguageRequestFilter.negotiate(ctx),
                "?lang=klingon 不认识，应继续看 X-Lang");
    }

    @Test
    @DisplayName("无法识别的 X-Lang 静默落到 Accept-Language")
    void unknownHeaderFallsThrough() {
        ContainerRequestContext ctx = ctx(null, "klingon", "en-US");
        assertEquals(GameLanguage.EN_US, LanguageRequestFilter.negotiate(ctx));
    }

    @Test
    @DisplayName("Accept-Language 全是垃圾时回落默认，不抛异常")
    void garbageAcceptLanguageFallsBack() {
        ContainerRequestContext ctx = ctx(null, null, ";;;,,,q=,,");
        assertEquals(I18nManager.serverDefault(), LanguageRequestFilter.negotiate(ctx));
    }

    // ==================== 大小写与分隔符 ====================

    @Test
    @DisplayName("协商对大小写与连字符不敏感")
    void negotiationIsCaseInsensitive() {
        assertEquals(GameLanguage.ZH_CN,
                LanguageRequestFilter.negotiate(ctx("ZH-CN", null, null)));
        assertEquals(GameLanguage.EN_US,
                LanguageRequestFilter.negotiate(ctx(null, "EN-us", null)));
    }

    @Test
    @DisplayName("filter() 把协商结果写进 I18nContext，供业务层 I18n.t 直接使用")
    void filterBindsContext() {
        new LanguageRequestFilter().filter(ctx(null, "en_us", null));
        assertEquals(GameLanguage.EN_US, I18nContext.current());
    }

    // ==================== 工具 ====================

    /** 造一个只实现协商所需方法的 ContainerRequestContext。 */
    private static ContainerRequestContext ctx(String langQuery, String xLang, String acceptLanguage) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);

        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        if (langQuery != null) {
            query.add("lang", langQuery);
        }
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(ctx.getUriInfo()).thenReturn(uriInfo);

        when(ctx.getHeaderString("X-Lang")).thenReturn(xLang);
        when(ctx.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn(acceptLanguage);
        return ctx;
    }
}
