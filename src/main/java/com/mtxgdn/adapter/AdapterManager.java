package com.mtxgdn.adapter;

import com.mtxgdn.minecraft.MinecraftMotdServer;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.minecraft.adapter.MinecraftPlayerAdapter;
import com.mtxgdn.onebot.OneBotScreenshotBot;
import com.mtxgdn.onebot.OneBotWebSocketServer;
import com.mtxgdn.qq.QqOfficialAdapter;
import com.mtxgdn.server.ServerConfigurator;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.websocket.GameWebSocketApp;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.websockets.WebSocketAddOn;
import org.glassfish.grizzly.websockets.WebSocketEngine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的适配器生命周期管理器。
 * 负责根据 AdapterConfigManager 配置启动/停止/重载所有适配器实例。
 */
public class AdapterManager {

    private static final GameLogger LOG = GameLogger.getLogger(AdapterManager.class);

    // 运行中的适配器实例
    private static final Map<String, Object> runningAdapters = new ConcurrentHashMap<>();

    // 第三方适配器工厂（外部插件通过 PluginContext.registerAdapterFactory 注册）
    private static final Map<String, AdapterFactory> adapterFactories = new ConcurrentHashMap<>();

    // 服务器启动完成后置 true：此后内置适配器插件（AdapterChannelPlugin）的启停才真正作用于通道
    private static volatile boolean bootStarted = false;

    // 外部引用
    private static GameWebSocketApp gameWebSocketApp;
    private static MinecraftMotdServer motdServer;

    private AdapterManager() {
    }

    /**
     * 根据 adapters.json 启动所有 enabled 的适配器。
     */
    public static void startAll() {
        AdapterConfigManager.load();

        for (AdapterConfig cfg : AdapterConfigManager.getAdapters()) {
            if (cfg.isEnabled()) {
                startOne(cfg);
            }
        }
        bootStarted = true;
    }

    /** 服务器是否已完成启动（启动阶段之后，内置适配器插件的启停才对通道生效）。 */
    public static boolean isBootStarted() {
        return bootStarted;
    }

    /**
     * 启动某类型下所有 enabled 的适配器实例（内置适配器插件启用时调用）。
     */
    public static synchronized void startType(String type) {
        if (!isTypeAllowed(type)) {
            return;
        }
        for (AdapterConfig cfg : AdapterConfigManager.getAdapters()) {
            if (type.equalsIgnoreCase(cfg.getType()) && cfg.isEnabled()
                    && !runningAdapters.containsKey(cfg.getId())) {
                startOne(cfg);
            }
        }
    }

    /**
     * 停止某类型下所有运行中的适配器实例（内置适配器插件停用时调用）。
     */
    public static synchronized void stopType(String type) {
        for (AdapterConfig cfg : AdapterConfigManager.getAdapters()) {
            if (type.equalsIgnoreCase(cfg.getType())) {
                stopOne(cfg.getId());
            }
        }
    }

    /**
     * 第三方插件注册自定义适配器类型。
     * 与内置适配器类型同名或重复注册时拒绝，防止外部插件覆盖内置通道。
     *
     * @return 注册成功返回 true；类型无效/内置类型/重复注册返回 false
     */
    public static boolean registerAdapterFactory(AdapterFactory factory) {
        if (factory == null || factory.getType() == null || factory.getType().isBlank()) {
            LOG.warn("注册适配器工厂失败：类型不能为空");
            return false;
        }
        String type = factory.getType();
        if (isBuiltInAdapterType(type)) {
            LOG.warn("适配器类型 " + type + " 为内置类型，忽略第三方注册");
            return false;
        }
        if (adapterFactories.putIfAbsent(type, factory) != null) {
            LOG.warn("适配器类型 " + type + " 已由其它插件注册，忽略重复注册");
            return false;
        }
        LOG.info("已注册第三方适配器类型: " + type);
        return true;
    }

