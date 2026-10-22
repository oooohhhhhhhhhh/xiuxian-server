package com.mtxgdn.util;

import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LangManager {

    private static final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private LangManager() {
    }

    /**
     * 设置当前<b>线程</b>的语言。
     *
     * @deprecated 语言已改为按线程绑定（{@link com.mtxgdn.i18n.I18nContext}）——
     * 进程级静态字段会让两个玩家同时在线时后设置的覆盖前一个，导致 A 收到 B 的语言。
     * 本方法保留只是为了不破坏已编译的插件，行为已改为设置<b>当前线程</b>的语言，
     * 请改用 {@link com.mtxgdn.i18n.I18nContext#runWith}。
     */
    @Deprecated
    public static void setLanguage(String lang) {
        com.mtxgdn.i18n.I18nContext.set(com.mtxgdn.i18n.GameLanguage.parse(lang));
    }

    /**
     * 当前线程的语言代码。
     *
     * @deprecated 见 {@link #setLanguage(String)}，请改用
     *             {@code I18nContext.current().code()}。
     */
    @Deprecated
    public static String getLanguage() {
        return com.mtxgdn.i18n.I18nContext.current().code();
    }

    public static String get(String key) {
        return get(key, key);
    }

    /**
     * 按<b>当前线程</b>的语言取词条，缺词时回退到中文，再缺才用默认值。
     *
     * <p><b>修复说明</b>：这里原来读的是 {@code private static String currentLang = "zh_cn"}
     * —— 一个进程级静态字段，而全仓库<b>没有任何地方调用过 {@code setLanguage}</b>。
     * 结果是物品名、秘境名、探索事件名永远只有中文：玩家把语言切成英文，
     * 这些名字依然是中文（而其它文案走 {@code I18nManager}，是英文），中英混排。
     * 现在改为读 {@link com.mtxgdn.i18n.I18nContext} 的线程级语言，
     * 与其它文案的来源一致。</p>
     */
    public static String get(String key, String defaultValue) {
        String lang = com.mtxgdn.i18n.I18nContext.current().code();
        String value = load(lang).get(key);
        if (value != null) {
            return value;
        }
        // 回退一层到中文：英文词条缺 key 时至少还能显示中文，而不是把 key 直接抛给玩家
        String fallbackLang = com.mtxgdn.i18n.GameLanguage.fallback().code();
        if (!fallbackLang.equals(lang)) {
            value = load(fallbackLang).get(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static Map<String, String> load(String lang) {
        return cache.computeIfAbsent(lang, LangManager::loadInternal);
    }

    private static Map<String, String> loadInternal(String lang) {
        String path = "/data/mtxgdn/lang/" + lang + ".json";
        try (InputStream is = LangManager.class.getResourceAsStream(path)) {
            if (is == null) {
                return Collections.emptyMap();
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Map<String, String> map = gson.fromJson(reader, MAP_TYPE);
                return map != null ? map : Collections.emptyMap();
            }
        } catch (JsonSyntaxException e) {
            GameLogger.getLogger(LangManager.class).error("语言文件语法错误: " + path, e);
            return Collections.emptyMap();
        } catch (Exception e) {
            GameLogger.getLogger(LangManager.class).error("加载语言文件失败: " + path, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 合并外部翻译文件到当前缓存（供插件等使用）。
     * 若 key 冲突，后加载的会覆盖先加载的。
     *
     * @param lang       语言代码，如 "zh_cn"
     * @param jsonStream JSON 格式翻译文件输入流（调用方负责关闭）
     */
    public static void merge(String lang, InputStream jsonStream) {
        try (Reader reader = new InputStreamReader(jsonStream, StandardCharsets.UTF_8)) {
            Map<String, String> newEntries = gson.fromJson(reader, MAP_TYPE);
            if (newEntries != null && !newEntries.isEmpty()) {
                // 注意：不能写成 cache.computeIfAbsent(lang, k -> new ConcurrentHashMap<>())，
                // 那样在"插件先合并、业务后取词条"的顺序下会凭空造出一个只含插件词条的空壳，
                // 基础语言文件再也不会被加载 —— 表现为物品名/秘境名全部退化成裸 key。
                // 这里先确保基础词条已加载，再在可变副本上合并。
                cache.compute(lang, (k, existing) -> {
                    Map<String, String> base = existing != null ? existing : loadInternal(k);
                    Map<String, String> merged = new ConcurrentHashMap<>(base);
                    merged.putAll(newEntries);
                    return merged;
                });
            }
        } catch (JsonSyntaxException e) {
            GameLogger.getLogger(LangManager.class).error("合并翻译文件语法错误: lang=" + lang, e);
        } catch (Exception e) {
            GameLogger.getLogger(LangManager.class).error("合并翻译文件失败: lang=" + lang, e);
        }
    }

    public static void reload() {
        cache.clear();
    }
}
