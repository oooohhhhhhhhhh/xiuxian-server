package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 今日天象必须是"全局且确定"的。
 *
 * <p><b>缺陷形态</b>：{@code todayPhenomenon} / {@code phenomenonDate} 原来是<b>实例</b>字段，
 * 首次访问时用实例 {@code Random} 抽一个：</p>
 * <ul>
 *   <li><b>不全局</b>：{@code new DailyService()} 散布在 {@code OfflineRewardService}、
 *       {@code CombatService}、{@code ExplorationService}、{@code SkillService} 等处，
 *       每个新实例都从 {@code phenomenonDate == null} 开始，各自给"今天"抽一个不同的天象 ——
 *       同一时刻，界面显示的天象和掉落结算用的天象可以不是同一个。</li>
 *   <li><b>不持久</b>：重启后重新抽，"今日天象"说变就变。</li>
 *   <li><b>不线程安全</b>：注释写着"线程安全"，但两个线程同时进
 *       {@code if (phenomenonDate == null)} 会各抽一次。</li>
 * </ul>
 *
 * <p>修复：static 缓存 + 按日期播种的确定性抽取。</p>
 */
class DailyPhenomenonGlobalTest extends TestBase {

    @Test
    @DisplayName("同一个日期永远抽出同一个天象（重启/多实例都一致）")
    void deterministicPerDate() {
        LocalDate date = LocalDate.of(2026, 3, 15);
        CelestialPhenomenon first = DailyService.pickPhenomenonFor(date);
        assertNotNull(first);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, DailyService.pickPhenomenonFor(date),
                    "同一日期必须恒定抽出同一个天象，否则每次重启/每个新实例都会换一个");
        }
    }

    @Test
    @DisplayName("不同实例拿到的今日天象必须相同")
    void allInstancesAgree() {
        CelestialPhenomenon a = new DailyService().getTodayPhenomenon();
        CelestialPhenomenon b = new DailyService().getTodayPhenomenon();
        CelestialPhenomenon c = DailyService.getTodayPhenomenonStatic();
        assertEquals(a, b, "两个 DailyService 实例对今日天象的判断必须一致");
        assertEquals(a, c, "静态入口与实例入口必须一致");
    }

    @Test
    @DisplayName("并发首次访问不会抽到两个不同天象")
    void concurrentFirstAccessIsConsistent() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<CelestialPhenomenon> seen = Collections.synchronizedSet(new HashSet<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    seen.add(new DailyService().getTodayPhenomenon());
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "并发任务未在 10 秒内完成");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, seen.size(),
                "并发首访时出现了多个不同天象，说明存在竞态: " + seen);
    }

    @Test
    @DisplayName("天象分布不会退化（一年内应覆盖到多个不同天象）")
    void distributionIsNotDegenerate() {
        Set<CelestialPhenomenon> seen = new HashSet<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 365; i++) {
            seen.add(DailyService.pickPhenomenonFor(start.plusDays(i)));
        }
        assertTrue(seen.size() >= Math.min(3, CelestialPhenomenon.values().length),
                "一年只抽到 " + seen.size() + " 种天象，分布明显退化: " + seen);
    }
}
