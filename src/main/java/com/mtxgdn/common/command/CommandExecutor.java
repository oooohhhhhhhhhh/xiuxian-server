package com.mtxgdn.common.command;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指令执行的独立线程池。
 *
 * <p><b>为什么需要</b>：QQ / Minecraft 通道的指令回调原来直接跑在 Grizzly 的
 * <b>worker 线程</b>上（默认 core=4 / max=8）。而这个线程池同时还要处理所有 REST 请求，
 * 于是一条指令的开销会直接变成"整个 HTTP 服务变慢"：</p>
 * <ul>
 *   <li>一条指令平均 3~12 次数据库查询；</li>
 *   <li>{@code /注册}、{@code /绑定}、{@code /改密} 还要跑 BCrypt ——
 *       故意设计成慢（默认 cost 下约 100ms CPU 密集），几个玩家同时注册就能占满 4 个 worker；</li>
 *   <li>worker 占满后，管理台、静态资源、健康检查<b>一起</b>卡住，
 *       表现为"玩家用指令，网页打不开"。</li>
 * </ul>
 *
 * <p>解法：worker 线程只负责"收下并投递"，真正的执行放到这个有界线程池里。
 * 有界（而不是无界）是刻意的：指令洪泛时应该<b>明确拒绝</b>并告知玩家，
 * 而不是把任务无限堆进队列，最后把堆撑爆。</p>
 *
 * <p>配置：</p>
 * <ul>
 *   <li>{@code performance.command_threads}（默认 4）—— 并发执行指令的线程数；</li>
 *   <li>{@code performance.command_queue}（默认 200）—— 排队上限，满了就拒绝。</li>
 * </ul>
 *
 * <p>线程是 daemon 且带名字（{@code cmd-worker-N}）：关服时不会阻止 JVM 退出，
 * 线程转储里也能一眼看出是哪一个在跑长指令。</p>
 */
public final class CommandExecutor {

    private static final GameLogger LOG = GameLogger.getLogger(CommandExecutor.class);

    /** 并发执行指令的线程数。 */
    private static final int THREADS = Math.max(1, AppConfig.getInt("performance.command_threads", 4));

    /** 排队上限：满了直接拒绝，避免无界堆积。 */
    private static final int QUEUE_CAPACITY = Math.max(1, AppConfig.getInt("performance.command_queue", 200));

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            THREADS, THREADS, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "cmd-worker-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    static {
        // 空闲时回收线程：没指令的时候不该常驻 4 个线程
        POOL.allowCoreThreadTimeOut(true);
    }

    private CommandExecutor() {
    }

    /**
     * 把一次指令执行投递给线程池。
     *
     * @param taskName 日志里标识这次执行的名字（如 {@code onebot:/breakthrough}），
     *                 只用于排查，不要放玩家隐私数据
     * @param task     实际执行体；内部抛出的任何异常都会被捕获并记录，
     *                 不会污染线程池也不会静默消失
     * @return {@code true} 表示已入队；{@code false} 表示队列已满被拒绝，
     *         调用方应回复玩家"服务器繁忙"而不是假装成功
     */
    public static boolean submit(String taskName, Runnable task) {
        try {
            POOL.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    // 兜底：指令实现里的任何异常都必须留下痕迹。
                    // 不这么做的话异常会被 ThreadPoolExecutor 吞掉，
                    // 玩家侧只看到"没有反应"，日志里什么都没有。
                    LOG.error("指令执行异常 [" + taskName + "]", t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            LOG.warn("指令线程池已满，拒绝执行 [" + taskName
                    + "]（线程 " + THREADS + "，队列上限 " + QUEUE_CAPACITY + "）");
            return false;
        }
    }

    /** 当前正在执行的任务数（监控用）。 */
    public static int getActiveCount() {
        return POOL.getActiveCount();
    }

    /** 当前排队任务数（监控用）。 */
    public static int getQueueSize() {
        return POOL.getQueue().size();
    }

    /** 线程数（配置值，监控/日志用）。 */
    public static int getThreads() {
        return THREADS;
    }

    /** 队列上限（配置值）。 */
    public static int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }

    /** 关服时调用：不再接受新任务，已入队的尽量跑完。 */
    public static void shutdown() {
        POOL.shutdown();
        try {
            if (!POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                POOL.shutdownNow();
            }
        } catch (InterruptedException e) {
            POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
