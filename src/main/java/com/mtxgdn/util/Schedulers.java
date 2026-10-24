package com.mtxgdn.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台定时器的统一创建方式。
 *
 * <p><b>为什么需要它</b>：仓库里有十来个服务在静态初始化块里
 * {@code Executors.newSingleThreadScheduledExecutor()} 起一个"每 N 秒扫一遍"的
 * 守护任务。裸用这个工厂方法有两个坑：</p>
 *
 * <ul>
 *   <li><b>线程不是 daemon</b>：只要有一个这样的线程还活着，JVM 就不会退出。
 *       关服时如果有人忘了调 {@code shutdown()}，进程会挂在那里不退，
 *       现场看起来像"关不掉"。</li>
 *   <li><b>线程没有名字</b>：出问题时 jstack 里全是 {@code pool-7-thread-1}，
 *       根本认不出是哪路定时任务在卡。</li>
 * </ul>
 *
 * <p>所以统一走这里：daemon + 可读的线程名。定时任务自己仍然要在每次 tick
 * 开头检查 {@link com.mtxgdn.db.DatabaseManager#isReady()}，
 * 免得池子关掉之后刷一屏无意义的异常。</p>
 */
public final class Schedulers {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Schedulers() {
    }

    /**
     * 单线程定时器，线程为 daemon 且带可读名字。
     *
     * @param name 线程名，例如 {@code player-auto-heal}
     */
    public static ScheduledExecutorService newDaemonSingle(String name) {
        String threadName = name + "-" + SEQ.incrementAndGet();
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
    }
}
