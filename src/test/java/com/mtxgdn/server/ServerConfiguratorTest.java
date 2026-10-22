package com.mtxgdn.server;

import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grizzly 的线程池与超时配置必须真正生效。
 *
 * <p><b>两个缺陷</b>：</p>
 * <ol>
 *   <li>{@code network.read_timeout_ms} 被读进字段 {@code readTimeoutMs}、也在启动日志里
 *       打印出来，但<b>从来没被用到</b>。运维照着配置注释调它，不会有任何变化。</li>
 *   <li>工作线程池固定 {@code core=4 / max=8 / queue=100}。标准线程池语义下，
 *       队列没满之前不会把线程从 core 扩到 max —— 队列 100 条意味着 max 永远达不到，
 *       请求先排队 100 个才开始加线程，表现是"并发一高就整体变慢"。</li>
 * </ol>
 *
 * <p>这里不启动服务器，只构造 {@link NetworkListener} 并检查配置是否落到了它身上。</p>
 */
class ServerConfiguratorTest {

    @Test
    @DisplayName("network.read_timeout_ms 必须落到 listener 的事务超时上")
    void readTimeoutIsApplied() {
        String key = "network.read_timeout_ms";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "45000");
            ServerConfigurator configurator = new ServerConfigurator();
            NetworkListener listener = newListener();
            configurator.configureListener(listener);
            assertEquals(45, listener.getTransactionTimeout(),
                    "读取超时应按秒落到事务超时上（45000ms → 45s）；"
                            + "若为默认值说明该配置项仍然是死配置");
        } finally {
            restore(key, original);
        }
    }

    @Test
    @DisplayName("工作线程队列上限必须明显小于默认的 100，否则 max 永远达不到")
    void workerQueueLimitIsSane() {
        ServerConfigurator configurator = new ServerConfigurator();
        NetworkListener listener = newListener();
        configurator.configureListener(listener);

        ThreadPoolConfig pool = listener.getTransport().getWorkerThreadPoolConfig();
        assertTrue(pool.getCorePoolSize() > 0, "core 必须为正");
        assertTrue(pool.getMaxPoolSize() >= pool.getCorePoolSize(), "max 不得小于 core");
        assertTrue(pool.getQueueLimit() > 0 && pool.getQueueLimit() <= 64,
                "队列上限应在 (0,64] 内，实际: " + pool.getQueueLimit()
                        + "；过大（如 100）会让 max 永远达不到");
    }

    @Test
    @DisplayName("队列上限可配置")
    void workerQueueLimitIsConfigurable() {
        String key = "performance.grizzly_worker_queue";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "7");
            ServerConfigurator configurator = new ServerConfigurator();
            NetworkListener listener = newListener();
            configurator.configureListener(listener);
            assertEquals(7, listener.getTransport().getWorkerThreadPoolConfig().getQueueLimit(),
                    "队列上限应可通过 performance.grizzly_worker_queue 配置");
        } finally {
            restore(key, original);
        }
    }

    @Test
    @DisplayName("读取超时配置为 0/负数时不设置事务超时（表示不限制）")
    void zeroReadTimeoutDisablesTransactionTimeout() {
        String key = "network.read_timeout_ms";
        String original = System.getProperty(key);
        try {
            System.setProperty(key, "0");
            ServerConfigurator configurator = new ServerConfigurator();
            NetworkListener listener = newListener();
            int before = listener.getTransactionTimeout();
            configurator.configureListener(listener);
            assertEquals(before, listener.getTransactionTimeout(),
                    "配置为 0 时不应改动事务超时");
        } finally {
            restore(key, original);
        }
    }

    private static NetworkListener newListener() {
        // 端口 0 = 不真正绑定，这里只关心配置字段
        return new NetworkListener("test-listener", "127.0.0.1", 0);
    }

    private static void restore(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
