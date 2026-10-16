package com.mtxgdn.plugin.event;

import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件事件总线（单例）。
 * <p>
 * 负责：
 * <ul>
 *   <li>注册/取消事件处理器</li>
 *   <li>分发事件到所有匹配的处理器</li>
 *   <li>管理自定义事件 key 的处理器列表</li>
 * </ul>
 * <p>
 * 此管理器是线程安全的，允许在任何时刻注册和触发事件。
 */
public final class PluginEventManager {

    private static final GameLogger LOG = GameLogger.getLogger(PluginEventManager.class);
    private static final PluginEventManager INSTANCE = new PluginEventManager();

    /** 按类型索引的处理器列表（支持多处理器订阅同一事件） */
    private final Map<PluginEvent.Type, List<HandlerEntry>> handlersByType = new EnumMap<>(PluginEvent.Type.class);
    /** 自定义 key -> 处理器列表（用于 CUSTOM 事件的细粒度订阅） */
    private final Map<String, List<HandlerEntry>> handlersByKey = new java.util.concurrent.ConcurrentHashMap<>();
    /** 全局所有处理器（用于统计/调试） */
    private final List<HandlerEntry> allHandlers = new CopyOnWriteArrayList<>();

    private PluginEventManager() {
        for (PluginEvent.Type t : PluginEvent.Type.values()) {
            handlersByType.put(t, new CopyOnWriteArrayList<>());
        }
    }

    public static PluginEventManager getInstance() { return INSTANCE; }

    // ==================== 注册 API ====================

    /** 默认优先级 */
    public static final int PRIORITY_NORMAL = 0;
    /** 最高优先级 */
    public static final int PRIORITY_HIGHEST = Integer.MAX_VALUE;
    /** 最低优先级 */
    public static final int PRIORITY_LOWEST = Integer.MIN_VALUE;

    /**
     * 订阅指定类型的事件。
     * @param pluginName 插件名称（用于日志/调试）
     * @param type 事件类型
     * @param condition 可选的触发条件表达式 —— 支持简单格式如 "command=/你好"
     * @param action 事件处理器
     */
    public void register(String pluginName, PluginEvent.Type type,
                         String condition, PluginEventHandler action) {
        register(pluginName, type, condition, PRIORITY_NORMAL, action);
    }

    /**
     * 订阅指定类型的事件并设置优先级。优先级数值越大越先执行。
     */
    public void register(String pluginName, PluginEvent.Type type,
                         String condition, int priority, PluginEventHandler action) {
        HandlerEntry entry = new HandlerEntry(pluginName, type, "", condition, priority, action, true);
        handlersByType.get(type).add(entry);
        allHandlers.add(entry);
        LOG.info("[" + pluginName + "] 注册事件处理器: " + type + (condition != null && !condition.isEmpty() ? " (" + condition + ")" : ""));
    }

    /** 订阅自定义 key 的事件（type 自动为 CUSTOM）。 */
    public void registerCustom(String pluginName, String customKey,
                               String condition, PluginEventHandler action) {
        registerCustom(pluginName, customKey, condition, PRIORITY_NORMAL, action);
    }

    /** 订阅自定义 key 的事件并设置优先级。 */
    public void registerCustom(String pluginName, String customKey,
                               String condition, int priority, PluginEventHandler action) {
        HandlerEntry entry = new HandlerEntry(pluginName, PluginEvent.Type.CUSTOM, customKey, condition, priority, action, true);
        handlersByKey.computeIfAbsent(customKey, k -> new CopyOnWriteArrayList<>()).add(entry);
        allHandlers.add(entry);
        LOG.info("[" + pluginName + "] 注册自定义事件: " + customKey);
    }

    /** 切换某个事件处理器的启用状态。 */
    public void setEnabled(String pluginName, PluginEvent.Type type, boolean enabled) {
        for (HandlerEntry e : allHandlers) {
            if (e.pluginName.equals(pluginName) && e.type == type) {
                e.enabled = enabled;
            }
        }
    }

