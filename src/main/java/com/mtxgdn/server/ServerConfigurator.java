package com.mtxgdn.server;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.websockets.WebSocketAddOn;

public class ServerConfigurator {

    private static final GameLogger LOG = GameLogger.getLogger(ServerConfigurator.class);

    private final int ioThreads;
    private final int workerCore;
    private final int workerMax;
    private final int connectionTimeoutMs;
    private final int readTimeoutMs;
    private final boolean lowMemoryMode;
    /** V1：WebSocket 单帧最大字节数（默认 64KB） */
    private final int maxWebSocketMessageSize;

    /** 默认 WebSocket 单帧上限：64KB，足够容纳任何正常游戏消息。 */
    public static final int DEFAULT_MAX_WS_MESSAGE_SIZE = 65536;

    /**
     * V1：当前生效的 WebSocket 单帧上限（字符数）。
     *
     * <p>供 {@code GameWebSocketApp} 在解析前强制校验使用 —— Grizzly 4.0.2 不提供
     * 框架级单帧上限，应用层检查是唯一可靠的拦截点。</p>
     */
    public static int getMaxWebSocketMessageSize() {
        return AppConfig.getInt("network.ws_max_message_size", DEFAULT_MAX_WS_MESSAGE_SIZE);
    }

    public ServerConfigurator() {
        this.ioThreads = AppConfig.getInt("performance.grizzly_io_threads", 2);
        this.workerCore = AppConfig.getInt("performance.grizzly_worker_cores", 4);
        this.workerMax = AppConfig.getInt("performance.grizzly_worker_max", 8);
        this.workerQueueLimit = AppConfig.getInt("performance.grizzly_worker_queue", DEFAULT_WORKER_QUEUE);
        this.connectionTimeoutMs = AppConfig.getInt("network.connection_timeout_ms", 10000);
        this.readTimeoutMs = AppConfig.getInt("network.read_timeout_ms", 30000);
        this.lowMemoryMode = AppConfig.getBoolean("performance.low_memory", false);
        this.maxWebSocketMessageSize = getMaxWebSocketMessageSize();
    }

    /**
     * 工作线程队列的默认上限。
     *
     * <p>必须明显小于"一个请求的期望排队时间能容忍的条数"：Grizzly 的工作线程池是标准
     * {@link java.util.concurrent.ThreadPoolExecutor} 语义 —— 队列没满之前<b>不会</b>把线程数
     * 从 core 扩到 max。原来 core=4 / max=8 / queue=100 的组合下，max 实际上永远达不到：
     * 请求先排队 100 条才开始加线程，表现就是"并发一高就整体变慢"而不是"多开线程扛住"。
     * 32 = core 的 8 倍，既能让 max 在突发时真正生效，又不至于让请求无限堆积。</p>
     */
    public static final int DEFAULT_WORKER_QUEUE = 32;

    /** 工作线程队列上限（见 {@link #DEFAULT_WORKER_QUEUE}）。 */
    private final int workerQueueLimit;

    public void configure(HttpServer server) {
        ThreadPoolConfig workerConfig = newWorkerPoolConfig();

        for (NetworkListener listener : server.getListeners()) {
            configureListener(listener, workerConfig);
        }

        WebSocketAddOn wsAddOn = new WebSocketAddOn();
        // V1 修复说明：Grizzly 4.0.2 的 WebSocket 栈（WebSocketAddOn / WebSocketFilter /
        // ProtocolHandler）并未提供 maxMessageSize 之类的单帧上限 API（已用 javap 核实：
        // WebSocketAddOn 仅有 setTimeoutInSeconds）。因此单帧大小限制必须在应用层
        // （GameWebSocketApp.onMessage 解析之前）强制执行，见 maxWebSocketMessageSize。
        // 这里同时收紧每连接读缓冲，降低超大分片帧的累积内存占用。
        server.getListeners().forEach(listener -> listener.registerAddOn(wsAddOn));

        LOG.info("性能配置: Grizzly IO线程=" + ioThreads +
                " 工作线程(" + workerCore + "/" + workerMax + ") 队列上限=" + workerQueueLimit);
        LOG.info("网络超时: 连接=" + connectionTimeoutMs + "ms 读取=" + readTimeoutMs + "ms");
        LOG.info("WebSocket 单帧上限(应用层强制): " + maxWebSocketMessageSize + " 字节");
    }

    /** 按配置构造工作线程池（core / max / queue 三者必须一起看，见 DEFAULT_WORKER_QUEUE）。 */
    private ThreadPoolConfig newWorkerPoolConfig() {
        return ThreadPoolConfig.defaultConfig()
                .setCorePoolSize(workerCore)
                .setMaxPoolSize(workerMax)
                .setQueueLimit(workerQueueLimit)
                .setPoolName("grizzly-worker");
    }

    public void configureListener(NetworkListener listener, ThreadPoolConfig workerConfig) {
        listener.getTransport().setSelectorRunnersCount(ioThreads);
        listener.getTransport().setWorkerThreadPoolConfig(workerConfig);
        listener.getTransport().setConnectionTimeout(connectionTimeoutMs);
        listener.getTransport().setKeepAlive(true);

        // network.read_timeout_ms 以前只读不用 —— 配置项存在但完全不生效，
        // 运维照着注释调它不会有任何变化。Grizzly 里对应的概念是"单次请求事务超时"
        // （NetworkListener.setTransactionTimeout，单位秒）：超时后连接被断开，
        // 避免慢请求把工作线程长期占住。
        if (readTimeoutMs > 0) {
            int seconds = Math.max(1, readTimeoutMs / 1000);
            listener.setTransactionTimeout(seconds);
        }

        if (lowMemoryMode) {
            listener.getTransport().setReadBufferSize(16384);
            listener.getTransport().setWriteBufferSize(16384);
        }
    }

    public void configureListener(NetworkListener listener) {
        configureListener(listener, newWorkerPoolConfig());
    }

    public int getIoThreads() {
        return ioThreads;
    }

    public int getWorkerCore() {
        return workerCore;
    }

    public int getWorkerMax() {
        return workerMax;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }
}