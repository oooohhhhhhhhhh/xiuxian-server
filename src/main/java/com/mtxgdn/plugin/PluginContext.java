package com.mtxgdn.plugin;

import com.mtxgdn.common.ExperimentalConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.explorationevent.ExplorationEventRegistry;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.secretrealm.SecretRealmRegistry;
import com.mtxgdn.game.service.*;
import com.mtxgdn.plugin.event.PluginEvent;
import com.mtxgdn.plugin.event.PluginEventHandler;
import com.mtxgdn.plugin.event.PluginEventManager;
import com.mtxgdn.util.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.util.Properties;

/**
 * 插件运行上下文。插件通过此对象向服务端注册扩展内容、访问服务。
 * <p>
 * 这是插件开发者与服务端交互的主入口。
 */
public final class PluginContext {

    private final PluginInfo info;
    private final File dataFolder;
    private final ClassLoader classLoader;
    private final GameLogger log;

    PluginContext(PluginInfo info, File dataFolder, ClassLoader classLoader) {
        this.info = info;
        this.dataFolder = dataFolder;
        this.classLoader = classLoader;
        this.log = GameLogger.getLogger("Plugin[" + info.getName() + "]");
    }

    /** 获取插件元数据。 */
    public PluginInfo getInfo() { return info; }

    /** 获取插件专属数据目录（./plugins/{plugin_name}/）。 */
    public File getDataFolder() {
        if (!dataFolder.exists()) dataFolder.mkdirs();
        return dataFolder;
    }

    /** 获取当前插件的类加载器。 */
    public ClassLoader getClassLoader() { return classLoader; }

    /** 获取该插件专属的日志记录器。 */
    public GameLogger getLogger() { return log; }

    /**
     * 从插件 jar 包内部加载资源文件。
     * @param resourcePath jar 内部的资源路径，例如 "config.properties" 或 "lang/zh_cn.json"
     */
    public InputStream getResource(String resourcePath) {
        return classLoader.getResourceAsStream(resourcePath);
    }

    /**
     * 自动加载插件 jar 包中的翻译文件到全局 {@link LangManager}。
     * 扫描 jar 根目录下 lang/ 目录中的 .json 文件，按文件名推导语言代码。
     * 由 PluginManager 在插件加载后自动调用，插件开发者无需手动调用。
     */
    public void loadLang() {
        // 按约定：插件翻译文件位于 jar 内 lang/ 目录，如 lang/zh_cn.json
        String currentLang = LangManager.getLanguage();
        tryLoadLangFile(currentLang);
    }

    private void tryLoadLangFile(String lang) {
        String path = "lang/" + lang + ".json";
        try (InputStream is = getResource(path)) {
            if (is != null) {
                LangManager.merge(lang, is);
                log.debug("已加载插件翻译文件: " + path);
            }
        } catch (Exception e) {
            log.warn("加载插件翻译文件失败: " + path + " - " + e.getMessage());
        }
    }

    /**
     * 加载插件数据目录中的 properties 配置文件。
     * 若文件不存在则返回空的 Properties 对象。
     */
    public Properties loadConfig(String fileName) {
        Properties props = new Properties();
        File f = new File(getDataFolder(), fileName);
        if (f.exists()) {
            try (FileInputStream fis = new FileInputStream(f)) {
                props.load(fis);
            } catch (Exception e) {
                log.error("读取配置失败: " + fileName, e);
            }
        }
        return props;
    }

    // ================ 注册接口（给插件开发者使用） =================

    /** 注册一条命令。 */
    public void registerCommand(Command command) {
        CommandRegistry.register(command);
        log.debug("注册命令: " + command.getClass().getSimpleName());
    }

    /** 注册一个物品。 */
    public void registerItem(Item item) {
        ItemRegistry.register(item);
        log.debug("注册物品: " + item.getFullKey());
    }

    /**
     * 注册物品的能量转化值（供能量转化系统使用）。
     * 插件可以通过此接口为自己的物品设置自定义能量价值，
     * 无需修改物品本身的 price 属性。
     *
     * @param itemKey     物品完整键（如 "myplugin:custom_sword"）
     * @param energyValue 每个物品对应的能量值（必须大于0）
     */
    public void registerItemEnergy(String itemKey, long energyValue) {
        EnergyService.registerItemEnergy(itemKey, energyValue);
        log.debug("注册物品能量值: " + itemKey + " = " + energyValue);
    }

