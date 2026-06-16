package com.mtxgdn.plugin.event;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件事件基类。所有插件触发的事件都继承或使用此类。
 * <p>
 * 事件系统是轻量且灵活的：
 * <pre>
 *   PluginEvent event = PluginEvent.builder()
 *       .type(PluginEvent.Type.COMMAND)
 *       .data("command", "/你好")
 *       .data("senderId", "player_123")
 *       .build();
 *   PluginEventManager.fire(event);
 * </pre>
 */
public class PluginEvent {

    /**
     * 预定义事件类型。插件开发者也可以使用 CUSTOM + 自定义 key。
     */
    public enum Type {
        /** 当玩家执行命令时触发 */
        COMMAND,
        /** 玩家登录 */
        PLAYER_LOGIN,
        /** 玩家登出 */
        PLAYER_LOGOUT,
        /** 物品被使用 */
        ITEM_USED,
        /** 战斗结束 */
        COMBAT_ENDED,
        /** 探索开始 */
        EXPLORATION_START,
        /** 探索结束 */
        EXPLORATION_END,
        /** 定时周期性事件（由计时器触发） */
        SCHEDULED,
        /** 服务端启动完成 */
        SERVER_READY,
        /** 自定义事件 */
        CUSTOM
    }

    private final Type type;
    private final String customKey;      // 当 type=CUSTOM 时的自定义标识
    private final Map<String, Object> data;
    private final long timestamp;
    private boolean cancelled;

    PluginEvent(Builder b) {
        this.type = b.type;
        this.customKey = b.customKey;
        this.data = Collections.unmodifiableMap(new HashMap<>(b.data));
        this.timestamp = System.currentTimeMillis();
        this.cancelled = false;
    }

    public Type getType() { return type; }
    public String getCustomKey() { return customKey; }
    public Map<String, Object> getData() { return data; }
    public long getTimestamp() { return timestamp; }
    public boolean isCancelled() { return cancelled; }

    /** 获取指定 key 的数据（强转），缺失返回 null */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    /** 标记事件为已取消（由 Handler 决定实际效果） */
    public void cancel() { this.cancelled = true; }

    /** 获取用于日志的简短描述 */
    public String toShortString() {
        String key = type == Type.CUSTOM ? "[" + customKey + "]" : "";
        return type.name() + key + "(" + data.size() + " fields)";
    }

    public static Builder builder() { return new Builder(); }

    /** 构建器 */
    public static final class Builder {
        private Type type = Type.CUSTOM;
        private String customKey = "";
        private final Map<String, Object> data = new HashMap<>();

        public Builder type(Type t) { this.type = t; return this; }
        public Builder customKey(String k) { this.customKey = k == null ? "" : k; return this; }
        public Builder data(String key, Object value) {
            if (key != null && value != null) this.data.put(key, value);
            return this;
        }
        public PluginEvent build() { return new PluginEvent(this); }
    }
}
