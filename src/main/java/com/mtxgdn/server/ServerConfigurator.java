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
        this.connectionTimeoutMs = AppConfig.getInt("network.connection_timeout_ms", 10000);
        this.readTimeoutMs = AppConfig.getInt("network.read_timeout_ms", 30000);
        this.lowMemoryMode = AppConfig.getBoolean("performance.low_memory", false);
        this.maxWebSocketMessageSize = getMaxWebSocketMessageSize();
    }

    public void configure(HttpServer server) {
        ThreadPoolConfig workerConfig = ThreadPoolConfig.defaultConfig()
                .setCorePoolSize(workerCore)
                .setMaxPoolSize(workerMax)
                .setQueueLimit(100)
                .setPoolName("grizzly-worker");

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
                " 工作线程(" + workerCore + "/" + workerMax + ")");
        LOG.info("网络超时: 连接=" + connectionTimeoutMs + "ms 读取=" + readTimeoutMs + "ms");
        LOG.info("WebSocket 单帧上限(应用层强制): " + maxWebSocketMessageSize + " 字节");
    }

    public void configureListener(NetworkListener listener, ThreadPoolConfig workerConfig) {
        listener.getTransport().setSelectorRunnersCount(ioThreads);
        listener.getTransport().setWorkerThreadPoolConfig(workerConfig);
        listener.getTransport().setConnectionTimeout(connectionTimeoutMs);
        listener.getTransport().setKeepAlive(true);

        if (lowMemoryMode) {
            listener.getTransport().setReadBufferSize(16384);
            listener.getTransport().setWriteBufferSize(16384);
        }
    }

    public void configureListener(NetworkListener listener) {
        ThreadPoolConfig workerConfig = ThreadPoolConfig.defaultConfig()
                .setCorePoolSize(workerCore)
                .setMaxPoolSize(workerMax)
                .setQueueLimit(100)
                .setPoolName("grizzly-worker");
        configureListener(listener, workerConfig);
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