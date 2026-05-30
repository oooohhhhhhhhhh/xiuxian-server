package com.mtxgdn;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.demo.DemoClient;
import com.mtxgdn.game.item.ItemScanner;
import com.mtxgdn.game.explorationevent.ExplorationEventScanner;
import com.mtxgdn.game.secretrealm.SecretRealmScanner;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.minecraft.MinecraftMotdServer;
import com.mtxgdn.onebot.OneBotWebSocketServer;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.MySqlLauncher;
import com.mtxgdn.websocket.GameWebSocketApp;
import org.glassfish.grizzly.http.server.CLStaticHttpHandler;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.websockets.WebSocketAddOn;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

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
    public static HttpServer oneBotServer;

    public static void main(String[] args) throws Exception {
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

        LOG.info("正在扫描并注册物品...");
        ItemScanner.ScanResult scanResult = ItemScanner.scanAndRegister();
        LOG.info(scanResult.toString());

        LOG.info("正在扫描并注册秘境...");
        SecretRealmScanner.ScanResult realmScanResult = SecretRealmScanner.scanAndRegister();
        LOG.info(realmScanResult.toString());

        LOG.info("正在扫描并注册游历事件...");
        ExplorationEventScanner.ScanResult eventScanResult = ExplorationEventScanner.scanAndRegister();
        LOG.info(eventScanResult.toString());

        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");

        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
                URI.create("http://0.0.0.0:8080/api/"), config);

        WebSocketAddOn wsAddOn = new WebSocketAddOn();
        server.getListeners().forEach(listener -> listener.registerAddOn(wsAddOn));

        gameWebSocketApp = new GameWebSocketApp();
        WebSocketEngine.getEngine().register("", "/", gameWebSocketApp);

        LOG.info("服务已启动");
        LOG.info("API路由启动在 http://127.0.0.1:8080/api/");
        LOG.info("WebSocket路由启动在 ws://127.0.0.1:8080");

        int oneBotPort = AppConfig.getInt("onebot.port", 6700);
        boolean oneBotEnabled = AppConfig.getBoolean("onebot.enabled", true);

        if (oneBotEnabled) {
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

        CLStaticHttpHandler adminHandler = new CLStaticHttpHandler(Main.class.getClassLoader(), "/webadmin/");
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
            if (oneBotServer != null) oneBotServer.shutdownNow();
            server.shutdownNow();
            return;
        }

        if (nogui) {
            LOG.info("无GUI模式，按 Enter 键关闭服务器...");
            System.in.read();
            motdServer.stop();
            if (oneBotServer != null) oneBotServer.shutdownNow();
            server.shutdownNow();
            return;
        }

        LOG.info("Web 管理控制台: http://127.0.0.1:8080/admin/");
        LOG.info("按 Enter 键关闭服务器...");
        System.in.read();
        motdServer.stop();
        if (oneBotServer != null) oneBotServer.shutdownNow();
        server.shutdownNow();
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
