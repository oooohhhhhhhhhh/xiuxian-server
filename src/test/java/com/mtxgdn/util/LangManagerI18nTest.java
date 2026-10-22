package com.mtxgdn.util;

import com.mtxgdn.i18n.GameLanguage;
import com.mtxgdn.i18n.I18nContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 物品/秘境/事件名必须跟随玩家语言，而不是永远中文。
 *
 * <p><b>缺陷形态</b>：{@link LangManager} 读的是
 * {@code private static String currentLang = "zh_cn"} —— 一个进程级静态字段，
 * 而全仓库<b>没有任何地方调用过 {@code setLanguage}</b>。结果是物品名、秘境名、
 * 探索事件名永远只有中文：玩家把语言切成英文，这些名字依然是中文
 * （其它文案走 {@code I18nManager}，是英文），界面中英混排。</p>
 *
 * <p>修复后语言来自 {@link I18nContext}（线程级），并保留"缺词回退中文"。</p>
 */
class LangManagerI18nTest {

    /** 两种语言里译文不同的词条。 */
    private static final String KEY = "admin.auto_mute.enabled";

    @Test
    @DisplayName("中文线程取到中文词条")
    void chineseThreadGetsChinese() {
        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.ZH_CN, () -> value.set(LangManager.get(KEY)));
        assertEquals("已启用自动禁言", value.get());
    }

    @Test
    @DisplayName("英文线程取到英文词条（修复前这里仍然是中文）")
    void englishThreadGetsEnglish() {
        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.EN_US, () -> value.set(LangManager.get(KEY)));
        assertEquals("Auto-mute enabled", value.get(),
                "英文线程应取到英文词条；若仍是中文，说明语言来源又退回了进程级静态字段");
    }

    @Test
    @DisplayName("同一词条在中英两种语言下译文不同")
    void translationsDiffer() {
        AtomicReference<String> zh = new AtomicReference<>();
        AtomicReference<String> en = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.ZH_CN, () -> zh.set(LangManager.get(KEY)));
        I18nContext.runWith(GameLanguage.EN_US, () -> en.set(LangManager.get(KEY)));
        assertNotEquals(zh.get(), en.get(), "两门语言的译文应当不同，否则用例本身失去意义");
    }

    @Test
    @DisplayName("英文缺词时回退到中文，而不是把 key 抛给玩家")
    void fallsBackToChineseWhenMissing() {
        // 造一个只存在于中文的词条
        String zhOnlyKey = "test.lang_only_in_zh." + System.nanoTime();
        String json = "{\"" + zhOnlyKey + "\":\"只有中文有\"}";
        LangManager.merge(GameLanguage.ZH_CN.code(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.EN_US, () -> value.set(LangManager.get(zhOnlyKey, "DEFAULT")));
        assertEquals("只有中文有", value.get(),
                "英文词条缺失时应回退到中文，而不是返回默认值/裸 key");
    }

    @Test
    @DisplayName("两种语言都没有的 key 返回调用方给的默认值")
    void unknownKeyReturnsDefault() {
        String missing = "definitely.missing.key." + System.nanoTime();
        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.EN_US, () -> value.set(LangManager.get(missing, "FALLBACK")));
        assertEquals("FALLBACK", value.get());
    }

    @Test
    @DisplayName("语言按线程隔离：并发下不会互相串味")
    void languageIsThreadLocal() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<String> wrong = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            GameLanguage lang = (i % 2 == 0) ? GameLanguage.ZH_CN : GameLanguage.EN_US;
            String expected = lang == GameLanguage.ZH_CN ? "已启用自动禁言" : "Auto-mute enabled";
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    I18nContext.runWith(lang, () -> {
                        // 故意多读几次，放大竞态
                        for (int k = 0; k < 50; k++) {
                            String got = LangManager.get(KEY);
                            if (!expected.equals(got)) {
                                wrong.compareAndSet(null, "线程语言 " + lang.code()
                                        + " 期望「" + expected + "」实际「" + got + "」");
                                return;
                            }
                        }
                    });
                } finally {
                    done.countDown();
                }
            }, "lang-test-" + i);
            t.setDaemon(true);
            t.start();
        }

        ready.await();
        go.countDown();
        done.await();
        assertEquals(null, wrong.get(), "并发下语言串味了：" + wrong.get());
    }

    @Test
    @DisplayName("插件先合并词条不会抹掉基础语言文件")
    void mergeBeforeLoadKeepsBaseEntries() {
        // 模拟启动顺序：插件在业务第一次取词条之前就 merge 了自己的 lang 文件
        LangManager.reload();
        String pluginKey = "test.plugin_entry." + System.nanoTime();
        String json = "{\"" + pluginKey + "\":\"插件词条\"}";
        LangManager.merge(GameLanguage.ZH_CN.code(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        AtomicReference<String> base = new AtomicReference<>();
        AtomicReference<String> plugin = new AtomicReference<>();
        I18nContext.runWith(GameLanguage.ZH_CN, () -> {
            base.set(LangManager.get(KEY));
            plugin.set(LangManager.get(pluginKey));
        });
        assertEquals("已启用自动禁言", base.get(),
                "插件先 merge 时，基础语言文件仍必须被加载；"
                        + "若这里返回裸 key，说明 merge 造了个空壳把基础词条挡在外面");
        assertEquals("插件词条", plugin.get(), "插件自己的词条也要生效");
    }

    @Test
    @DisplayName("未绑定线程时使用服务端默认语言")
    void unboundThreadUsesServerDefault() {
        AtomicReference<String> value = new AtomicReference<>();
        I18nContext.runWith(null, () -> value.set(LangManager.getLanguage()));
        assertEquals(com.mtxgdn.i18n.I18nManager.serverDefault().code(), value.get());
    }
}