    /**
     * 第三方插件注销自定义适配器类型（同时停止该类型下运行中的实例）。
     */
    public static void unregisterAdapterType(String type) {
        stopType(type);
        if (adapterFactories.remove(type) != null) {
            LOG.info("已注销第三方适配器类型: " + type);
        }
    }

    /** 判断某适配器类型对应的内置插件是否允许运行（未注册为内置插件的类型一律放行）。 */
    private static boolean isTypeAllowed(String type) {
        return com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown(type);
    }

    /** 内置适配器类型集合（adapters.json 中的 type 与插件页开关一一对应）。 */
    private static boolean isBuiltInAdapterType(String type) {
        return switch (type) {
            case "onebot", "qq", "minecraft", "minecraft-player" -> true;
            default -> false;
        };
    }

    /**
     * 启动单个适配器实例。
     */
    public static synchronized boolean startOne(AdapterConfig cfg) {
        String id = cfg.getId();
        if (runningAdapters.containsKey(id)) {
            LOG.warn("适配器 " + id + " 已在运行中");
            return false;
        }

        // 类型级插件开关：built_in_plugins.<type>=false 时整个通道不可启动
        if (!isTypeAllowed(cfg.getType())) {
            LOG.warn("适配器类型 " + cfg.getType() + " 已停用（内置插件开关关闭），跳过启动: " + id);
            return false;
        }

        try {
            switch (cfg.getType()) {
                case "onebot" -> startOneBot(cfg);
                case "qq" -> startQqOfficial(cfg);
                case "minecraft" -> startMinecraft(cfg);
                case "minecraft-player" -> startMinecraftPlayer(cfg);
                default -> {
                    AdapterFactory factory = adapterFactories.get(cfg.getType());
                    if (factory == null) {
                        LOG.warn("未知适配器类型: " + cfg.getType());
                        return false;
                    }
                    ManagedAdapter instance = factory.create(cfg);
                    instance.start(cfg);
                    runningAdapters.put(cfg.getId(), instance);
                }
            }
            LOG.info("适配器 " + id + " (" + cfg.getType() + ") 已启动");
            return true;
        } catch (Exception e) {
            LOG.error("适配器 " + id + " 启动失败", e);
            return false;
        }
    }

    /**
     * 停止单个适配器实例。
     */
    public static synchronized void stopOne(String adapterId) {
        Object instance = runningAdapters.remove(adapterId);
        if (instance == null) {
            return;
        }

        try {
            switch (instance) {
                case OneBotWebSocketServer ws -> {
                    ws.shutdown();
                    cleanupOneBotServer(adapterId);
                }
                case OneBotScreenshotBot bot -> bot.stop();
                case QqOfficialAdapter qq -> qq.stop();
                case MinecraftAdapter mc -> mc.stop();
                case MinecraftPlayerAdapter mcp -> mcp.stop();
                case ManagedAdapter ma -> ma.stop();
                default -> LOG.warn("未知实例类型: " + instance.getClass().getSimpleName());
            }
            LOG.info("适配器 " + adapterId + " 已停止");
        } catch (Exception e) {
            LOG.error("适配器 " + adapterId + " 停止失败", e);
        }
    }

    /**
     * 停止所有适配器（用于服务器关闭）。
     */
    public static void stopAll() {
        for (String id : runningAdapters.keySet()) {
            try {
                stopOne(id);
            } catch (Exception e) {
                LOG.error("停止适配器 " + id + " 失败", e);
            }
        }
        runningAdapters.clear();
    }

    /**
     * 重载适配器配置：对比新旧配置，停止已删除/禁用的，启动新增/启用的。
     */
    public static synchronized void reloadAll() {
        reloadAll(AdapterConfigManager.getConfig().copy());
    }

