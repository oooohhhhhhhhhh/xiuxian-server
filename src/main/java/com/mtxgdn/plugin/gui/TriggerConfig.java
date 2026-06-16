package com.mtxgdn.plugin.gui;

import com.mtxgdn.plugin.event.PluginEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个事件触发器配置 —— GUI 中配置的每一条触发器条目。
 * <p>
 * 包含：事件类型、触发条件、响应动作、描述、启用状态。
 */
public final class TriggerConfig {

    /** 响应动作枚举 —— 当事件触发后执行何种操作。 */
    public enum Action {
        /** 发送一条消息给玩家 */
        SEND_MESSAGE("发送消息"),
        /** 给予玩家灵石 */
        GIVE_SPIRIT_STONES("给予灵石"),
        /** 给予玩家物品 */
        GIVE_ITEM("给予物品"),
        /** 执行一段 Java 代码 */
        RUN_JAVA("执行代码"),
        /** 仅记录日志（用于调试） */
        LOG_ONLY("仅记录日志");

        public final String label;
        Action(String l) { this.label = l; }
    }

    /** 事件类型（对应 PluginEvent.Type） */
    PluginEvent.Type eventType;
    /** 自定义 key（当 eventType = CUSTOM 时使用） */
    String customKey;
    /** 触发条件表达式，如 "command=/你好"，多个条件逗号分隔；空表示始终触发 */
    String condition;
    /** 响应动作类型 */
    Action action;
    /** 动作参数（例如消息内容、灵石数量、物品 key） */
    String actionParam;
    /** Java 代码片段（当 action = RUN_JAVA 时使用） */
    String javaCode;
    /** 描述（便于识别） */
    String description;
    /** 启用状态 */
    boolean enabled;

    public TriggerConfig() {
        this.eventType = PluginEvent.Type.COMMAND;
        this.customKey = "";
        this.condition = "";
        this.action = Action.SEND_MESSAGE;
        this.actionParam = "道友安好！";
        this.javaCode = "// 在此编写自定义 Java 代码";
        this.description = "示例触发器";
        this.enabled = true;
    }

    // ===== 字段访问器
    public PluginEvent.Type getEventType() { return eventType; }
    public void setEventType(PluginEvent.Type t) { this.eventType = t; }
    public String getCustomKey() { return customKey; }
    public void setCustomKey(String k) { this.customKey = k == null ? "" : k; }
    public String getCondition() { return condition; }
    public void setCondition(String c) { this.condition = c == null ? "" : c; }
    public Action getAction() { return action; }
    public void setAction(Action a) { this.action = a; }
    public String getActionParam() { return actionParam; }
    public void setActionParam(String p) { this.actionParam = p == null ? "" : p; }
    public String getJavaCode() { return javaCode; }
    public void setJavaCode(String c) { this.javaCode = c == null ? "" : c; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean b) { this.enabled = b; }

    // ==================== JSON 序列化 ====================

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventType", eventType.name());
        m.put("customKey", customKey);
        m.put("condition", condition);
        m.put("action", action.name());
        m.put("actionParam", actionParam);
        m.put("javaCode", javaCode);
        m.put("description", description);
        m.put("enabled", enabled);
        return m;
    }

    static TriggerConfig fromMap(Map<String, Object> m) {
        TriggerConfig t = new TriggerConfig();
        try { t.eventType = PluginEvent.Type.valueOf(MiniJson.getString(m, "eventType", "COMMAND")); } catch (Exception ignored) {}
        t.customKey = MiniJson.getString(m, "customKey", "");
        t.condition = MiniJson.getString(m, "condition", "");
        try { t.action = Action.valueOf(MiniJson.getString(m, "action", "SEND_MESSAGE")); } catch (Exception ignored) {}
        t.actionParam = MiniJson.getString(m, "actionParam", "");
        t.javaCode = MiniJson.getString(m, "javaCode", "");
        t.description = MiniJson.getString(m, "description", "");
        t.enabled = MiniJson.getBoolean(m, "enabled", true);
        return t;
    }

    static List<Map<String, Object>> listToMap(List<TriggerConfig> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TriggerConfig t : list) out.add(t.toMap());
        return out;
    }

    static List<TriggerConfig> listFromMap(List<Map<String, Object>> list) {
        List<TriggerConfig> out = new ArrayList<>();
        for (Map<String, Object> m : list) out.add(fromMap(m));
        return out;
    }
}
