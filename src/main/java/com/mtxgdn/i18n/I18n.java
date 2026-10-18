package com.mtxgdn.i18n;

/**
 * 面向业务代码的唯一入口：取当前请求语言下的文案。
 *
 * <pre>
 * String msg = I18n.t("sect.leave.success");
 * String msg = I18n.t("trade.buy.paid", amount, itemName);   // {0} {1}
 * </pre>
 *
 * <p>本类不持有任何状态，也不管语言从哪来——那是 {@link I18nContext} 与
 * {@code LanguageRequestFilter} 的职责。业务代码只需要知道"取当前语言的文案"。</p>
 *
 * <p>占位符用 {@code {0}} {@code {1}} 位置参数，刻意不用 {@link java.text.MessageFormat}
 * —— 后者把单引号当特殊字符，中文文案里常见的撇口（don't / it's）会被吃掉。</p>
 */
public final class I18n {

    private I18n() {
    }

    /** 取当前语言下的文案。 */
    public static String t(String key) {
        return I18nManager.raw(key, I18nContext.current());
    }

    /** 取当前语言下的文案，并按位置填充 {@code {0}}... 占位符。 */
    public static String t(String key, Object... args) {
        return I18nManager.format(key, I18nContext.current(), args);
    }

    /** 取指定语言下的文案（用于"按发送者语言"这类需要显式指定语言的场景）。 */
    public static String t(String key, GameLanguage lang) {
        return I18nManager.raw(key, lang);
    }

    /**
     * 取当前语言下的文案；取不到时返回 {@code fallback}。
     *
     * <p>与 {@link #t(String)} 的区别：缺 key 时{@code t} 会把 key 本身返回（便于暴露漏翻译），
     * 而本方法返回一个人畜无害的兜底文案。用于"这个文案缺失不该把 key 露出去"的场景。</p>
     */
    public static String tOr(String key, String fallback) {
        GameLanguage lang = I18nContext.current();
        String value = I18nManager.raw(key, lang);
        return key.equals(value) ? fallback : value;
    }

    /** 当前语言。 */
    public static GameLanguage current() {
        return I18nContext.current();
    }
}
