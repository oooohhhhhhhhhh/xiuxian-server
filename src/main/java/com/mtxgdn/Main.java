package com.mtxgdn;

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
import com.mtxgdn.game.service.CraftingService;
import com.mtxgdn.game.service.EquipmentDataChecker;
import com.mtxgdn.game.service.SkillService;
import com.mtxgdn.game.service.TechniqueService;
import com.mtxgdn.adapter.AdapterManager;
import com.mtxgdn.minecraft.MinecraftMotdServer;
import com.mtxgdn.onebot.OneBotScreenshotBot;
import com.mtxgdn.onebot.OneBotWebSocketServer;
import com.mtxgdn.server.AdminStaticFileHandler;
import com.mtxgdn.server.ServerConfigurator;
import com.mtxgdn.server.ServerShutdownHook;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.MySqlLauncher;
import com.mtxgdn.websocket.GameWebSocketApp;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.websockets.WebSocketAddOn;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.mtxgdn.i18n.I18n;

public class Main {

    private static final GameLogger LOG = GameLogger.getLogger(Main.class);

    public static long serverStartTime;
    public static GameWebSocketApp gameWebSocketApp;
    public static HttpServer mainServer;
    private static MinecraftMotdServer motdServer;
    /** 启动参数（供安装向导自动重启时原样携带） */
    public static volatile String[] startupArgs = new String[0];

