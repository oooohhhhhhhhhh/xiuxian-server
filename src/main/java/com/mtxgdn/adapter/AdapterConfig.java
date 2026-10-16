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
}