    /** 移除某插件的所有事件处理器（插件卸载时调用）。 */
    public void unregisterAll(String pluginName) {
        allHandlers.removeIf(e -> e.pluginName.equals(pluginName));
        for (List<HandlerEntry> list : handlersByType.values()) {
            list.removeIf(e -> e.pluginName.equals(pluginName));
        }
        for (List<HandlerEntry> list : handlersByKey.values()) {
            list.removeIf(e -> e.pluginName.equals(pluginName));
        }
        LOG.debug("已清理插件 [" + pluginName + "] 的所有事件处理器");
    }

    // ==================== 分发 API ====================

    /**
     * 静默触发事件：供游戏核心代码调用（登录/战斗/物品/游历/命令等）。
     * 处理器异常已在 fire 内部捕获，不会影响核心流程。
     */
    public static void fireQuietly(PluginEvent event) {
        if (event == null) return;
        try {
            getInstance().fire(event);
        } catch (Throwable t) {
            LOG.error("事件触发异常: " + event.toShortString(), t);
        }
    }

    /** 触发一个事件，分发给所有相关处理器。按优先级降序执行。 */
    public void fire(PluginEvent event) {        List<HandlerEntry> targets = new ArrayList<>();
        if (event.getType() == PluginEvent.Type.CUSTOM) {
            List<HandlerEntry> byKey = handlersByKey.get(event.getCustomKey());
            if (byKey != null) targets.addAll(byKey);
        } else {
            targets.addAll(handlersByType.get(event.getType()));
        }

        // 按优先级降序排列（数值大的先执行）
        targets.sort(Comparator.comparingInt((HandlerEntry h) -> h.priority).reversed());

        int dispatched = 0;
        for (HandlerEntry h : targets) {
            if (!h.enabled) continue;
            if (event.isCancelled()) break;
            if (h.condition != null && !h.condition.isEmpty() && !matchesCondition(event, h.condition)) continue;
            try {
                h.action.handle(event);
                dispatched++;
            } catch (Throwable t) {
                LOG.error("事件处理器异常: " + h.pluginName + " -> " + event.toShortString(), t);
            }
        }

        if (dispatched > 0) {
            LOG.debug("事件 " + event.toShortString() + " 已分发给 " + dispatched + " 个处理器");
        }
    }

    /** 检查事件是否满足给定条件（简单 key=value 格式，支持逗号分隔的多条件）。 */
    private static boolean matchesCondition(PluginEvent event, String condition) {
        if (condition == null || condition.trim().isEmpty()) return true;
        for (String part : condition.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;
            String key = trimmed.substring(0, eq).trim();
            String expected = trimmed.substring(eq + 1).trim();
            Object actual = event.getData().get(key);
            if (actual == null || !expected.equalsIgnoreCase(String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 统计/调试 ====================

    /** 获取当前注册的处理器总数（含禁用的）。 */
    public int getTotalHandlerCount() { return allHandlers.size(); }

    /** 获取某类型事件的活跃处理器数量。 */
    public int getActiveCount(PluginEvent.Type type) {
        int c = 0;
        for (HandlerEntry e : handlersByType.get(type)) if (e.enabled) c++;
        return c;
    }

    // ==================== 内部数据结构 ====================

    static final class HandlerEntry {
        final String pluginName;
        final PluginEvent.Type type;
        final String customKey;
        final String condition;
        final int priority;
        final PluginEventHandler action;
        volatile boolean enabled;

        HandlerEntry(String pluginName, PluginEvent.Type type, String customKey,
                     String condition, int priority, PluginEventHandler action, boolean enabled) {
            this.pluginName = pluginName;
            this.type = type;
            this.customKey = customKey;
            this.condition = condition;
            this.priority = priority;
            this.action = action;
            this.enabled = enabled;
        }
    }
}