    public static void main(String[] args) throws Exception {
        startupArgs = args != null ? args : new String[0];
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");

        if (hasArg(startupArgs, "--plugin-make-gui")) {
            PluginMakerGUI.launch();
            return;
        }
        if (hasArg(startupArgs, "--plugin-make")) {
            new PluginMaker().run();
            return;
        }

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LOG.error("未捕获异常 线程=" + t.getName(), e);
        });

        registerShutdownHook();

        try {
            doMain(args);
        } catch (Exception e) {
            LOG.error("服务器启动失败", e);
            System.exit(1);
        }
    }

    private static void registerShutdownHook() {
        ServerShutdownHook hook = ServerShutdownHook.createDefault();
        hook.addTask(() -> AdapterManager.stopAll());
        hook.addTask(() -> {
            if (gameWebSocketApp != null) {
                gameWebSocketApp.shutdownGracefully();
            }
        });
        hook.addTask(() -> {
            if (motdServer != null) motdServer.stop();
        });
        hook.addTask(() -> {
            com.mtxgdn.game.service.TradeService.shutdown();
            LOG.info("坊市到期挂单回收与价格历史清理任务已停止");
        });
        hook.addTask(() -> {
            com.mtxgdn.game.service.EconomyService.shutdown();
            LOG.info("拍卖到期结算任务已停止");
        });
        hook.addTask(() -> {
            com.mtxgdn.playerdata.PlayerDataService.shutdown();
            LOG.info("玩家数据文件同步已完成");
        });
        hook.addTask(() -> {
            com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().disableAll(
                    com.mtxgdn.plugin.PluginContext.createSharedContext(
                            new File("data"), Main.class.getClassLoader()));
            LOG.info("内置玩法模块已停用");
        });
        hook.addTask(() -> {
            com.mtxgdn.db.DatabaseManager.shutdown();
            LOG.info("数据库连接池已关闭");
        });
        hook.register();
    }

    /**
     * 注册所有内置插件/玩法模块。
     * <p>清单集中在 {@link com.mtxgdn.plugin.PluginCatalog}，开关见 config/plugins.yml
     * （键 {@code plugins.<name>}，旧 application.yml 的 built_in_plugins.* 仅作只读兜底）。
     */
    private static void registerBuiltInPlugins(com.mtxgdn.plugin.BuiltInPluginRegistry registry) {
        // 清单集中登记（PluginCatalog），此处只负责注册，具体启用状态由 init() 读取开关文件决定
        registry.registerAll(com.mtxgdn.plugin.PluginCatalog.createAll());
    }

    private static void doMain(String[] args) throws Exception {
        boolean demo = hasArg(args, "--demo");
        boolean nogui = hasArg(args, "--nogui");

        serverStartTime = System.currentTimeMillis();
        LOG.info("服务器正在启动...");
        LOG.info("重启方式: " + restartMode() + "（restart.mode=" + AppConfig.get("restart.mode", "auto")
                + "，systemd/容器环境变量: INVOCATION_ID=" + (System.getenv("INVOCATION_ID") != null)
                + ", container=" + (System.getenv("container") != null) + "）");

        // JWT 密钥初始化（未完成安装时使用临时密钥，完成后强制要求配置）
        boolean installMode = !com.mtxgdn.rest.InstallResource.isInstalled();
        try {
            com.mtxgdn.util.JwtUtil.init(installMode);
        } catch (RuntimeException e) {
            LOG.error("JWT 密钥初始化失败: " + e.getMessage());
            LOG.error("请通过安装向导配置 jwt.secret，或手动编辑 config/application.yml");
            System.err.println("============================================================");
            System.err.println("[Main] " + e.getMessage());
            System.err.println("============================================================");
            System.exit(1);
        }

        initializeDatabase();

        LOG.info("正在初始化默认技能...");
        new SkillService().insertDefaultSkills();

        LOG.info("正在初始化默认功法...");
        new TechniqueService().insertDefaultTechniques();

        LOG.info("正在初始化默认配方...");
        new CraftingService().insertDefaultRecipes();

        LOG.info("正在扫描并注册物品...");
        ItemScanner.ScanResult scanResult = ItemScanner.scanAndRegister();
        LOG.info(scanResult.toString());

        LOG.info("正在检查装备数据一致性...");
        new EquipmentDataChecker().checkEquipmentData();

        LOG.info("正在扫描并注册秘境...");
        SecretRealmScanner.ScanResult realmScanResult = SecretRealmScanner.scanAndRegister();
        LOG.info(realmScanResult.toString());

        LOG.info("正在扫描并注册游历事件...");
        ExplorationEventScanner.ScanResult eventScanResult = ExplorationEventScanner.scanAndRegister();
        LOG.info(eventScanResult.toString());

        LOG.info("正在扫描并注册命令...");
        CommandScanner.ScanResult cmdScanResult = CommandScanner.scanAndRegister("com.mtxgdn.onebot.command");
        LOG.info(cmdScanResult.toString());

        LOG.info("正在加载语言包...");
        // 必须早于插件系统：PluginContext.loadLang() 会把插件自带的语言包合并进来，
        // 顺序反了会让内置基线覆盖插件词条（I18nManager 的 putAll 是后来者胜）。
        com.mtxgdn.i18n.I18nManager.load();

        LOG.info("正在初始化插件系统...");
        PluginManager pm = PluginManager.getInstance();
        pm.init(new File("plugins"));
        PluginManager.LoadResult pluginLoadResult = pm.loadPlugins();
        LOG.info(pluginLoadResult.toString());

        // 内置玩法模块：扫描 jar 内已注册的模块，读取配置决定启用/禁用
        LOG.info("正在初始化内置玩法模块...");
        com.mtxgdn.plugin.BuiltInPluginRegistry builtInRegistry =
                com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance();
        registerBuiltInPlugins(builtInRegistry);
        builtInRegistry.init();
        com.mtxgdn.plugin.PluginContext sharedPluginCtx =
                com.mtxgdn.plugin.PluginContext.createSharedContext(
                        new File("data"), Main.class.getClassLoader());
        builtInRegistry.enableAll(sharedPluginCtx);

        ResourceConfig config = new ResourceConfig().packages("com.mtxgdn.rest");
        // 监听地址与端口从配置读取（server.host / server.port）
        // S5 修复：默认绑定 127.0.0.1（仅本机），避免配置丢失/格式错误时回退到 0.0.0.0 公网全开
        String serverHost = AppConfig.get("server.host", "127.0.0.1");
        // 公网绑定时若 admin 凭据仍是默认 admin/admin123，发出强警告
        if (!"127.0.0.1".equals(serverHost) && !"localhost".equals(serverHost)
                && !"0:0:0:0:0:0:0:1".equals(serverHost) && !"::1".equals(serverHost)) {
            String adminPwdHash = AppConfig.get("admin.password_hash", "");
            String adminPwd = AppConfig.get("admin.password", "");
            if (adminPwdHash.isEmpty() && (adminPwd.isEmpty() || "admin123".equals(adminPwd))) {
                System.err.println("============================================================");
                System.err.println("[Main] ⚠ 严重安全警告: 服务绑定到公网 (" + serverHost + ") 但 admin 凭据未配置或仍为默认！");
                System.err.println("[Main] 请立即修改 admin.password_hash 并重启，或改回 127.0.0.1。");
                System.err.println("============================================================");
            }
        }
        int serverPort = AppConfig.getInt("server.port", 8080);
        // 重启场景：上一个实例可能尚未释放端口（Windows 上尤其明显），先等它退出再 bind
        waitForPortRelease(serverHost, serverPort);
        HttpServer server = GrizzlyHttpServerFactory.createHttpServer(
                URI.create("http://" + serverHost + ":" + serverPort + "/api/"), config);
        mainServer = server;

        PluginWebManager.getInstance().init(server, config);
        pm.enablePlugins();

        if (AppConfig.getBoolean("plugins.hot_swap", false)) {
            pm.startFileWatcher();
        }

        ServerConfigurator serverConfig = new ServerConfigurator();
        serverConfig.configure(server);

        gameWebSocketApp = new GameWebSocketApp();
        WebSocketEngine.getEngine().register("", "/", gameWebSocketApp);

        // 全局季节更替 → 全服广播（季节系统同步至全局）
        com.mtxgdn.game.service.SeasonService.setChangeListener(season -> {
            GameWebSocketApp app = gameWebSocketApp;
            if (app != null) {
                com.google.gson.JsonObject data = new com.google.gson.JsonObject();
                data.addProperty("season", season.name());
                data.addProperty("seasonDisplay", season.getDisplayName());
                data.addProperty("seasonDesc", season.getDescription());
                data.addProperty("message", I18n.t("main.h6e8e8544", season.getDisplayName(), season.getDescription()));
                app.broadcast(com.mtxgdn.common.GameMessage.ok(0, "season_change", data));
            }
        });

        LOG.info("服务已启动");
        LOG.info("API路由启动在 http://" + serverHost + ":" + serverPort + "/api/");
        LOG.info("WebSocket路由启动在 ws://" + serverHost + ":" + serverPort);

        // JWT 密钥安全检查：自动生成的临时密钥发出警告
        com.mtxgdn.util.JwtUtil.warnIfAutoGenerated();

        reportDeploymentPaths();

        releaseConfigs();

        // 启动所有适配器（从 adapters.json 读取配置）
        AdapterManager.setGameWebSocketApp(gameWebSocketApp);
        AdapterManager.startAll();

        server.getServerConfiguration().addHttpHandler(new AdminStaticFileHandler("/admin", "webadmin"), "/admin");
        LOG.info("管理控制台启动在 http://127.0.0.1:" + serverPort + "/admin/");

        // 首次启动（未完成安装：jwt.secret 为空）时挂载安装向导
        if (!com.mtxgdn.rest.InstallResource.isInstalled()) {
            server.getServerConfiguration().addHttpHandler(new AdminStaticFileHandler("/install", "install"), "/install");
            LOG.info("🔧 检测到首次启动，安装向导已就绪: http://127.0.0.1:" + serverPort + "/install");
            System.out.println("======================================================");
            System.out.println("  🔧 首次启动检测：尚未完成安装");
            System.out.println("  📖 请在浏览器打开安装向导: http://127.0.0.1:" + serverPort + "/install");
            System.out.println("  （配置管理员账号、数据库与 JWT 密钥，一键完成安装）");
            System.out.println("======================================================");
        }

        // 定期清理无界静态 Map（限流窗口 / Token 黑名单），防止长期运行内存泄漏
        java.util.concurrent.ScheduledExecutorService cleanupScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cleanup-scheduler");
                    t.setDaemon(true);
                    return t;
                });
        cleanupScheduler.scheduleAtFixedRate(() -> {
            try {
                com.mtxgdn.util.RateLimiter.cleanupExpired(3600);
            } catch (Throwable ignored) {
            }
            try {
                com.mtxgdn.util.TokenBlacklist.cleanupExpired(24L * 3600 * 1000);
            } catch (Throwable ignored) {
            }
            // 野外地图到期后换一批（内部只在"已过期/为空"时才真的重建，故可高频调用）
            try {
                com.mtxgdn.common.service.ServiceRegistry.getMapService().maintainWildernessIfNeeded();
            } catch (Throwable ignored) {
            }
        }, 5, 5, java.util.concurrent.TimeUnit.MINUTES);

        // 服务端就绪事件（插件可在 onEnable 注册 SERVER_READY 监听器）
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.SERVER_READY)
                        .data("serverTime", System.currentTimeMillis())
                        .build());

        motdServer = MinecraftMotdServer.create(
                MinecraftMotdServer.Config.builder()
                        .port(AppConfig.getInt("motd.port", 25565))
                        .maxPlayers(AppConfig.getInt("motd.max_players", 2026))
                        .versionName(AppConfig.get("motd.version_name", "§b§l修仙世界 §r§f— §eAPI v1.0"))
                        .protocolVersion(AppConfig.getInt("motd.protocol_version", -1))
                        .motdLine1(AppConfig.get("motd.line1", "§b§l修 仙 世 界 §r§8— §e§lXiuXian Game"))
                        .motdLine2(AppConfig.get("motd.line2", "§7"))
                        .motdLine3(AppConfig.get("motd.line3", "§f⚔ §a境界突破 §7| §d秘境探索 §7| §e游历修仙 §f⚔"))
                        .joinMessage(AppConfig.get("motd.join_message", ""))
                        .redirectServer(AppConfig.get("motd.redirect_server", ""))
                        .redirectDelaySeconds(AppConfig.getInt("motd.redirect_delay_seconds", 5))
                        .onlineCountProvider(() -> gameWebSocketApp != null ? gameWebSocketApp.getOnlineCount() : 0)
                        .build());
        motdServer.start();

        if (demo) {
            runDemoMode(motdServer, server);
            return;
        }

        if (nogui) {
            runNoGuiMode(motdServer, server);
            return;
        }

        runInteractiveMode(motdServer, server);
    }

    /**
     * 重启方式。
     *
     * <ul>
     *   <li>{@link #SUPERVISED}：由外部托管（systemd / 容器 / 进程守护面板）负责拉起 ——
     *       只做"干净退出"，绝不自我 spawn。systemd 默认 {@code KillMode=control-group}，
     *       主进程退出时会把 cgroup 里残留的子进程一起 SIGTERM 掉，自我 spawn 出来的
     *       新 JVM 必然被杀；而 {@code System.exit(0)} 又让 {@code Restart=on-failure}
     *       不触发，结果就是服务停在那里没人管。</li>
     *   <li>{@link #SELF_SPAWN}：裸跑（{@code java -jar} / start.sh / install.bat）——
     *       自己拉起新进程再退出。</li>
     * </ul>
     */
    public enum RestartMode { SUPERVISED, SELF_SPAWN }

    /**
     * 判断"重启"应该自我 spawn 还是退出等托管方拉起。
     *
     * <p>优先读 {@code restart.mode}：{@code auto}（默认）/ {@code supervised} /
     * {@code self-spawn}。自动模式按环境探测：systemd 注入 {@code INVOCATION_ID}、
     * 容器内存在 {@code /.dockerenv} 或 {@code /run/.containerenv}、或设置了
     * {@code container} 环境变量 → 视为托管。面板（MCSM 等）托管且未开自动重启时，
     * 自动模式会走自我 spawn（面板看不到 INVOCATION_ID），也可显式写 {@code self-spawn}。</p>
     */
    public static RestartMode restartMode() {
        String override = AppConfig.get("restart.mode", "auto");
        if (override != null) {
            String v = override.trim().toLowerCase();
            if ("supervised".equals(v) || "supervisor".equals(v)) {
                return RestartMode.SUPERVISED;
            }
            if ("self-spawn".equals(v) || "self_spawn".equals(v) || "spawn".equals(v)) {
                return RestartMode.SELF_SPAWN;
            }
        }
        // systemd 会给服务进程注入 INVOCATION_ID
        if (System.getenv("INVOCATION_ID") != null) {
            return RestartMode.SUPERVISED;
        }
        // Docker / Podman 等容器：进程退出后由容器 restart 策略拉起
        try {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get("/.dockerenv"))
                    || java.nio.file.Files.exists(java.nio.file.Paths.get("/run/.containerenv"))) {
                return RestartMode.SUPERVISED;
            }
        } catch (Throwable ignored) {
            // 文件系统不可读时按裸跑处理
        }
        if (System.getenv("container") != null) {
            return RestartMode.SUPERVISED;
        }
        return RestartMode.SELF_SPAWN;
    }

    /**
     * 自动重启服务器（安装向导配置完成后调用）。
     *
     * <p>托管模式（systemd / 容器）下只退出，由托管方拉起新实例；
     * 裸跑模式下以 {@code java -jar <当前jar> <原启动参数>} 启动新进程，
     * 然后<b>立即</b>退出释放端口 —— 新进程启动时会自己等端口释放
     * （见 {@link #waitForPortRelease}），因此不需要、也不能在这里等子进程就绪。</p>
     */
    public static void restartServer() {
        if (restartMode() == RestartMode.SUPERVISED) {
            LOG.info("重启方式：外部托管（systemd / 容器）—— 退出当前进程，由托管方拉起新实例");
            LOG.info("提示：若服务没有自动回来，请确认 systemd 单元为 Restart=always、或容器已配置 restart 策略");
            System.exit(0);
            return;
        }
        try {
            String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator
                    + (System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
            String classPath = System.getProperty("java.class.path", "");
            String jarPath = null;
            for (String entry : classPath.split(java.io.File.pathSeparator)) {
                if (entry.endsWith(".jar")) {
                    jarPath = entry;
                    break;
                }
            }
            if (jarPath == null) {
                LOG.error("无法定位可执行 jar，请手动重启服务器");
                return;
            }
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaBin);
            // 保留当前 JVM 参数（-Xmx / -Xss / -Dfile.encoding 等），否则重启后堆大小会掉回默认值；
            // 调试代理除外：新进程不该去抢同一个调试端口
            for (String jvmArg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (jvmArg.startsWith("-agentlib") || jvmArg.startsWith("-Xrunjdwp")) {
                    continue;
                }
                cmd.add(jvmArg);
            }
            cmd.add("-jar");
            cmd.add(jarPath);
            for (String arg : startupArgs) {
                cmd.add(arg);
            }
            LOG.info("正在自动重启: " + String.join(" ", cmd));
            new ProcessBuilder(cmd).inheritIO().start();
            // 不再轮询"新进程是否就绪"：端口还握在自己手里，子进程不可能就绪，
            // 那样的探测只会命中当前进程自己（永远"成功"）。新进程会等端口释放后再 bind。
            LOG.info("已拉起新进程，当前进程退出并释放端口");
            System.exit(0);
        } catch (Exception e) {
            LOG.error("自动重启失败，请手动重启服务器", e);
        }
    }

    /**
     * 等端口释放后再 bind。
     *
     * <p>重启时父进程 spawn 完新进程就退出，新进程可能比父进程更快走到 bind；
     * 在 Windows 上这会直接 {@code Address already in use}，把"重启"变成"停机"。
     * 这里最多等 15 秒，端口空闲就立刻返回。</p>
     */
    private static void waitForPortRelease(String host, int port) {
        java.net.InetAddress addr;
        try {
            addr = "0.0.0.0".equals(host) ? null : java.net.InetAddress.getByName(host);
        } catch (Exception e) {
            return; // 地址解析失败交给后面真正的 bind 报错
        }
        long deadline = System.currentTimeMillis() + 15_000L;
        boolean logged = false;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
                probe.setReuseAddress(false);
                probe.bind(new java.net.InetSocketAddress(addr, port), 1);
                if (logged) {
                    LOG.info("端口 " + host + ":" + port + " 已释放，继续启动");
                }
                return;
            } catch (Exception e) {
                if (!logged) {
                    LOG.info("端口 " + host + ":" + port + " 仍被占用（上一个实例正在退出），最多等待 15 秒…");
                    logged = true;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        LOG.warn("等待端口 " + host + ":" + port + " 释放超时（15s），继续尝试启动");
    }


    private static void reportDeploymentPaths() {        java.nio.file.Path jarDir = AppConfig.getJarDir().toAbsolutePath();
        String cwd = System.getProperty("user.dir", ".");
        LOG.info("=== 部署路径 ===");
        LOG.info("JAR 目录: " + jarDir);
        LOG.info("工作目录: " + cwd);
        LOG.info("配置文件目录: " + jarDir.resolve("config"));
        LOG.info("数据库: " + AppConfig.get("database.type", "mysql") + "（"
                + ("sqlite".equalsIgnoreCase(AppConfig.get("database.type", "mysql"))
                        ? AppConfig.get("database.sqlite_path", "xiuxian.db")
                        : AppConfig.get("database.url", "jdbc:mysql://localhost:3306/xiuxian")) + "）");
        LOG.info("玩家数据目录: " + AppConfig.get("player-data.dir", "data/players"));
        LOG.info("日志目录: " + AppConfig.get("logging.dir", "log"));
        LOG.info("插件目录: plugins");
        if (!java.nio.file.Paths.get(cwd).toAbsolutePath().equals(jarDir)) {
            LOG.warn("工作目录与 JAR 目录不一致：config/adapters.json、数据库文件、data/、log/、plugins/ 等相对路径"
                    + "会落在工作目录（" + cwd + "）。建议从 JAR 所在目录启动，或（Linux systemd）设置"
                    + "WorkingDirectory=" + jarDir);
        }
        LOG.info("======================");
    }

    private static void initializeDatabase() {
        String dbType = AppConfig.get("database.type", "mysql");
        if ("sqlite".equalsIgnoreCase(dbType)) {
            String sqlitePath = AppConfig.get("database.sqlite_path", "xiuxian.db");
            String dbUrl = "jdbc:sqlite:" + sqlitePath;
            LOG.info("数据库类型: SQLite");
            LOG.info("数据库文件: " + dbUrl);
            DatabaseManager.init(dbUrl, "", "");
        } else {
            String dbUrl = AppConfig.get("database.url", "jdbc:mysql://localhost:3306/xiuxian");
            String dbUser = AppConfig.get("database.username", "root");
            String dbPassword = AppConfig.get("database.password", "root");
            boolean isLocalDb = isLocalDatabase(dbUrl);
            if (isLocalDb) {
                MySqlLauncher.ensureMySqlRunning();
            } else {
                LOG.info("数据库类型: MySQL (远程地址)");
                LOG.info("数据库URL: " + dbUrl);
                LOG.info("跳过本地MySQL启动，请确保远程MySQL服务已运行");
            }
            DatabaseManager.init(dbUrl, dbUser, dbPassword);
        }

        // V4 修复：把 DB 中历史吊销的 token 载入内存缓存，
        // 否则重启前登出的 token 会在内存缓存为空时被误判为有效。
        try {
            com.mtxgdn.util.TokenBlacklist.loadFromDatabase();
        } catch (Exception e) {
            LOG.warn("载入 JWT 吊销名单失败（登出记录在本次启动内可能不生效）: " + e.getMessage());
        }
    }

    private static void releaseConfigs() {
        LOG.info("正在释放配置文件到 " + AppConfig.getJarDir().resolve("config").toAbsolutePath() + " ...");
        try {
            // 插件开关独立配置文件：初始化即触发模板释放与旧键迁移
            java.util.List<String> pluginNames = com.mtxgdn.plugin.PluginCatalog.createAll().stream()
                    .map(com.mtxgdn.plugin.BuiltInPlugin::getName)
                    .collect(java.util.stream.Collectors.toList());
            com.mtxgdn.plugin.PluginSwitches.migrateLegacyKeys(pluginNames);
            LOG.info("  plugins.yml 已加载: " + com.mtxgdn.plugin.PluginSwitches.getConfigPath().toAbsolutePath());
        } catch (Exception e) {
            LOG.warn("  plugins.yml 加载失败: " + e.getMessage());
        }
        try {
            GameConfigLoader.extractConfigs();
            LOG.info("  realm_config.json 已释放");
        } catch (Exception ignored) {
            LOG.warn("  realm_config.json 释放失败");
        }
    }

    private static void runDemoMode(MinecraftMotdServer motdServer, HttpServer server) throws Exception {
        LOG.info("============================================");
        LOG.info("  Demo 模式已激活，等待服务就绪...");
        LOG.info("============================================");
        waitForServerReady();
        LOG.info("服务就绪，启动交互式客户端...");
        DemoClient.main(new String[0]);
        AdapterManager.stopAll();
        if (motdServer != null) motdServer.stop();
        server.shutdownNow();
    }

    private static void runNoGuiMode(MinecraftMotdServer motdServer, HttpServer server) throws Exception {
        LOG.info("无GUI模式，后台运行中...");
        synchronized (Main.class) {
            Main.class.wait();
        }
    }

    private static void runInteractiveMode(MinecraftMotdServer motdServer, HttpServer server) throws Exception {
        LOG.info("Web 管理控制台: http://127.0.0.1:8080/admin/");
        LOG.info("按 Enter 键关闭服务器...");
        waitForEnter();
        AdapterManager.stopAll();
        if (motdServer != null) motdServer.stop();
        server.shutdownNow();
    }

    private static void waitForEnter() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            reader.readLine();
        } catch (Exception e) {
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

    private static boolean isLocalDatabase(String dbUrl) {
        if (dbUrl == null) {
            return true;
        }
        String host = extractHostFromUrl(dbUrl);
        if (host == null) {
            return true;
        }
        return host.equalsIgnoreCase("localhost")
                || host.equalsIgnoreCase("127.0.0.1")
                || host.equalsIgnoreCase("0:0:0:0:0:0:0:1")
                || host.equalsIgnoreCase("::1");
    }

    private static String extractHostFromUrl(String dbUrl) {
        if (dbUrl.startsWith("jdbc:mysql://")) {
            String urlPart = dbUrl.substring("jdbc:mysql://".length());
            int colonIdx = urlPart.indexOf(':');
            int slashIdx = urlPart.indexOf('/');
            int questionIdx = urlPart.indexOf('?');

            int endIdx = Math.min(
                    Math.min(colonIdx > 0 ? colonIdx : Integer.MAX_VALUE,
                            slashIdx > 0 ? slashIdx : Integer.MAX_VALUE),
                    questionIdx > 0 ? questionIdx : Integer.MAX_VALUE);

            if (endIdx > 0 && endIdx < Integer.MAX_VALUE) {
                return urlPart.substring(0, endIdx);
            } else if (!urlPart.isEmpty()) {
                return urlPart;
            }
        }
        return null;
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