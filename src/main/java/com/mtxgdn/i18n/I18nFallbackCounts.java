package com.mtxgdn.i18n;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 回退与缺失计数。仅用于可观测性（{@code GET /admin/i18n/status}），不参与翻译逻辑。
 *
 * <p>分开统计"回退命中"与"彻底缺失"两种情况：前者说明某门语言没翻完但还能兜住，
 * 后者说明连回退源都没有，属于真 bug。</p>
 */
final class I18nFallbackCounts {

    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final Map<String, LongAdder> PER_LANGUAGE = new ConcurrentHashMap<>();

    private I18nFallbackCounts() {
    }

    static void hit() {
        HITS.increment();
    }

    static void miss() {
        MISSES.increment();
    }

    static long fallbackHits() {
        return HITS.sum();
    }

    static long misses() {
        return MISSES.sum();
    }

    static void forLanguageIncrement(String langCode) {
        PER_LANGUAGE.computeIfAbsent(langCode, k -> new LongAdder()).increment();
    }

    static long forLanguage(String langCode) {
        LongAdder adder = PER_LANGUAGE.get(langCode);
        return adder != null ? adder.sum() : 0L;
    }

    static void reset() {
        HITS.reset();
        MISSES.reset();
        PER_LANGUAGE.clear();
    }

    /** 仅测试用：直接拿到当前语言维度的累加器快照。 */
    static Map<String, Long> snapshot() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, LongAdder> e : PER_LANGUAGE.entrySet()) {
            out.put(e.getKey(), e.getValue().sum());
        }
        return out;
    }
}
