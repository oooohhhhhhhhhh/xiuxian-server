package com.mtxgdn.util;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量级内存滑动窗口限流器。
 *
 * <p><b>V11 修复</b>：原实现没有任何容量上限。而限流的 key 是<b>攻击者可控</b>的
 * （如 {@code login_<用户名>}、{@code send_code_ip:<来源>}），攻击者只需用海量
 * 随机用户名/伪造来源发起请求，就能让 {@code windows} 映射无限增长直至 OOM ——
 * 限流器本身成了内存耗尽攻击的入口。</p>
 *
 * <p>现在增加了三重保护：</p>
 * <ol>
 *   <li>{@link #MAX_ENTRIES} 容量上限：超过后淘汰最旧窗口，保证内存有界；</li>
 *   <li>{@link #maybeSweep()} 惰性清扫：每 {@link #SWEEP_INTERVAL} 次调用清理一次
 *       已过期窗口，不依赖外部定时任务；</li>
 *   <li>保留显式 {@link #cleanupExpired(int)} 供启动时/定时任务调用。</li>
 * </ol>
 *
 * <p><b>已知设计限制</b>（本次未改动，多实例部署需注意）：窗口状态存在单进程内存中，
 * 进程重启后计数清零，且多实例部署时各实例独立计数（实际阈值被放大为
 * 「单实例阈值 × 实例数」）。需要跨实例精确限流时应改用 Redis 等共享存储；
 * 单实例部署下行为正确。</p>
 */
public class RateLimiter {

    private static final ConcurrentHashMap<String, RateWindow> windows = new ConcurrentHashMap<>();

    /**
     * V11：窗口映射最大条目数，超过后淘汰最旧窗口。
     * 默认 10 万 —— 正常服务远达不到，攻击场景下用于封顶内存占用。
     */
    private static final int MAX_ENTRIES = 100_000;

    /** V11：每 N 次 allow() 调用触发一次惰性过期清扫。 */
    private static final int SWEEP_INTERVAL = 1024;

    private static final AtomicLong callCounter = new AtomicLong(0);

    private static class RateWindow {
        final AtomicLong counter = new AtomicLong(0);
        final long windowStart;
        volatile int windowSeconds;

        RateWindow(long windowStart, int limit, int windowSeconds) {
            this.windowStart = windowStart;
            this.windowSeconds = windowSeconds;
        }
    }

    public static boolean allow(String key, int limit, int windowSeconds) {
        if (key == null) {
            return true; // 无 key 视为不限流，避免 NPE 影响主流程
        }
        // V11：定期惰性清扫，防止攻击者用海量新 key 撑爆内存
        maybeSweep();

        long now = System.currentTimeMillis() / 1000;
        RateWindow window = windows.compute(key, (k, old) -> {
            if (old == null || (now - old.windowStart) >= old.windowSeconds) {
                return new RateWindow(now, limit, windowSeconds);
            }
            return old;
        });

        synchronized (window) {
            if (now - window.windowStart >= window.windowSeconds) {
                window.counter.set(0);
                windows.put(key, new RateWindow(now, limit, windowSeconds));
                window = windows.get(key);
            }
            return window.counter.incrementAndGet() <= limit;
        }
    }

    /** V11：惰性清扫，按调用次数节流，避免每次请求都全表扫描。 */
    private static void maybeSweep() {
        if (callCounter.incrementAndGet() % SWEEP_INTERVAL != 0) {
            return;
        }
        evictIfOversized();
    }

    /**
     * V11：容量保护。超过 {@link #MAX_ENTRIES} 时淘汰"窗口最早开始"的一批条目 ——
     * 越早开始的窗口越先自然过期，淘汰它们对正常限流判定影响最小。
     */
    private static void evictIfOversized() {
        int size = windows.size();
        if (size <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;

        // 第一步：先清掉已过期的（零成本，通常能释放大部分空间）
        windows.entrySet().removeIf(entry ->
                (now - entry.getValue().windowStart) >= entry.getValue().windowSeconds);
        if (windows.size() <= MAX_ENTRIES) {
            return;
        }

        // 第二步：仍然超限则按窗口开始时间淘汰最旧的一批，降到上限的 90%
        int target = (int) (MAX_ENTRIES * 0.9);
        List<String> oldest = windows.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().windowStart))
                .limit(Math.max(1, windows.size() - target))
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
        oldest.forEach(windows::remove);

        System.err.println("[RateLimiter] 警告：限流窗口条目超过上限，已淘汰 " + oldest.size()
                + " 条最旧记录（当前 " + windows.size() + " 条）。可能存在异常流量。");
    }

    public static long getRemaining(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        RateWindow window = windows.get(key);
        if (window == null || (now - window.windowStart) >= windowSeconds) {
            return limit;
        }
        return Math.max(0, limit - window.counter.get());
    }

    public static void cleanupExpired(int maxWindowSeconds) {
        long now = System.currentTimeMillis() / 1000;
        windows.entrySet().removeIf(entry -> (now - entry.getValue().windowStart) >= maxWindowSeconds);
    }

    public static int size() {
        return windows.size();
    }

    /** V11：当前容量上限（测试与监控用）。 */
    public static int maxEntries() {
        return MAX_ENTRIES;
    }

    public static void clear() {
        windows.clear();
    }
}
