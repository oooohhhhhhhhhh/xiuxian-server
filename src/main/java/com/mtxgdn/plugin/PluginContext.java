package com.mtxgdn.plugin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
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
import com.mtxgdn.util.GameLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
}
