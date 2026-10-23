package com.mtxgdn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多语言核心层回归测试。
 *
 * <p>覆盖三条最容易出错的边界：</p>
 * <ul>
 *   <li>语言协商：{@code ?lang=} / {@code X-Lang} / {@code Accept-Language} /
 *       服务端默认的优先级，以及"未知代码静默落下一级"</li>
 *   <li>回退链：en_us 缺 key 时回落 zh_cn，两边都缺才返回 key 本身</li>
 *   <li>{@link I18nContext} 的线程隔离：一个请求的语言不能带到下一个请求</li>
 * </ul>
 *
 * <p>这些测试刻意不碰数据库与插件目录，只驱动 {@link I18nManager} 的内存状态，
 * 所以不会与共享 SQLite 测试库的其他用例相互干扰。</p>
 */
class I18nTest {

    @BeforeEach
    void setUp() {
        I18nManager.reload();
        I18nContext.clear();
        I18nFallbackCounts.reset();
    }

    @AfterEach
    void tearDown() {
        I18nContext.clear();
        I18nManager.reload();
        I18nFallbackCounts.reset();
    }

    // ==================== GameLanguage.parse ====================

    @Test
    @DisplayName("parse: 大小写/连字符/仅语言段都应认得出 zh_cn 与 en_us")
    void parseIsCaseAndSeparatorInsensitive() {
        assertEquals(GameLanguage.ZH_CN, GameLanguage.parse("zh_cn"));
        assertEquals(GameLanguage.ZH_CN, GameLanguage.parse("ZH_CN"));
        assertEquals(GameLanguage.ZH_CN, GameLanguage.parse("zh-CN"));
        assertEquals(GameLanguage.ZH_CN, GameLanguage.parse("zh"));
        assertEquals(GameLanguage.ZH_CN, GameLanguage.parse("  zh_cn  "));
        assertEquals(GameLanguage.EN_US, GameLanguage.parse("en_us"));
        assertEquals(GameLanguage.EN_US, GameLanguage.parse("EN-US"));
        assertEquals(GameLanguage.EN_US, GameLanguage.parse("en"));
    }

    @Test
    @DisplayName("parse: 未知语言返回 null 而不是抛异常，让调用方能静默回落")
    void parseReturnsNullForUnknown() {
        assertNull(GameLanguage.parse("ja_jp"));
        assertNull(GameLanguage.parse(""));
        assertNull(GameLanguage.parse("  "));
        assertNull(GameLanguage.parse(null));
    }

    @Test
    @DisplayName("toLocale: 供 java.util.Locale 与 MessageFormat 之外的本地化 API 使用")
    void toLocaleMapsCorrectly() {
        assertEquals("zh", GameLanguage.ZH_CN.toLocale().getLanguage());
        assertEquals("CN", GameLanguage.ZH_CN.toLocale().getCountry());
        assertEquals("en", GameLanguage.EN_US.toLocale().getLanguage());
        assertEquals("US", GameLanguage.EN_US.toLocale().getCountry());
    }

    // ==================== 加载与回退链 ====================

    @Test
    @DisplayName("load: classpath 内置语言包应被加载，且 zh_cn 词条数远超 en_us")
    void loadClasspathBundles() {
        I18nManager.load();
        assertTrue(I18nManager.isLoaded(GameLanguage.ZH_CN), "zh_cn 应已加载");
        assertTrue(I18nManager.isLoaded(GameLanguage.EN_US), "en_us 应已加载");
        assertTrue(I18nManager.bundle(GameLanguage.ZH_CN).size() > 100,
                "zh_cn 词条数应超过 100，实际 " + I18nManager.bundle(GameLanguage.ZH_CN).size());
        assertTrue(I18nManager.isLoaded(GameLanguage.EN_US));
    }