    /**
     * 重载适配器配置（使用指定的旧配置进行对比）。
     * 用于 API 调用场景：外部已经在更新前捕获了旧配置。
     */
    public static synchronized void reloadAll(AdaptersConfig oldConfig) {
        AdaptersConfig newConfig = AdapterConfigManager.reload();

        Map<String, AdapterConfig> oldMap = new ConcurrentHashMap<>();
        for (AdapterConfig ac : oldConfig.getAdapters()) {
            oldMap.put(ac.getId(), ac);
        }

        Map<String, AdapterConfig> newMap = new ConcurrentHashMap<>();
        for (AdapterConfig ac : newConfig.getAdapters()) {
            newMap.put(ac.getId(), ac);
        }

        // 停止已删除或已禁用的适配器
        for (Map.Entry<String, AdapterConfig> entry : oldMap.entrySet()) {
            String id = entry.getKey();
            AdapterConfig oldCfg = entry.getValue();
            AdapterConfig newCfg = newMap.get(id);

            boolean shouldStop = false;
            if (newCfg == null) {
                // 已删除
                shouldStop = true;
            } else if (oldCfg.isEnabled() && !newCfg.isEnabled()) {
                // 已禁用
                shouldStop = true;
            } else if (oldCfg.isEnabled() && newCfg.isEnabled() && !oldCfg.equals(newCfg)) {
                // 配置变更：先停后启
                shouldStop = true;
            }

            if (shouldStop) {
                stopOne(id);
            }
        }

        // 启动新增或变更的适配器
        for (Map.Entry<String, AdapterConfig> entry : newMap.entrySet()) {
            String id = entry.getKey();
            AdapterConfig newCfg = entry.getValue();
            AdapterConfig oldCfg = oldMap.get(id);

            if (!newCfg.isEnabled()) {
                continue;
            }

            if (oldCfg == null) {
                // 新增
                startOne(newCfg);
            } else if (!oldCfg.isEnabled() && newCfg.isEnabled()) {
                // 从禁用变为启用
                startOne(newCfg);
            } else if (!oldCfg.equals(newCfg) && oldCfg.isEnabled()) {
                // 配置变更，重新启动
                startOne(newCfg);
            }
        }
    }

    /**
     * 获取运行中的适配器状态列表。
     */
    public static Map<String, Boolean> getStatus() {
        Map<String, Boolean> status = new ConcurrentHashMap<>();
        for (AdapterConfig cfg : AdapterConfigManager.getAdapters()) {
            status.put(cfg.getId(), runningAdapters.containsKey(cfg.getId()));
        }
        return status;
    }

    /**
     * 检查适配器是否运行中。
     */
    public static boolean isRunning(String adapterId) {
        return runningAdapters.containsKey(adapterId);
    }

    // ==================== 外部引用注入 ====================

    public static void setGameWebSocketApp(GameWebSocketApp app) {
        gameWebSocketApp = app;
    }

    public static void setMotdServer(MinecraftMotdServer server) {
        motdServer = server;
    }

    // ==================== OneBot 启动 ====================

    private static void startOneBot(AdapterConfig cfg) throws Exception {
        String mode = cfg.getString("mode", "ws_server");

        if ("screenshot".equalsIgnoreCase(mode)) {
            OneBotScreenshotBot bot = new OneBotScreenshotBot();
            bot.start();
            runningAdapters.put(cfg.getId(), bot);
            return;
        }

        int port = cfg.getInt("port", 6700);
        // 默认只监听回环地址：OneBot 客户端通常与服务器同机部署。
        // 需要远程接入时必须显式配置 host 与 access_token。
        String host = cfg.getString("host", "127.0.0.1");
        String accessToken = cfg.getString("access_token", "");
        if (accessToken == null) {
            accessToken = "";
        }
        if (!isLoopbackHost(host) && accessToken.isBlank()) {
            throw new IllegalStateException("适配器 " + cfg.getId()
                    + " 监听在非本机地址 " + host + " 但未配置 access_token，拒绝启动"
                    + "（OneBot 通道无鉴权等同于把全部指令暴露给网络）");
        }

        HttpServer server = new HttpServer();
        NetworkListener listener = new NetworkListener("onebot-" + cfg.getId(), host, port);
        listener.registerAddOn(new WebSocketAddOn());

        ServerConfigurator serverConfig = new ServerConfigurator();
        serverConfig.configureListener(listener);

        server.addListener(listener);

        OneBotWebSocketServer wsServer = new OneBotWebSocketServer(accessToken);
        WebSocketEngine.getEngine().register("", "/onebot", wsServer);

        server.start();
        runningAdapters.put(cfg.getId(), wsServer);
        // 保存 server 引用用于后续关闭
        runningAdapters.put(cfg.getId() + ".server", server);
        // 保存 engine 引用用于反注册，避免热重载时残留映射与调度线程
        runningAdapters.put(cfg.getId() + ".engine", wsServer);

        LOG.info("OneBot WebSocket: ws://" + host + ":" + port + "/onebot"
                + (accessToken.isBlank() ? "（仅本机，未启用 access_token）" : "（已启用 access_token）"));
    }

