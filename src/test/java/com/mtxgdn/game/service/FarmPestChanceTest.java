package com.mtxgdn.game.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 病虫害发生率必须与"每小时 2%"这个配置语义一致。
 *
 * <p><b>缺陷形态</b>：{@code FarmService} 里写的是
 * {@code double perSecond = PEST_CHANCE_PER_HOUR / 60.0}，而 {@code exposedSeconds}
 * 的单位是<b>秒</b>。等于把小时率当成分钟率用，病虫害发生率被放大 60 倍
 * —— 配置说 2%/小时，实际接近 2%/分钟，作物几乎一种下就生虫。</p>
 *
 * <p>这种量纲错误靠随机数跑用例很难稳定复现（要么太慢、要么偶发通过），
 * 所以把折算公式抽成包内可见的静态方法直接断言数值。</p>
 */
class FarmPestChanceTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    @DisplayName("暴露满 1 小时，生虫概率应约等于配置的 2%")
    void oneHourMatchesConfiguredRate() {
        double chance = FarmService.pestChanceForExposedSeconds(3600);
        assertEquals(0.02, chance, 0.001,
                "1 小时后的生虫概率应约为 2%；若得到 ~70% 说明分母又写成了 60");
    }

    @Test
    @DisplayName("暴露 1 分钟的概率约为 2%/60，而不是 2%")
    void oneMinuteIsSixtyTimesSmaller() {
        double oneMinute = FarmService.pestChanceForExposedSeconds(60);
        assertTrue(oneMinute > 0.0002 && oneMinute < 0.0004,
                "1 分钟的生虫概率应约 0.033%（2%/60），实际: " + oneMinute);
    }

    @Test
    @DisplayName("概率随暴露时间单调递增，且始终在 [0,1] 内")
    void chanceIsMonotonicAndBounded() {
        double previous = -1;
        for (long seconds : new long[]{0, 1, 60, 600, 3600, 21600, 86400, 604800}) {
            double chance = FarmService.pestChanceForExposedSeconds(seconds);
            assertTrue(chance >= 0.0 && chance <= 1.0, "概率越界: " + chance + " (seconds=" + seconds + ")");
            assertTrue(chance >= previous, "概率应单调不减: " + seconds + " 秒处 " + chance + " < " + previous);
            previous = chance;
        }
        // 6 小时：1-(1-0.02/3600)^21600 ≈ 11.3%
        assertEquals(0.113, FarmService.pestChanceForExposedSeconds(21600), 0.005,
                "6 小时后的生虫概率应约 11%");
    }

    @Test
    @DisplayName("非正暴露时长返回 0")
    void nonPositiveExposureYieldsZero() {
        assertEquals(0.0, FarmService.pestChanceForExposedSeconds(0), TOLERANCE);
        assertEquals(0.0, FarmService.pestChanceForExposedSeconds(-100), TOLERANCE);
    }
}