    @Test
    @DisplayName("回退链: en_us 缺 key 时应回落 zh_cn 而非把 key 漏给玩家")
    void fallbackToZhCnWhenEnMissing() {
        I18nManager.load();
        // en_us 已全量翻译，"真实缺 key"的场景不再天然存在，
        // 所以造一条只进 zh_cn 的词条来驱动回退链 —— 比依赖某个具体词条有没有翻译更稳。
        String someKey = "__i18n_test_fallback_only_zh__";
        mergeInto(GameLanguage.ZH_CN, Map.of(someKey, "只有中文的兜底文案"));

        String en = I18nManager.raw(someKey, GameLanguage.EN_US);
        String zh = I18nManager.raw(someKey, GameLanguage.ZH_CN);

        assertEquals(zh, en, "en_us 缺 key 时必须回落 zh_cn 的值");
        assertNotEquals(someKey, en, "不能把 key 本身当成译文返回");
    }

    @Test
    @DisplayName("回退链: 任何语言都缺 key 时才返回 key 本体")
    void missingEverywhereReturnsKey() {
        I18nManager.load();
        String key = "__i18n_test___definitely_missing__";
        assertEquals(key, I18nManager.raw(key, GameLanguage.ZH_CN));
        assertEquals(key, I18nManager.raw(key, GameLanguage.EN_US));
        // missingKeys 只回答"fallback 有而目标语言无"，全局缺失的 key 不该出现在里面
        assertFalse(I18nManager.missingKeys(GameLanguage.EN_US).contains(key),
                "missingKeys 只统计 fallback 有、目标语言无的 key；全局缺失不属于这个范畴");
    }

    @Test
    @DisplayName("raw: 不传语言时按 I18nContext 当前语言取")
    void rawUsesContextLanguage() {
        I18nManager.load();
        String key = "sect.sect_names_must_be_2";
        I18nContext.set(GameLanguage.ZH_CN);
        assertEquals(I18nManager.raw(key, GameLanguage.ZH_CN), I18nManager.raw(key));
        I18nContext.set(GameLanguage.EN_US);
        assertEquals(I18nManager.raw(key, GameLanguage.EN_US), I18nManager.raw(key));
    }

    // ==================== 占位符 ====================

    @Test
    @DisplayName("format: {0} {1} 按序替换，多余的占位符原样保留")
    void formatPositionalPlaceholders() {
        I18nManager.load();
        String tpl = "需要 {0} 灵石，当前 {1}";
        Map<String, String> probe = Map.of("probe.k", tpl);
        mergeInto(GameLanguage.ZH_CN, probe);

        assertEquals("需要 100 灵石，当前 50", I18nManager.format("probe.k", GameLanguage.ZH_CN, 100, 50));
        // 参数少于占位符：剩下的 {n} 保留，不让玩家看到裸 key
        assertEquals("需要 100 灵石，当前 {1}", I18nManager.format("probe.k", GameLanguage.ZH_CN, 100));
        // 参数多于占位符：多出来的忽略
        assertEquals("需要 1 灵石，当前 2", I18nManager.format("probe.k", GameLanguage.ZH_CN, 1, 2, 3));
    }

    @Test
    @DisplayName("format: 单引号必须原样保留（MessageFormat 会吃掉它，这是不用它的理由）")
    void formatKeepsSingleQuotes() {
        I18nManager.mergeFromStream(GameLanguage.ZH_CN,
                json("quote.k", "don''t touch {0}, it's mine"));
        // MessageFormat 会把 '' 折叠成一个 '、并把 '...' 当成引用块整段吞掉；
        // 朴素的 {n} 替换则让所有单引号逐字保留。这条断言就是钉住这个设计决定。
        assertEquals("don''t touch 灵石, it's mine",
                I18nManager.format("quote.k", GameLanguage.ZH_CN, "灵石"));
    }

    @Test
    @DisplayName("I18n.t: 不带语言参数时读 I18nContext，与显式传语言结果一致")
    void ITUsesContext() {
        I18nManager.load();
        String key = "sect.sect_names_must_be_2";
        I18nContext.set(GameLanguage.EN_US);
        assertEquals(I18n.t(key, GameLanguage.EN_US), I18n.t(key));
    }

    @Test
    @DisplayName("I18n.tOr: 显式兜底优先于任何语言包结果")
    void tOrUsesExplicitFallback() {
        I18nManager.load();
        assertEquals("兜底文案", I18n.tOr("__missing__key__", "兜底文案"));
    }

