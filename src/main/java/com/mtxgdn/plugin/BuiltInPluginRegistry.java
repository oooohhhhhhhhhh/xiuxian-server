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
        for (BuiltInPluginEntry entry : resolveEnableOrder()) {
            try {
                entry.module.onLoad(sharedContext);
                entry.module.onEnable(sharedContext);
                registerProvidedServices(entry.module);
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
     * 算出本次实际要启用的模块，按依赖顺序排列。
     *
     * <p>与配置的分工：{@code enabled} 表达"配置说它该开"，{@code active} 表达"它真的开起来了"。
     * 本方法<b>不修改</b> {@code enabled} —— 一个模块因为依赖没到而没启用，
     * 不该被顺手写进 plugins.yml 变成永久关闭；下次依赖修好后它应该能自己恢复。</p>
     *
     * <p>顺序由 {@link PluginDependencyResolver} 负责（与外部插件共用同一份拓扑排序与环检测），
     * 这里只多做一件事：把"依赖了未启用模块"的模块提前剔除，以给出精确原因
     * （拓扑排序只会说"缺少必需插件"，而这里能说"依赖的内置模块未启用"）。</p>
     */
    private List<BuiltInPluginEntry> resolveEnableOrder() {
        // 1. 依赖了"已注册但未启用"或"根本没注册"的模块 → 剔除，并做传递闭包
        Map<String, String> blocked = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BuiltInPluginEntry entry : modules.values()) {
                String name = entry.module.getName();
                if (!entry.enabled || blocked.containsKey(name)) continue;
                for (String dep : entry.module.getDependencies()) {
                    String reason = null;
                    if (dep == null || dep.isEmpty()) continue;
                    BuiltInPluginEntry d = modules.get(dep);
                    if (d == null) {
                        reason = "依赖的内置模块未注册: " + dep;
                    } else if (!d.enabled || blocked.containsKey(dep)) {
                        reason = "依赖的内置模块未启用: " + dep;
                    }
                    if (reason != null) {
                        blocked.put(name, reason);
                        changed = true;
                        break;
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : blocked.entrySet()) {
            LOG.error("内置模块不启用: " + e.getKey() + " —— " + e.getValue()
                    + "（模块开关保持原值，修好依赖后重启即可恢复）");
        }

        // 2. 剩下的交给统一解析器排序（顺带兜住环）
        List<PluginInfo> infos = new ArrayList<>();
        for (BuiltInPluginEntry entry : modules.values()) {
            if (!entry.enabled || blocked.containsKey(entry.module.getName())) continue;
            BuiltInPlugin m = entry.module;
            infos.add(new PluginInfo(m.getName(), m.getVersion(), "", "", "",
                    new ArrayList<>(m.getDependencies()),
                    new ArrayList<>(m.getSoftDependencies())));
        }
        PluginDependencyResolver.Plan plan = PluginDependencyResolver.resolve(infos);
        for (Map.Entry<String, String> e : plan.getRejected().entrySet()) {
            LOG.error("内置模块不启用: " + e.getKey() + " —— " + e.getValue());
        }

        List<BuiltInPluginEntry> order = new ArrayList<>();
        for (String name : plan.getLoadOrder()) {
            BuiltInPluginEntry entry = modules.get(name);
            if (entry != null) order.add(entry);
        }
        return order;
    }

    /**
     * 判断某模块当前是否可以启用（依赖都已就绪）。
     *
     * @return 不可启用的原因；可以启用时返回 {@code null}
     */
    public String getDependencyBlockReason(String name) {
        BuiltInPluginEntry entry = modules.get(name);
        if (entry == null) return "模块未注册";
        for (String dep : entry.module.getDependencies()) {
            BuiltInPluginEntry d = modules.get(dep);
            if (d == null) return "依赖的内置模块未注册: " + dep;
            if (!d.active) return "依赖的内置模块未启用: " + dep;
        }
        return null;
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
                unregisterProvidedServices(entry.module);
                entry.active = false;
            } catch (Throwable t) {
                LOG.error("内置插件停用异常: " + entry.module.getName(), t);
            }
        }
    }

    /**
     * 把模块声明提供的能力登记到全局服务表，归属标注为模块名。
     *
     * <p>归属必须在注册表侧标注：内置模块共用一个 {@code PluginContext}，
     * 靠 context 无法区分"是谁在登记"，一旦按 context 的 "server" 归属，
     * 停用任意一个模块就会把所有内置模块的服务一起注销。</p>
     */
    private void registerProvidedServices(BuiltInPlugin module) {
        java.util.Map<Class<?>, Object> provided = module.getProvidedServices();
        if (provided == null || provided.isEmpty()) return;
        for (java.util.Map.Entry<Class<?>, Object> e : provided.entrySet()) {
            PluginServiceRegistry.getInstance().provide(module.getName(), e.getKey(), e.getValue());
        }
    }

    /** 注销模块提供的能力。 */
    private void unregisterProvidedServices(BuiltInPlugin module) {
        PluginServiceRegistry.getInstance().unregisterAll(module.getName());
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
            // 依赖关系与"为什么没起来"：WebUI 排查时最需要的就是这两项
            status.put("dependencies", new ArrayList<>(entry.module.getDependencies()));
            status.put("softDependencies", new ArrayList<>(entry.module.getSoftDependencies()));
            String blocked = entry.active ? null : getDependencyBlockReason(entry.module.getName());
            status.put("blockedReason", blocked == null ? "" : blocked);
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
        String blocked = getDependencyBlockReason(name);
        if (blocked != null) {
            LOG.warn("内置插件未能启用: " + name + " —— " + blocked);
            return false;
        }
        enableEntry(entry);
        if (entry.active) {
            persistConfig(name, true);
            com.mtxgdn.audit.AuditService.getInstance().plugin(name,
                    com.mtxgdn.audit.AuditActions.PLUGIN_ENABLE, "内置模块启用");
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
        com.mtxgdn.audit.AuditService.getInstance().plugin(name,
                com.mtxgdn.audit.AuditActions.PLUGIN_DISABLE, "内置模块停用");
        return true;
    }

    /**
     * 列出仍然启用、且硬依赖指定模块的模块名。
     *
     * <p>停用时不连锁停用依赖方：一次手动操作带走半个服，比"某个功能悄悄少了"
     * 更难善后。依赖方会被 {@link #getDependencyBlockReason} 挡在下一次启用之外，
     * 所以不会带病运行。</p>
     */
    public List<String> getActiveDependents(String name) {
        List<String> dependents = new ArrayList<>();
        for (BuiltInPluginEntry entry : modules.values()) {
            if (!entry.active) continue;
            if (entry.module.getName().equals(name)) continue;
            if (entry.module.getDependencies().contains(name)) dependents.add(entry.module.getName());
        }
        return dependents;
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
            registerProvidedServices(entry.module);
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
        unregisterProvidedServices(entry.module);
        entry.enabled = false;
        entry.active = false;
        LOG.info("内置插件已停用: " + entry.module.getName());
        List<String> dependents = getActiveDependents(entry.module.getName());
        if (!dependents.isEmpty()) {
            LOG.warn("模块 " + entry.module.getName() + " 已停用，但下列模块仍依赖它: " + dependents
                    + "（不会连锁停用；它们会在下次启用时因依赖不满足而被拒绝）");
        }
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
