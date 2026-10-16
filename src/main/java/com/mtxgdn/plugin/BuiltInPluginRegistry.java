package com.mtxgdn.plugin;

import com.mtxgdn.util.GameLogger;

import java.util.*;

/**
 * 内置插件注册表。管理 jar 内打包的内置玩法模块。
 * <p>
 * 与外部插件（./plugins/*.jar）不同，内置插件直接编译在主 jar 中，
 * 通过 {@code config/plugins.yml}（根节点 {@code plugins}，键 {@code plugins.&lt;name&gt;}）
 * 配置启用/禁用；旧 {@code application.yml} 的 {@code built_in_plugins.*} 与旧
 * {@code config/experimental.yml} 的 {@code experimental.*} 作为只读兜底，并在首次
 * 启动时自动迁移到 {@code plugins.yml}。
 * <p>
 * 使用方式（Main.doMain 中）：
 * <pre>
 *   BuiltInPluginRegistry registry = BuiltInPluginRegistry.getInstance();
 *   registry.registerAll(PluginCatalog.createAll());  // 注册模块（清单见 PluginCatalog）
 *   registry.init();                                  // 读取配置、启用/禁用
 *   registry.enableAll(sharedContext);                 // 调用 onEnable
 * </pre>
 */
public final class BuiltInPluginRegistry {

    private static final GameLogger LOG = GameLogger.getLogger(BuiltInPluginRegistry.class);

    private final Map<String, BuiltInPluginEntry> modules = new LinkedHashMap<>();
    private boolean initialized = false;
    private PluginContext sharedContext;

    /**
     * 注册一个内置插件模块。
     * 必须在 {@link #init()} 之前调用。
     */
    public void register(BuiltInPlugin module) {
        String name = module.getName();
        if (modules.containsKey(name)) {
            LOG.warn("内置插件重复注册: " + name);
            return;
        }
        modules.put(name, new BuiltInPluginEntry(module));
        LOG.debug("已注册内置插件: " + name);
    }

    /**
     * 批量注册多个内置插件。
     */
    public void registerAll(BuiltInPlugin... pluginList) {
        for (BuiltInPlugin p : pluginList) {
            register(p);
        }
    }

    /** 批量注册多个内置插件。 */
    public void registerAll(Collection<? extends BuiltInPlugin> pluginList) {
        for (BuiltInPlugin p : pluginList) {
            register(p);
        }
    }

    /**
     * 初始化：读取 {@code config/plugins.yml}，决定每个模块是否启用。
     * <p>
     * 取值优先级：plugins.yml → 旧键只读兜底（built_in_plugins.* / experimental.*）→
     * 插件自身 {@link BuiltInPlugin#getDefaultEnabled()}。
     */
    public void init() {
        if (initialized) return;
        // 首次启动把旧 application.yml / experimental.yml 的开关键迁移到 plugins.yml
        List<String> names = new ArrayList<>();
        for (BuiltInPluginEntry entry : modules.values()) {
            names.add(entry.module.getName());
        }
        PluginSwitches.migrateLegacyKeys(names);
        if (PluginSwitches.hasLegacyKeys()) {
            LOG.info("检测到旧插件开关配置（built_in_plugins.* / experimental.*），"
                    + "已迁移到 " + PluginSwitches.getConfigPath() + "；旧键保留为只读兜底");
        }
        for (BuiltInPluginEntry entry : modules.values()) {
            String name = entry.module.getName();
            String configKey = PluginSwitches.KEY_PREFIX + name.toLowerCase();
            PluginSwitches.Resolved resolved =
                    PluginSwitches.resolve(name, entry.module.getDefaultEnabled());
            entry.enabled = resolved.isEnabled();
            entry.configSource = resolved.getSource();
            if (!entry.enabled) {
                LOG.info("内置插件已禁用（配置 " + configKey + "=false）: " + name);
            } else if (resolved.getSource() == PluginSwitches.Source.LEGACY) {
                LOG.info("内置插件开关来自旧配置兜底（建议迁移到 " + PluginSwitches.CONFIG_FILE + "）: " + name);
            }
        }
        initialized = true;
    }