    // ==================== I18nContext 线程隔离 ====================

    @Test
    @DisplayName("I18nContext: 每个线程持有自己的语言，互不串扰")
    void contextIsPerThread() throws Exception {
        I18nManager.load();
        I18nContext.set(GameLanguage.ZH_CN);

        Thread other = new Thread(() -> {
            // peek() 回答"本线程有没有显式设过"，新线程没设过 → null；
            // current() 回答"实际该用哪种语言"，于是回落到默认语言。
            // 这两个语义必须分开，否则"没设过"和"设成了默认语言"会被混为一谈。
            assertNull(I18nContext.peek(), "新线程不应继承主线程显式设过的语言");
            assertEquals(GameLanguage.defaultLanguage(), I18nContext.current(),
                    "没设过时 current() 应回落到默认语言");
            I18nContext.set(GameLanguage.EN_US);
            assertEquals(GameLanguage.EN_US, I18nContext.current());
        });
        other.start();
        other.join();

        // 主线程不受子线程影响
        assertEquals(GameLanguage.ZH_CN, I18nContext.current());
    }

    @Test
    @DisplayName("I18nContext.runWith: 执行完后恢复进入前的语言，异常时也恢复")
    void runWithRestoresPrevious() {
        I18nContext.set(GameLanguage.EN_US);

        I18nContext.runWith(GameLanguage.ZH_CN, () ->
                assertEquals(GameLanguage.ZH_CN, I18nContext.current()));

        assertEquals(GameLanguage.EN_US, I18nContext.current(), "runWith 结束后必须恢复");

        assertThrows(RuntimeException.class, () -> I18nContext.runWith(GameLanguage.ZH_CN, () -> {
            throw new RuntimeException("boom");
        }));
        assertEquals(GameLanguage.EN_US, I18nContext.current(), "异常路径也要恢复语言");
    }

    @Test
    @DisplayName("I18nContext.clear: 请求结束时必须清掉，否则线程池会把语言带给下一个请求")
    void clearRemovesBinding() {
        I18nContext.set(GameLanguage.EN_US);
        I18nContext.clear();
        assertEquals(GameLanguage.defaultLanguage(), I18nContext.current(),
                "clear 之后应回到默认语言，而不是继续返回 EN_US");
    }

    // ==================== mergeFromStream ====================

    @Test
    @DisplayName("mergeFromStream: 插件可覆盖同名内置 key，且只增不删")
    void mergeOverridesButDoesNotRemove() {
        I18nManager.load();
        String key = "sect.sect_names_must_be_2";
        String original = I18nManager.raw(key, GameLanguage.ZH_CN);

        I18nManager.mergeFromStream(GameLanguage.ZH_CN, json(key, "插件改写后的文案"));
        assertEquals("插件改写后的文案", I18nManager.raw(key, GameLanguage.ZH_CN));

        // 另一个内置 key 必须还在：合并不能把整包换掉
        assertNotNull(I18nManager.raw("sect.sect_names_must_be_2", GameLanguage.ZH_CN));
        assertNotEquals(original, I18nManager.raw(key, GameLanguage.ZH_CN));
    }

    @Test
    @DisplayName("mergeFromStream: 坏 JSON 只告警不抛异常，不影响服务继续跑")
    void mergeToleratesBadJson() {
        I18nManager.load();
        assertDoesNotThrow(() -> {
            InputStream bad = new ByteArrayInputStream("{ 这不是 json".getBytes(StandardCharsets.UTF_8));
            I18nManager.mergeFromStream(GameLanguage.ZH_CN, bad);
        });
    }

    @Test
    @DisplayName("reload: 外部改动应能被丢弃并回到 classpath 基线")
    void reloadDiscardsMergedEntries() {
        I18nManager.load();
        String key = "sect.sect_names_must_be_2";
        String baseline = I18nManager.raw(key, GameLanguage.ZH_CN);

        I18nManager.mergeFromStream(GameLanguage.ZH_CN, json(key, "临时覆盖"));
        assertEquals("临时覆盖", I18nManager.raw(key, GameLanguage.ZH_CN));

        I18nManager.reload();
        assertEquals(baseline, I18nManager.raw(key, GameLanguage.ZH_CN),
                "reload 后必须回到 classpath 基线");
    }