    private static boolean isLoopbackHost(String host) {
        return host == null || host.isBlank()
                || host.startsWith("127.") || host.equals("::1") || host.equalsIgnoreCase("localhost");
    }

    private static void cleanupOneBotServer(String adapterId) {
        Object serverObj = runningAdapters.remove(adapterId + ".server");
        if (serverObj instanceof HttpServer server) {
            server.shutdownNow();
        }
        Object engineObj = runningAdapters.remove(adapterId + ".engine");
        if (engineObj instanceof OneBotWebSocketServer wsServer) {
            // 先从 WebSocketEngine 反注册，否则重载后旧的 application 仍然占着 /onebot 映射
            try {
                WebSocketEngine.getEngine().unregister(wsServer);
            } catch (RuntimeException e) {
                LOG.warn("反注册 OneBot WebSocket 失败: " + e.getMessage());
            }
        }
    }

    // ==================== QQ 官方适配器 ====================

    private static void startQqOfficial(AdapterConfig cfg) {
        QqOfficialAdapter adapter = new QqOfficialAdapter();
        adapter.start(cfg);
        runningAdapters.put(cfg.getId(), adapter);
    }

    // ==================== Minecraft 启动 ====================

    private static void startMinecraft(AdapterConfig cfg) {
        MinecraftAdapter adapter = new MinecraftAdapter();
        adapter.start();
        runningAdapters.put(cfg.getId(), adapter);
    }

    // ==================== Minecraft Player 启动 ====================

    private static void startMinecraftPlayer(AdapterConfig cfg) {
        MinecraftPlayerAdapter adapter = new MinecraftPlayerAdapter(cfg);
        adapter.start();
        runningAdapters.put(cfg.getId(), adapter);
    }

    // ==================== 兼容旧 Main.java 的引用 ====================

    /**
     * 查找第一个指定类型的运行中适配器。
     */
    public static OneBotWebSocketServer getFirstOneBotWsServer() {
        for (Object instance : runningAdapters.values()) {
            if (instance instanceof OneBotWebSocketServer ws) {
                return ws;
            }
        }
        return null;
    }

    public static OneBotScreenshotBot getFirstScreenshotBot() {
        for (Object instance : runningAdapters.values()) {
            if (instance instanceof OneBotScreenshotBot bot) {
                return bot;
            }
        }
        return null;
    }

    public static QqOfficialAdapter getFirstQqOfficialAdapter() {
        for (Object instance : runningAdapters.values()) {
            if (instance instanceof QqOfficialAdapter qq) {
                return qq;
            }
        }
        return null;
    }

    public static MinecraftAdapter getFirstMinecraftAdapter() {
        for (Object instance : runningAdapters.values()) {
            if (instance instanceof MinecraftAdapter mc) {
                return mc;
            }
        }
        return null;
    }

    public static MinecraftPlayerAdapter getFirstMinecraftPlayerAdapter() {
        for (Object instance : runningAdapters.values()) {
            if (instance instanceof MinecraftPlayerAdapter mcp) {
                return mcp;
            }
        }
        return null;
    }
}