    /**
     * 启用所有已启用的模块（调用 onLoad + onEnable）。
     * 需要在 ServiceRegistry 初始化之后、PluginManager 之前调用。
     */
    public void enableAll(PluginContext sharedContext) {
        this.sharedContext = sharedContext;
        if (!initialized) init();
        int enabled = 0;
        for (BuiltInPluginEntry entry : modules.values()) {
            if (!entry.enabled) continue;
            try {
                entry.module.onLoad(sharedContext);
                entry.module.onEnable(sharedContext);
                entry.active = true;
                enabled++;
                LOG.info("✓ 内置插件已启用: " + entry.module.getName() + " v" + entry.module.getVersion());
            } catch (Throwable t) {
                LOG.error("内置插件启用失败: " + entry.module.getName(), t);
            }
        }
        LOG.info("内置插件初始化完成: " + enabled + "/" + modules.size() + " 个已启用"
                + "（开关文件 " + PluginSwitches.getConfigPath().getFileName() + "）");
    }

    /**
     * 停用所有已启用的模块（服务器关闭时调用）。
     */
    public void disableAll(PluginContext sharedContext) {
        this.sharedContext = sharedContext;
        for (BuiltInPluginEntry entry : modules.values()) {
            if (!entry.active) continue;
            try {
                entry.module.onDisable(sharedContext);
                entry.active = false;
            } catch (Throwable t) {
                LOG.error("内置插件停用异常: " + entry.module.getName(), t);
            }
        }
    }

