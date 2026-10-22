package com.mtxgdn.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个适配器配置项。
 */
public class AdapterConfig {
    private String id;
    private String type;
    private boolean enabled;
    private Map<String, Object> config = new LinkedHashMap<>();

    public AdapterConfig() {
    }

    public AdapterConfig(String id, String type, boolean enabled, Map<String, Object> config) {
        this.id = id;
        this.type = type;
        this.enabled = enabled;
        this.config = config != null ? config : new LinkedHashMap<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config != null ? config : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = config.get(key);
        if (val == null) return defaultValue;
        return (T) val;
    }

    public String getString(String key, String defaultValue) {
        Object val = config.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object val = config.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object val = config.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /** 创建深拷贝（用于热重载对比） */
    public AdapterConfig copy() {
        return new AdapterConfig(id, type, enabled, new LinkedHashMap<>(config));
    }

    /**
     * 值相等：{@code id}、{@code type}、{@code enabled} 与整个 {@code config} 都相同。
     *
     * <p><b>为什么必须重写</b>：{@code AdapterManager.reloadAll} 用
     * {@code !oldCfg.equals(newCfg)} 判断"配置是否变更，需要先停后启"。
     * 不重写 {@code equals} 时那是<b>引用比较</b>，热重载读出来的永远是新的对象，
     * 于是判断恒为"已变更" —— 每次保存配置都会把所有启用的适配器停掉再启动，
     * 正在聊天的 QQ/Minecraft 玩家连接被无谓地掐断。</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdapterConfig other)) {
            return false;
        }
        return enabled == other.enabled
                && java.util.Objects.equals(id, other.id)
                && java.util.Objects.equals(type, other.type)
                && configEquals(config, other.config);
    }

    /**
     * 只按 {@code id}/{@code type}/{@code enabled} 计算哈希。
     *
     * <p>刻意不把 {@code config} 算进去：适配器配置是可变的，放进哈希会把
     * "先 put 进 HashMap 再改配置"变成找不到对象。这里只保证
     * {@code equals → 同 hash} 这条契约。</p>
     */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, type, enabled);
    }

    /**
     * 比较两份 config，<b>数字按数值比较</b>。
     *
     * <p>原因：热重载走的是 {@code Gson.fromJson(..., Map.class)}，
     * JSON 里的数字一律变成 {@code Double}，而内存里的原始值可能是 {@code Integer}
     * （例如默认配置里写死的 {@code port: 8080}）。若直接用 {@code Map.equals}，
     * {@code 8080} 与 {@code 8080.0} 会被判为不同 —— 等于没修，仍然是每次重载都重启。</p>
     */
    private static boolean configEquals(Map<String, Object> a, Map<String, Object> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (!b.containsKey(entry.getKey())) {
                return false;
            }
            if (!valueEquals(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean valueEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Map<?, ?> && b instanceof Map<?, ?>) {
            return configEquals((Map<String, Object>) a, (Map<String, Object>) b);
        }
        if (a instanceof java.util.List<?> la && b instanceof java.util.List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!valueEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
