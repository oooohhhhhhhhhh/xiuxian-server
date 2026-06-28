package com.mtxgdn;

import com.mtxgdn.common.ExperimentalConfig;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.demo.DemoClient;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.item.ItemScanner;
import com.mtxgdn.game.explorationevent.ExplorationEventScanner;
import com.mtxgdn.game.secretrealm.SecretRealmScanner;
import com.mtxgdn.common.command.CommandScanner;
import com.mtxgdn.plugin.PluginManager;
import com.mtxgdn.plugin.PluginMaker;
import com.mtxgdn.plugin.PluginWebManager;
import com.mtxgdn.plugin.gui.PluginMakerGUI;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.minecraft.MinecraftMotdServer;
import com.mtxgdn.minecraft.adapter.MinecraftAdapter;
import com.mtxgdn.onebot.OneBotScreenshotBot;
import com.mtxgdn.onebot.OneBotWebSocketServer;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.MySqlLauncher;
import com.mtxgdn.websocket.GameWebSocketApp;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.grizzly.websockets.WebSocketAddOn;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Main {

    private static final GameLogger LOG = GameLogger.getLogger(Main.class);

    public static long serverStartTime;
    public static GameWebSocketApp gameWebSocketApp;
    public static OneBotWebSocketServer oneBotWebSocketServer;
    public static OneBotScreenshotBot screenshotBot;
    public static MinecraftAdapter minecraftAdapter;
    public static HttpServer oneBotServer;
    public static HttpServer mainServer;

    public static void main(String[] args) throws Exception {
        // 第一时间强制设置 UTF-8，避免 Windows 上中文字符串/文件路径显示乱码
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        // 插件生成工具模式（GUI）：检测到 --plugin-make-gui 时，显示图形界面，不启动服务端
        if (hasArg(args, "--plugin-make-gui")) {
            PluginMakerGUI.launch();
            return;
        }
        // 插件生成工具模式：检测到 --plugin-make 时，不启动服务端，直接运行交互式向导
        if (hasArg(args, "--plugin-make")) {
            new PluginMaker().run();
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOG.error("未捕获异常 线程=" + t.getName(), e);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("服务器正在优雅关闭...");
            try {
                PluginManager.getInstance().disablePlugins();
            } catch (Exception ignore) {
            }
            if (oneBotWebSocketServer != null) {
                oneBotWebSocketServer.shutdown();
            }
            if (screenshotBot != null) {
                screenshotBot.stop();
            }
            if (minecraftAdapter != null) {
                minecraftAdapter.stop();
            }
            if (gameWebSocketApp != null) {
                try {
                    gameWebSocketApp.shutdownGracefully();
                } catch (Exception ignore) {
                }
            }
            LOG.info("服务器已关闭");
        }, "shutdown-hook"));

        try {
            doMain(args);
        } catch (Exception e) {
            LOG.error("服务器启动失败", e);
            System.exit(1);
        }
    }

    private static void doMain(String[] args) throws Exception {
        boolean demo = hasArg(args, "--demo");
        boolean nogui = hasArg(args, "--nogui");

        serverStartTime = System.currentTimeMillis();

        LOG.info("服务器正在启动...");

        String dbType = AppConfig.get("database.type", "mysql");
        if (!"sqlite".equalsIgnoreCase(dbType)) {
            MySqlLauncher.ensureMySqlRunning();
        } else {
            LOG.info("数据库类型: SQLite (无需启动MySQL)");
        }

        DatabaseManager.initTable();

        LOG.info("正在初始化默认技能...");
        new SkillService().insertDefaultSkills();

        LOG.info("正在初始化默认功法...");
        new TechniqueService().insertDefaultTechniques();

        LOG.info("正在初始化默认配方...");
        new CraftingService().insertDefaultRecipes();

        LOG.info("正在扫描并注册物品...");
        ItemScanner.ScanResult scanResult = ItemScanner.scanAndRegister();
        LOG.info(scanResult.toString());

        LOG.info("正在扫描并注册秘境...");
        SecretRealmScanner.ScanResult realmScanResult = SecretRealmScanner.scanAndRegister();
        LOG.info(realmScanResult.toString());

        LOG.info("正在扫描并注册游历事件...");
        ExplorationEventScanner.ScanResult eventScanResult = ExplorationEventScanner.scanAndRegister();
        LOG.info(eventScanResult.toString());

        LOG.info("正在扫描并注册命令...");
        CommandScanner.ScanResult cmdScanResult = CommandScanner.scanAndRegister("com.mtxgdn.onebot.command");
        LOG.info(cmdScanResult.toString());

        LOG.info("正在初始化插件系统...");
        PluginManager pm = PluginManager.getInstance();
        pm.init(new File("plugins"));
        PluginManager.LoadResult pluginLoadResult = pm.loadPlugins();
        LOG.info(pluginLoadResult.toString());

        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");

        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
                URI.create("http://0.0.0.0:8080/api/"), config);
        mainServer = server;

        // 初始化插件 Web 管理器（必须在插件启用之前完成）
        PluginWebManager.getInstance().init(server, config);

        // 在 Web 基础设施就绪后再启用插件（插件在 onEnable 中注册 Web 资源）
        pm.enablePlugins();

        // 低内存模式：缩小 Grizzly 线程池和缓冲区
        if (AppConfig.getBoolean("performance.low_memory", true)) {
            int grizzlyIoThreads = AppConfig.getInt("performance.grizzly_io_threads", 1);
            int grizzlyWorkerCore = AppConfig.getInt("performance.grizzly_worker_cores", 1);
            int grizzlyWorkerMax = AppConfig.getInt("performance.grizzly_worker_max", 2);
            ThreadPoolConfig workerConfig = ThreadPoolConfig.defaultConfig()
                    .setCorePoolSize(grizzlyWorkerCore)
                    .setMaxPoolSize(grizzlyWorkerMax)
                    .setQueueLimit(50)
                    .setPoolName("grizzly-worker");
            for (NetworkListener listener : server.getListeners()) {
                listener.getTransport().setSelectorRunnersCount(grizzlyIoThreads);
                listener.getTransport().setWorkerThreadPoolConfig(workerConfig);
                // 缩小 IO 缓冲区 (默认 64KB → 16KB)
                listener.getTransport().setReadBufferSize(16384);
                listener.getTransport().setWriteBufferSize(16384);
            }
            LOG.info("低内存模式: Grizzly IO线程=" + grizzlyIoThreads +
                    " 工作线程(" + grizzlyWorkerCore + "/" + grizzlyWorkerMax +
                    ") 缓冲区=16KB");
        }

        WebSocketAddOn wsAddOn = new WebSocketAddOn();
        server.getListeners().forEach(listener -> listener.registerAddOn(wsAddOn));

        gameWebSocketApp = new GameWebSocketApp();
        WebSocketEngine.getEngine().register("", "/", gameWebSocketApp);

        LOG.info("服务已启动");
        LOG.info("API路由启动在 http://127.0.0.1:8080/api/");
        LOG.info("WebSocket路由启动在 ws://127.0.0.1:8080");

        // 服务器启动后，主动释放配置文件到 jar 所在目录的 config/ 下
        LOG.info("正在释放配置文件到 " + AppConfig.getJarDir().resolve("config").toAbsolutePath() + " ...");
        try {
            ExperimentalConfig.get("_dummy", "");
            LOG.info("  experimental.yml 已加载");
        } catch (Exception ignored) {
            LOG.warn("  experimental.yml 加载失败");
        }
        try {
            GameConfigLoader.extractConfigs();
            LOG.info("  realm_config.json 已释放");
        } catch (Exception ignored) {
            LOG.warn("  realm_config.json 释放失败");
        }

        int oneBotPort = AppConfig.getInt("onebot.port", 6700);
        boolean oneBotEnabled = AppConfig.getBoolean("onebot.enabled", true);
        String oneBotMode = AppConfig.get("onebot.mode", "ws_server");

        if (oneBotEnabled) {
            if ("screenshot".equalsIgnoreCase(oneBotMode)) {
                LOG.info("正在启动 OneBot 截图模式...");
                screenshotBot = new OneBotScreenshotBot();
                screenshotBot.start();
                LOG.info("OneBot 截图模式已启动");
            } else {
                try {
                    LOG.info("正在启动 OneBot WebSocket 服务...");

                    oneBotServer = new HttpServer();
                    NetworkListener oneBotListener = new NetworkListener("onebot", "0.0.0.0", oneBotPort);
                    oneBotListener.registerAddOn(new WebSocketAddOn());
                    oneBotServer.addListener(oneBotListener);

                    oneBotWebSocketServer = new OneBotWebSocketServer();
                    WebSocketEngine.getEngine().register("", "/onebot", oneBotWebSocketServer);

                    oneBotServer.start();
                    LOG.info("OneBot WebSocket 服务启动在 ws://127.0.0.1:" + oneBotPort + "/onebot");
                } catch (Exception e) {
                    LOG.error("OneBot WebSocket 服务启动失败", e);
                }
            }
        }

        boolean mcAdapterEnabled = AppConfig.getBoolean("minecraft.adapter.enabled", false);
        if (mcAdapterEnabled) {
            try {
                LOG.info("正在启动 Minecraft 适配器...");
                minecraftAdapter = new MinecraftAdapter();
                boolean mcStarted = minecraftAdapter.start();
                if (mcStarted) {
                    LOG.info("Minecraft 适配器已启动");
                } else {
                    LOG.warn("Minecraft 适配器启动失败，请检查 minecraft.jar_path 配置");
                }
            } catch (Exception e) {
                LOG.error("Minecraft 适配器启动失败", e);
            }
        }

        // 自定义静态文件处理器，确保 index.html 能正常访问
        HttpHandler adminHandler = new HttpHandler() {
            @Override
            public void service(Request request, Response response) throws Exception {
                String path = request.getHttpHandlerPath();
                if (path == null || path.isEmpty() || path.equals("/")) {
                    path = "/index.html";
                }
                String resourcePath = "webadmin" + path;
                InputStream in = Main.class.getClassLoader().getResourceAsStream(resourcePath);
                if (in == null) {
                    response.setStatus(404);
                    response.setContentType("text/plain; charset=UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.getWriter().write("404 Not Found");
                    return;
                }
                // 根据扩展名设置 Content-Type
                if (path.endsWith(".html") || path.endsWith(".htm")) {
                    response.setContentType("text/html; charset=UTF-8");
                } else if (path.endsWith(".css")) {
                    response.setContentType("text/css; charset=UTF-8");
                } else if (path.endsWith(".js")) {
                    response.setContentType("application/javascript; charset=UTF-8");
                } else if (path.endsWith(".json")) {
                    response.setContentType("application/json; charset=UTF-8");
                } else if (path.endsWith(".png")) {
                    response.setContentType("image/png");
                } else if (path.endsWith(".svg")) {
                    response.setContentType("image/svg+xml");
                } else {
                    response.setContentType("text/plain; charset=UTF-8");
                }
                response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                response.setCharacterEncoding("UTF-8");
                OutputStream os = response.getOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                in.close();
                os.flush();
            }
        };
        server.getServerConfiguration().addHttpHandler(adminHandler, "/admin");
        LOG.info("管理控制台启动在 http://127.0.0.1:8080/admin/");

        MinecraftMotdServer motdServer = MinecraftMotdServer.create(
                MinecraftMotdServer.Config.builder()
                        .port(25565)
                        .onlineCountProvider(() -> gameWebSocketApp != null ? gameWebSocketApp.getOnlineCount() : 0)
                        .build());
        motdServer.start();

        if (demo) {
            LOG.info("============================================");
            LOG.info("  Demo 模式已激活，等待服务就绪...");
            LOG.info("============================================");
            waitForServerReady();
            LOG.info("服务就绪，启动交互式客户端...");
            DemoClient.main(new String[0]);
            motdServer.stop();
            if (screenshotBot != null) screenshotBot.stop();
            if (minecraftAdapter != null) minecraftAdapter.stop();
            if (oneBotServer != null) oneBotServer.shutdownNow();
            server.shutdownNow();
            return;
        }

        if (nogui) {
            LOG.info("无GUI模式，按 Enter 键关闭服务器...");
            waitForEnter();
            if (oneBotWebSocketServer != null) oneBotWebSocketServer.shutdown();
            motdServer.stop();
            if (screenshotBot != null) screenshotBot.stop();
            if (minecraftAdapter != null) minecraftAdapter.stop();
            if (oneBotServer != null) oneBotServer.shutdownNow();
            server.shutdownNow();
            return;
        }

        LOG.info("Web 管理控制台: http://127.0.0.1:8080/admin/");
        LOG.info("按 Enter 键关闭服务器...");
        waitForEnter();
        if (oneBotWebSocketServer != null) oneBotWebSocketServer.shutdown();
        motdServer.stop();
        if (screenshotBot != null) screenshotBot.stop();
        if (minecraftAdapter != null) minecraftAdapter.stop();
        if (oneBotServer != null) oneBotServer.shutdownNow();
        server.shutdownNow();
    }

    private static void waitForEnter() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            reader.readLine();
        } catch (Exception e) {
            // 忽略异常
        }
    }

    private static boolean hasArg(String[] args, String target) {
        for (String arg : args) {
            if (target.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void waitForServerReady() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        for (int i = 0; i < 30; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:8080/api/game/status"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 || resp.statusCode() == 401) {
                    LOG.info("服务就绪 (尝试 " + (i + 1) + " 次, status=" + resp.statusCode() + ")");
                    return;
                }
            } catch (ConnectException e) {
                LOG.info("等待服务启动... (" + (i + 1) + "/30)");
            } catch (Exception e) {
                LOG.info("等待中... (" + (i + 1) + "/30) " + e.getMessage());
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        LOG.warn("服务就绪检查超时，强制启动客户端");
    }
}