    /** 获取所有模块的状态列表。 */
    public List<Map<String, Object>> getStatuses() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BuiltInPluginEntry entry : modules.values()) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("name", entry.module.getName());
            status.put("version", entry.module.getVersion());
            status.put("description", entry.module.getDescription());
            status.put("category", entry.module.getCategory());
            status.put("enabled", entry.enabled);
            status.put("active", entry.active);
            // 开关落盘位置（WebUI 提示用）：新配置文件 / 旧配置兜底 / 插件默认值
            status.put("configKey", PluginSwitches.KEY_PREFIX + entry.module.getName().toLowerCase());
            status.put("configSource", entry.configSource == null
                    ? PluginSwitches.Source.DEFAULT.name().toLowerCase()
                    : entry.configSource.name().toLowerCase());
            result.add(status);
        }
        return result;
    }

    /** 获取指定模块是否启用。 */
    public boolean isEnabled(String name) {
        BuiltInPluginEntry entry = modules.get(name);
        return entry != null && entry.active;
    }

    /**
     * 判断模块当前是否处于启用（运行中）状态，用于指令/接口的运行时门控。
     * 未注册的模块名一律视为启用，避免把未知模块误判为“已停用”而永久封禁其命令。
     */
    public boolean isEnabledOrUnknown(String name) {
        BuiltInPluginEntry entry = modules.get(name);
        return entry == null || entry.active;
    }

    /** 获取所有已注册模块的名称。 */
    public Set<String> getRegisteredNames() {
        return Collections.unmodifiableSet(modules.keySet());
    }

    // ==================== 运行时启停（WebUI 管理页使用） ====================

    /**
     * 运行时启用单个内置插件：立即调用 onLoad + onEnable，
     * 并把 {@code plugins.&lt;name&gt;} 持久化到 config/plugins.yml（重启后仍保持启用）。
     *
     * @return 模块存在且成功启用返回 true；模块不存在或已经启用返回 false
     */
    public synchronized boolean enablePlugin(String name) {
        BuiltInPluginEntry entry = modules.get(name);
        if (entry == null) {
            LOG.warn("内置插件不存在: " + name);
            return false;
        }
        if (entry.active) {
            LOG.warn("内置插件已启用: " + name);
            return false;
        }
        enableEntry(entry);
        if (entry.active) {
            persistConfig(name, true);
            return true;
        }
        return false;
    }

    /**
     * 运行时停用单个内置插件：立即调用 onDisable，
     * 并把 {@code plugins.&lt;name&gt;} 持久化到 config/plugins.yml（重启后仍保持停用）。
     *
     * @return 模块存在且成功停用返回 true；模块不存在或未启用返回 false
     */
    public synchronized boolean disablePlugin(String name) {
        BuiltInPluginEntry entry = modules.get(name);
        if (entry == null) {
            LOG.warn("内置插件不存在: " + name);
            return false;
        }
        if (!entry.active) {
            LOG.warn("内置插件未启用: " + name);
            return false;
        }
        disableEntry(entry);
        persistConfig(name, false);
        return true;
    }

    private void enableEntry(BuiltInPluginEntry entry) {
        if (sharedContext == null) {
            LOG.warn("内置插件共享上下文未初始化，仅更新状态: " + entry.module.getName());
            entry.enabled = true;
            entry.active = true;
            return;
        }
        // 先置为启用再调用 onLoad/onEnable：onEnable 期间（如适配器通道启动实例、注册指令）
        // 会查询自身 isEnabledOrUnknown 门控，此时必须已处于启用态才能放行
        entry.enabled = true;
        entry.active = true;
        try {
            entry.module.onLoad(sharedContext);
            entry.module.onEnable(sharedContext);
            LOG.info("✓ 内置插件已启用: " + entry.module.getName() + " v" + entry.module.getVersion());
        } catch (Throwable t) {
            entry.enabled = false;
            entry.active = false;
            LOG.error("内置插件启用失败: " + entry.module.getName(), t);
        }
    }

    private void disableEntry(BuiltInPluginEntry entry) {
        try {
            entry.module.onDisable(sharedContext);
        } catch (Throwable t) {
            LOG.error("内置插件停用异常: " + entry.module.getName(), t);
        }
        entry.enabled = false;
        entry.active = false;
        LOG.info("内置插件已停用: " + entry.module.getName());
    }

    /**
     * 将内置插件的启用状态持久化到 config/plugins.yml（键: plugins.&lt;name&gt;）。
     * 持久化失败不影响本次运行时状态，仅记录日志。
     */
    private void persistConfig(String name, boolean enabled) {
        BuiltInPluginEntry entry = modules.get(name);
        if (entry != null) {
            // 已落盘到新配置文件，之后不再依赖旧配置兜底
            entry.configSource = PluginSwitches.Source.CONFIG_FILE;
        }
        String key = PluginSwitches.KEY_PREFIX + name.toLowerCase();
        try {
            PluginSwitches.set(name, enabled);
            LOG.info("插件开关已持久化: " + key + "=" + enabled
                    + "（文件 " + PluginSwitches.getConfigPath() + "）");
        } catch (Throwable t) {
            LOG.error("插件开关持久化失败（本次变更仅内存生效）: " + name, t);
        }
    }

    // ==================== 内部类 ====================

    private static final class BuiltInPluginEntry {
        final BuiltInPlugin module;
        boolean enabled = true;
        boolean active = false;
        PluginSwitches.Source configSource = PluginSwitches.Source.DEFAULT;

        BuiltInPluginEntry(BuiltInPlugin module) {
            this.module = module;
        }
    }

    // ==================== 便捷方法 ====================

    /** 获取全局单例。 */
    private static final BuiltInPluginRegistry INSTANCE = new BuiltInPluginRegistry();
    public static BuiltInPluginRegistry getInstance() { return INSTANCE; }

    /**
     * 判断指定内置模块当前是否可用（静态便捷入口，供指令/接口做门控）。
     * <p>未注册的模块名一律视为可用，避免把未知模块误判为“已停用”。
     */
    public static boolean isModuleEnabled(String name) {
        return INSTANCE.isEnabledOrUnknown(name);
    }

    /** 判断指定模块是否已注册。 */
    public static boolean isRegistered(String name) {
        return INSTANCE.modules.containsKey(name);
    }
}