    // ==================== 可观测性 ====================

    @Test
    @DisplayName("status: 每种语言都应有 keyCount / missingCount / code")
    void statusReportsEveryLanguage() {
        I18nManager.load();
        Map<String, Object> status = I18nManager.status();
        assertEquals(GameLanguage.values().length, status.size());
        for (Map.Entry<String, Object> e : status.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            assertTrue(entry.containsKey("keyCount"));
            assertTrue(entry.containsKey("missingCount"));
            assertTrue(entry.containsKey("code"));
        }
    }

    @Test
    @DisplayName("exportJson: 导出的 JSON 含当前语言全部词条")
    void exportJsonRoundTrips() {
        I18nManager.load();
        String json = I18nManager.exportJson(GameLanguage.ZH_CN);
        assertNotNull(json);
        assertFalse(json.isBlank());
        assertTrue(json.contains("sect.sect_names_must_be_2"), "导出应含已知 key");
    }

    @Test
    @DisplayName("missingKeys: en_us 的缺失列表必须是 zh_cn 有而 en_us 无的子集")
    void missingKeysSubsetOfZhCn() {
        I18nManager.load();
        Map<String, String> zh = I18nManager.bundle(GameLanguage.ZH_CN);
        Map<String, String> en = I18nManager.bundle(GameLanguage.EN_US);
        for (String missing : I18nManager.missingKeys(GameLanguage.EN_US)) {
            assertTrue(zh.containsKey(missing), "缺失列表里的 key 必须存在于 zh_cn");
            assertFalse(en.containsKey(missing), "缺失列表里的 key 不应存在于 en_us");
        }
    }

    @Test
    @DisplayName("词条卫生: en_us 里不得存在与 zh_cn 一字不差的条目（那是没翻译的占位）")
    void englishPackHasNoUntranslatedPlaceholders() {
        I18nManager.load();
        Map<String, String> zh = I18nManager.bundle(GameLanguage.ZH_CN);
        Map<String, String> en = I18nManager.bundle(GameLanguage.EN_US);

        java.util.List<String> same = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : en.entrySet()) {
            String zhValue = zh.get(e.getKey());
            if (zhValue != null && zhValue.equals(e.getValue())
                    && !isIntentionallyUntranslated(e.getKey())) {
                same.add(e.getKey() + " = " + e.getValue());
            }
        }
        assertTrue(same.isEmpty(),
                "en_us 里有 " + same.size() + " 条与 zh_cn 完全相同，说明是占位没翻译"
                        + "（真的无需翻译的词条请删掉，让它走 zh_cn 回退链）: " + same);
    }

    /**
     * 那些"本来就不该翻译"的词条。
     *
     * <p>{@code cmd.*.usage} 是命令语法行，里面的 {@code /修炼}、{@code /宗门} 是
     * 指令解析器真正匹配的 token —— 翻成英文反而会让玩家照抄一条打不出来的命令。
     * {@code error.ok} 的值是 {@code ok}，本身就没有语言之分。</p>
     *
     * <p>这条豁免必须收得很紧：一旦放开成"以 cmd. 开头都算"，就再也发现不了
     * 帮助文案里漏翻的正文了。</p>
     */
    private static boolean isIntentionallyUntranslated(String key) {
        if ("error.ok".equals(key)) {
            return true;
        }
        return key.startsWith("cmd.") && key.endsWith(".usage");
    }

    // ==================== 工具方法 ====================

    private static InputStream json(String key, String value) {
        return json(Map.of(key, value));
    }

    /**
     * 构造词条 JSON。用 Gson 序列化而不是手拼——手拼的转义（尤其引号与反斜杠）
     * 一漏就变成 MalformedJson，测出来的反而是 mergeFromStream 的告警分支。
     */
    private static InputStream json(Map<String, String> entries) {
        String body = new com.google.gson.Gson().toJson(entries);
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void mergeInto(GameLanguage lang, Map<String, String> entries) {
        I18nManager.mergeFromStream(lang, json(entries));
    }
}