    /** 注册一个探索事件。 */
    public void registerExplorationEvent(ExplorationEvent event) {
        ExplorationEventRegistry.register(event);
        log.debug("注册探索事件: " + event.getFullKey());
    }

    /** 注册一个秘境。 */
    public void registerSecretRealm(SecretRealm realm) {
        SecretRealmRegistry.register(realm);
        log.debug("注册秘境: " + realm.getFullKey());
    }

    // ================ 事件处理器注册接口 =================

    /**
     * 注册一个事件处理器。
     * @param type 事件类型（例如 COMMAND、PLAYER_LOGIN 等）
     * @param condition 可选的触发条件表达式，格式如 "command=/你好"，为空表示始终触发
     * @param handler 事件处理器（lambda）
     */
    public void registerHandler(PluginEvent.Type type, String condition, PluginEventHandler handler) {
        PluginEventManager.getInstance().register(info.getName(), type, condition, handler);
    }

    /** 简化版：注册事件处理器，不设条件。 */
    public void registerHandler(PluginEvent.Type type, PluginEventHandler handler) {
        registerHandler(type, "", handler);
    }

    /** 注册自定义 key 的事件处理器（type 自动为 CUSTOM）。 */
    public void registerCustomHandler(String customKey, String condition, PluginEventHandler handler) {
        PluginEventManager.getInstance().registerCustom(info.getName(), customKey, condition, handler);
    }

    /** 切换本插件某类事件的启用状态。 */
    public void setHandlersEnabled(PluginEvent.Type type, boolean enabled) {
        PluginEventManager.getInstance().setEnabled(info.getName(), type, enabled);
    }

    /** 触发一个事件 —— 插件也可以主动触发事件供其他插件监听。 */
    public void fireEvent(PluginEvent event) {
        PluginEventManager.getInstance().fire(event);
    }

    // ================ 服务访问快捷方法 =================

    public PlayerService getPlayerService() { return ServiceRegistry.getPlayerService(); }
    public ItemService getItemService() { return ServiceRegistry.getItemService(); }
    public EconomyService getEconomyService() { return ServiceRegistry.getEconomyService(); }
    public CombatService getCombatService() { return ServiceRegistry.getCombatService(); }
    public SkillService getSkillService() { return ServiceRegistry.getSkillService(); }
    public DailyService getDailyService() { return ServiceRegistry.getDailyService(); }
    public ExplorationService getExplorationService() { return ServiceRegistry.getExplorationService(); }
    public SecretRealmService getSecretRealmService() { return ServiceRegistry.getSecretRealmService(); }
    public SectService getSectService() { return ServiceRegistry.getSectService(); }
    public TechniqueService getTechniqueService() { return ServiceRegistry.getTechniqueService(); }
    public CraftingService getCraftingService() { return ServiceRegistry.getCraftingService(); }
    public EnhanceService getEnhanceService() { return ServiceRegistry.getEnhanceService(); }
    public ChatService getChatService() { return ServiceRegistry.getChatService(); }
    public FriendService getFriendService() { return ServiceRegistry.getFriendService(); }
    public HeartDemonService getHeartDemonService() { return ServiceRegistry.getHeartDemonService(); }
    public TradeService getTradeService() { return ServiceRegistry.getTradeService(); }
    public NewbieGuideService getGuideService() { return ServiceRegistry.getGuideService(); }
    public RealmService getRealmService() { return ServiceRegistry.getRealmService(); }
    public EnergyService getEnergyService() { return ServiceRegistry.getEnergyService(); }

    // ================ 底层接口（供插件访问服务端基础设施） =================

    /**
     * 获取数据库连接（HikariCP 连接池）。
     * 插件可以使用此连接执行原生 SQL 查询。
     * 注意：用完后必须在 finally 块中 close() 归还连接。
     */
    public Connection getDatabaseConnection() throws java.sql.SQLException {
        return DatabaseManager.getConnection();
    }

    /** 获取服务端配置（application.yml）。 */
    public String getServerConfig(String key, String defaultValue) {
        return AppConfig.get(key, defaultValue);
    }

    /** 获取服务端配置（布尔值）。 */
    public boolean getServerConfigBoolean(String key, boolean defaultValue) {
        return AppConfig.getBoolean(key, defaultValue);
    }

    /** 获取服务端配置（整数值）。 */
    public int getServerConfigInt(String key, int defaultValue) {
        return AppConfig.getInt(key, defaultValue);
    }

