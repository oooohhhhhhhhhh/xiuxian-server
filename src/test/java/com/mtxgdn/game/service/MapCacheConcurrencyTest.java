package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地图缓存的并发安全。
 *
 * <p><b>缺陷形态</b>：{@code MapService} 的六份缓存原来是裸 {@code LinkedHashMap} /
 * {@code HashMap} / {@code ArrayList}，只有 {@code reload()} 自己是 {@code synchronized}。
 * 于是"后台热重载（管理后台改完 {@code map_locations} 后调用）"与"前台读地图"
 * 会撞在一起：重载方 {@code clear()} 后逐条重填，读取方正在遍历 {@code values()}，
 * 直接抛 {@code ConcurrentModificationException}；更糟的是重载中间态会被读到 ——
 * 地点表已清空、连接表还没重填，玩家这时传送就会"该地点不存在"或传到错误地点。</p>
 *
 * <p>本测试让一个线程反复热重载、另一个线程反复读地图，断言不出现任何异常。
 * 用旧实现跑这个用例会稳定抛 {@code ConcurrentModificationException}。</p>
 */
class MapCacheConcurrencyTest extends TestBase {

    @Test
    @DisplayName("并发读地图 + 热重载不应抛异常")
    void concurrentReadsAndReloadDoNotThrow() throws Exception {
        MapService mapService = ServiceRegistry.getMapService();
        // 先触发初始化，确保后面的读路径走的是"已有缓存"而不是首次加载
        assertNotNull(mapService.getLocation(1), "地点 1 应存在（出厂数据）");
        assertTrue(mapService.getAllLocations().size() > 0, "已知世界应有地点");

        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean reloadFinished = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong readCount = new AtomicLong();

        // 读线程一直读到重载结束为止 —— 必须保证两条线程真的重叠，
        // 否则 300 次读很可能在第一次重载开始前就跑完了，测不出任何东西。
        Thread reader = new Thread(() -> {
            try {
                start.await();
                while (!reloadFinished.get()) {
                    mapService.getAllLocations();
                    mapService.getKnownWorldLocations();
                    mapService.getLocationsByRegion("青云山脉");
                    mapService.getLocation(1);
                    mapService.isLocationKnown(1);
                    readCount.incrementAndGet();
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            }
        }, "map-reader");

        Thread reloader = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 40; i++) {
                    mapService.reload();
                }
            } catch (Throwable t) {
                error.compareAndSet(null, t);
            } finally {
                reloadFinished.set(true);
            }
        }, "map-reloader");

        reader.setDaemon(true);
        reloader.setDaemon(true);
        reader.start();
        reloader.start();
        start.countDown();
        reloader.join(120_000);
        reader.join(120_000);

        assertNull(error.get(),
                "并发读地图与热重载不应抛异常，实际: " + describe(error.get()));
        assertTrue(readCount.get() > 0, "读线程应至少完成一轮读取");
        // 重载结束后地图必须仍然完整（不能停在"清空后只填了一半"的状态）
        List<?> after = mapService.getAllLocations();
        assertTrue(after.size() > 0, "重载后已知世界地点不能为空");
        assertNotNull(mapService.getLocation(1), "重载后地点 1 仍应存在");
    }

    /** 并发失败时没有栈就完全没法定位，这里把栈一并塞进断言消息。 */
    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(t.toString());
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n    at ").append(e);
        }
        return sb.toString();
    }
}
