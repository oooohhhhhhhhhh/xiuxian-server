package com.mtxgdn.i18n;

/**
 * 服务端支持的语言。
 *
 * <p>语言代码采用 Java {@link Locale} 约定的 {@code language[_COUNTRY]} 小写下划线形式，
 * 与既有 {@code data/mtxgdn/lang/zh_cn.json} 的命名保持一致。</p>
 *
 * <p>新增一门语言只需要：在枚举里加一项、在 {@code data/mtxgdn/lang/} 放同名 JSON。
 * 不需要改任何解析逻辑。</p>
 */
public enum GameLanguage {

    /** 简体中文。既是默认语言，也是其它语言的回退源。 */
    ZH_CN("zh_cn", "简体中文"),

    /** 英语（美国）。 */
    EN_US("en_us", "English (US)");

    private final String code;
    private final String displayName;

    GameLanguage(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /** 语言代码，如 {@code zh_cn}。用于文件名、请求参数、Accept-Language。 */
    public String code() {
        return code;
    }

    /** 该语言的本地自称，如 {@code 简体中文} / {@code English (US)}。用于语言选择器。 */
    public String displayName() {
        return displayName;
    }

    /** 默认语言：协商全部失败时的落点。 */
    public static GameLanguage defaultLanguage() {
        return ZH_CN;
    }

    /**
     * 回退源：某语言缺 key 时用哪种语言顶上。
     *
     * <p>刻意只回退一层到 {@link #ZH_CN}，不再级联——当前只有两门语言，
     * 级联只会让"某个 key 到底哪层补上"变得难查。</p>
     */
    public static GameLanguage fallback() {
        return ZH_CN;
    }

    /**
     * 按语言代码解析，大小写与连字符不敏感。
     *
     * <p>接受 {@code zh_cn}、{@code zh-cn}、{@code ZH_CN}、{@code zh}（取语言部分）。
     * 无法识别时返回 {@code null}（由调用方决定是报错还是静默落到下一级）。</p>
     */
    public static GameLanguage parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        if (normalized.isEmpty()) {
            return null;
        }
        for (GameLanguage lang : values()) {
            if (lang.code.equals(normalized) || normalized.equals(lang.code.split("_")[0])) {
                return lang;
            }
        }
        return null;
    }

    /** 该语言枚举的 {@link Locale}，供 {@code DateTimeFormatter} 等需要 Locale 的 API 使用。 */
    public java.util.Locale toLocale() {
        return java.util.Locale.forLanguageTag(code.replace('_', '-'));
    }

    @Override
    public String toString() {
        return code;
    }
}