    /**
     * 检查 API 调用频率限制。
     * @param key          频率限制的 key（建议使用 "plugin:<插件名>:<action>" 格式）
     * @param limit        时间窗口内允许的次数
     * @param windowSeconds 时间窗口（秒）
     * @return true 表示允许本次请求
     */
    public boolean checkRateLimit(String key, int limit, int windowSeconds) {
        return RateLimiter.allow(key, limit, windowSeconds);
    }

    /** 获取频率限制剩余次数。 */
    public long getRateLimitRemaining(String key, int limit, int windowSeconds) {
        return RateLimiter.getRemaining(key, limit, windowSeconds);
    }

    /**
     * 获取实验性功能配置。
     * @param key 功能键名（在 experimental.yml 中定义）
     * @return true 表示该功能已启用
     */
    public boolean isExperimentalFeatureEnabled(String key) {
        return ExperimentalConfig.isEnabled(key);
    }

    /** 获取实验性功能配置的值。 */
    public String getExperimentalFeatureValue(String key, String defaultValue) {
        return ExperimentalConfig.get(key, defaultValue);
    }

    /**
     * 获取 JWT 工具类实例，用于生成或验证 JWT token。
     */
    public JwtUtil getJwtUtil() {
        return new JwtUtil();
    }

    /** 获取玩家行为日志记录器。 */
    public PlayerActionLogger getPlayerActionLogger() {
        return PlayerActionLogger.getInstance();
    }

    /** 获取服务端全局统计收集器。 */
    public StatsCollector getStatsCollector() {
        return StatsCollector.getInstance();
    }

    /** 获取服务端日志记录器。 */
    public GameLogger getGlobalLogger() {
        return GameLogger.getLogger("Plugin[" + info.getName() + "]");
    }

    // ================ Web UI 接口（供插件添加管理界面） =================

    /**
     * 注册一个 JAX-RS REST 资源类。
     * 插件可以在自己的 jar 中定义带有 @Path 注解的 REST 资源类，
     * 通过此方法注册后即可通过 HTTP 访问。
     *
     * <pre>{@code
     * // 在插件 jar 中定义
     * // @Path("/game/myplugin")
     * // public class MyPluginResource { ... }
     *
     * // 在 onEnable 中注册
     * context.registerRestResource(MyPluginResource.class);
     * }</pre>
     */
    public void registerRestResource(Class<?> resourceClass) {
        PluginWebManager.getInstance().registerRestResource(info.getName(), resourceClass);
    }

    /**
     * 注册一个 HTML 页面到管理后台。
     * 页面将挂载在 /admin/plugins/{插件名}/{path} 下。
     *
     * @param path       页面路径，例如 "settings" 或 ""（首页）
     * @param title      页面标题，会在管理后台导航中显示
     * @param htmlContent HTML 页面内容
     */
    public void registerWebPage(String path, String title, byte[] htmlContent) {
        PluginWebManager.getInstance().registerWebPage(info.getName(), path, title, htmlContent);
    }

    /**
     * 从插件 jar 中注册一个完整的 Web 资源目录。
     * jar 内 resourceDir 下的所有文件（HTML/CSS/JS/图片等）
     * 都会被映射到 /admin/plugins/{插件名}/。
     *
     * 示例：插件 jar 中包含 webadmin/index.html，调用
     * registerWebResources("webadmin") 后，
     * 访问 http://127.0.0.1:8080/admin/plugins/MyPlugin/ 即可看到该页面。
     *
     * @param resourceDir jar 内的资源目录路径，如 "webadmin/"
     */
    public void registerWebResources(String resourceDir) {
        PluginWebManager.getInstance().registerWebResources(
                info.getName(), resourceDir, this.classLoader);
    }

    /**
     * 注册一个 WebSocket 消息处理器。
     * 当游戏客户端发送指定消息类型时，会调用此处理器。
     *
     * <pre>{@code
     * context.registerWebSocketHandler("myplugin_action", (socket, data) -> {
     *     String userId = data.get("userId").getAsString();
     *     // 处理消息...
     * });
     * }</pre>
     *
     * @param messageType 消息类型（JSON 中的 type 字段值）
     * @param handler     处理器 lambda
     */
    public void registerWebSocketHandler(String messageType,
                                          PluginWebManager.PluginWsHandler handler) {
        PluginWebManager.getInstance().registerWsHandler(info.getName(), messageType, handler);
    }
}
