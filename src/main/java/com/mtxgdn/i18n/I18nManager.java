package com.mtxgdn.i18n;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多语言资源包管理：加载、回退、外部覆写、运行时合并。
 *
 * <p>加载优先级（从高到低，后加载的覆盖先加载的）：</p>
 * <ol>
 *   <li>插件 jar 内 {@code lang/<lang>.json} —— 运行时通过 {@link #mergeFromStream} 注入</li>
 *   <li>外部 {@code config/lang/<lang>.json} —— 运维可改，{@link #reload()} 或重启后生效</li>
 *   <li>classpath {@code data/mtxgdn/lang/<lang>.json} —— jar 内置基线</li>
 * </ol>
 *
 * <p>回退链：目标语言 → {@link GameLanguage#fallback()} → 原样返回 key 并记 WARN。
 * 即"发一半翻译"也是可用状态，不会把 {@code game.xxx} 这类 key 露给玩家。</p>
 *
 * <p>本类只提供<b>静态取值</b>能力，"当前请求用什么语言"由 {@link I18nContext} 负责，
 * 以避免旧实现里 {@code static currentLang} 那种并发下全局串味的问题。</p>
 */
public final class I18nManager {

    private static final GameLogger log = GameLogger.getLogger(I18nManager.class);

    private static final String RESOURCE_DIR = "data/mtxgdn/lang/";
    private static final String EXTERNAL_DIR = "config/lang/";

    /** 语言代码 → 词条表。volatile 保证 reload 后其它线程立刻看到新表。 */
    private static final Map<String, Map<String, String>> bundles = new ConcurrentHashMap<>();

    /** 已报告过的缺失 key，避免同一次运行里把同一条 WARN 打到爆。 */
    private static final Set<String> reportedMissing =
            Collections.synchronizedSet(new java.util.HashSet<>());

    private static volatile boolean loaded = false;

    private I18nManager() {
    }

    /**
     * 启动时加载全部语言包。幂等，重复调用无副作用。
     *
     * <p>由 {@code Main} 在插件系统初始化<b>之前</b>调用——插件加载会合并自己的语言包，
     * 顺序反了会让内置基线覆盖插件词条。</p>
     */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        for (GameLanguage lang : GameLanguage.values()) {
            Map<String, String> bundle = new ConcurrentHashMap<>();
            // classpath 基线 → 外部覆写（后者 putAll 覆盖前者）
            bundle.putAll(readClasspath(lang));
            bundle.putAll(readExternal(lang));

            if (bundle.isEmpty()) {
                log.warn("[i18n] 语言包为空: " + lang.code()
                        + "（既无 classpath 内置也无 " + EXTERNAL_DIR + lang.code() + ".json）");
            } else {
                int classpathCount = readClasspath(lang).size();
                log.info("[i18n] 已加载语言包 " + lang.code() + ": " + bundle.size()
                        + " 条词条（内置 " + classpathCount + " 条）");
            }
            bundles.put(lang.code(), Collections.unmodifiableMap(bundle));
        }

        I18nFallbackCounts.reset();
    }

    /** 重新加载全部语言包。运行中改完 {@code config/lang/*.json} 后调用。 */
    public static synchronized void reload() {
        loaded = false;
        bundles.clear();
        reportedMissing.clear();
        I18nFallbackCounts.reset();
        load();
    }

    /**
     * 把一段 JSON 词条合并进指定语言（插件用）。
     *
     * <p>只增不删：插件提供的 key 覆盖同名内置 key，但插件无法移除内置 key。
     * 反之，插件停用时它的词条仍留在内存里——这是既有 {@code LangManager} 的语义，
     * 保留以不破坏现有插件。</p>
     */
    public static void mergeFromStream(GameLanguage lang, InputStream jsonStream) {
        if (lang == null || jsonStream == null) {
            return;
        }
        try (Reader reader = new InputStreamReader(jsonStream, StandardCharsets.UTF_8)) {
            Map<String, String> incoming = I18nJson.read(reader);
            if (incoming == null || incoming.isEmpty()) {
                return;
            }
            // 注意：bundle(lang) 返回的是 load() 里包了 unmodifiableMap 的只读视图，
            // 直接 putAll 会抛 UnsupportedOperationException，被下面的 catch 吞掉，
            // 表现为"插件词条一条都没进去，只有一条告警"。所以必须先拷成可变副本，
            // 改完再整体 put 回 bundles —— 这样已发布的只读视图也不会被就地修改。
            Map<String, String> merged = new LinkedHashMap<>(bundle(lang));
            merged.putAll(incoming);
            bundles.put(lang.code(), Collections.unmodifiableMap(merged));
        } catch (Exception e) {
            log.warn("[i18n] 合并语言包失败: lang=" + lang.code() + " - " + e);
        }
    }

    /**
     * 取原文（无占位符）。
     *
     * <p>找得到 key 返回译文；找不到按 §加载优先级 的回退链兜底，最终仍缺则返回 key 本身
     * 并记一条 WARN（由 {@link #reportedMissing} 去重，不会刷屏）。</p>
     */
    public static String raw(String key) {
        return raw(key, GameLanguage.defaultLanguage());
    }

    /** 取指定语言下的原文（无占位符）。 */
    public static String raw(String key, GameLanguage lang) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        GameLanguage target = lang != null ? lang : GameLanguage.defaultLanguage();

        String value = lookup(target, key);
        if (value != null) {
            return value;
        }

        // 第一层回退：回退源语言
        GameLanguage fb = GameLanguage.fallback();
        if (fb != target) {
            I18nFallbackCounts.hit();
            I18nFallbackCounts.forLanguageIncrement(target.code());
            value = lookup(fb, key);
            if (value != null) {
                return value;
            }
        }

        reportMissing(key, target);
        return key;
    }

    /**
     * 取译文并按位置填充 {@code {0}} {@code {1}} ... 占位符。
     *
     * <p>刻意不用 {@link java.text.MessageFormat}——它的单引号是特殊字符，
     * 而大量文案里含英文撇口（如 {@code don't}），会让 {@code don't} 被吃掉。
     * 这里只做朴素的 {@code {n}} 替换。</p>
     */
    public static String format(String key, GameLanguage lang, Object... args) {
        String template = raw(key, lang);
        if (args == null || args.length == 0) {
            return template;
        }
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    /** 该语言已加载的词条表（只读视图）。 */
    public static Map<String, String> bundle(GameLanguage lang) {
        Map<String, String> bundle = bundles.get(lang.code());
        return bundle != null ? bundle : Collections.emptyMap();
    }

    /** 某语言是否已加载（词条数 > 0）。 */
    public static boolean isLoaded(GameLanguage lang) {
        return bundle(lang).isEmpty() == false;
    }

    /**
     * 找出某语言相对基线缺失的 key。
     *
     * <p>以 {@link GameLanguage#fallback()} 的词条全集为基准——它是唯一保证完整的语言。</p>
     */
    public static List<String> missingKeys(GameLanguage lang) {
        Map<String, String> base = bundle(GameLanguage.fallback());
        Map<String, String> target = bundle(lang);
        List<String> missing = new ArrayList<>();
        for (String key : base.keySet()) {
            if (!target.containsKey(key)) {
                missing.add(key);
            }
        }
        Collections.sort(missing);
        return missing;
    }

    /** 每种语言的词条数与缺失数，供 {@code GET /admin/i18n/status} 使用。 */
    public static Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (GameLanguage lang : GameLanguage.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", lang.code());
            entry.put("displayName", lang.displayName());
            entry.put("keyCount", bundle(lang).size());
            entry.put("missingCount", missingKeys(lang).size());
            entry.put("falledBackCount", I18nFallbackCounts.forLanguage(lang.code()));
            out.put(lang.code(), entry);
        }
        return out;
    }

    /**
     * 导出某语言当前生效的词条表（含插件合并结果与外部覆写），JSON 文本。
     *
     * <p>用于让运维把"改好的语言包"从服务端取回，落到 {@code config/lang/} 形成持久配置。</p>
     */
    public static String exportJson(GameLanguage lang) {
        return I18nJson.write(bundle(lang));
    }

    /** 当前服务端默认语言（来自 {@code i18n.default-language}，缺省 {@code zh_cn}）。 */
    public static GameLanguage serverDefault() {
        String configured = AppConfig.get("i18n.default-language", GameLanguage.defaultLanguage().code());
        GameLanguage parsed = GameLanguage.parse(configured);
        if (parsed == null) {
            log.warn("[i18n] i18n.default-language 配置了未知语言 '" + configured
                    + "'，回落到 " + GameLanguage.defaultLanguage().code());
            return GameLanguage.defaultLanguage();
        }
        return parsed;
    }

    // ==================== 内部 ====================

    private static String lookup(GameLanguage lang, String key) {
        Map<String, String> bundle = bundles.get(lang.code());
        return bundle != null ? bundle.get(key) : null;
    }

    private static Map<String, String> readClasspath(GameLanguage lang) {
        String path = RESOURCE_DIR + lang.code() + ".json";
        try (InputStream is = I18nManager.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                return Collections.emptyMap();
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Map<String, String> map = I18nJson.read(reader);
                return map != null ? map : Collections.emptyMap();
            }
        } catch (Exception e) {
            log.warn("[i18n] 读取内置语言包失败: " + path + " - " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> readExternal(GameLanguage lang) {
        try {
            Path file = AppConfig.getJarDir().resolve(EXTERNAL_DIR).resolve(lang.code() + ".json");
            if (!Files.exists(file)) {
                return Collections.emptyMap();
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, String> map = I18nJson.read(reader);
                if (map != null && !map.isEmpty()) {
                    log.info("[i18n] 已应用外部语言覆写: " + file.toAbsolutePath()
                            + "（" + map.size() + " 条）");
                }
                return map != null ? map : Collections.emptyMap();
            }
        } catch (IOException e) {
            log.warn("[i18n] 读取外部语言覆写失败: lang=" + lang.code() + " - " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static void reportMissing(String key, GameLanguage lang) {
        I18nFallbackCounts.miss();
        String token = lang.code() + "|" + key;
        // 首次出现才打日志：避免某个 key 在循环里被反复触发时刷满日志
        if (reportedMissing.add(token)) {
            log.warn("[i18n] 缺失词条 lang=" + lang.code() + " key=" + key);
        }
    }
}
