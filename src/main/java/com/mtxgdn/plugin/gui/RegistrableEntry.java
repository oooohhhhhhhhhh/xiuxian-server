package com.mtxgdn.plugin.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可注册项 —— 代表一个物品、一个事件、一个秘境或一个指令。
 * <p>
 * 每个注册项包含：
 * <ul>
 *   <li>type：注册项类型（物品/事件/秘境/指令）</li>
 *   <li>key：唯一标识（例如物品 key、事件 key、指令名）</li>
 *   <li>name：显示名称</li>
 *   <li>description：描述</li>
 *   <li>extraInfo：额外配置（按类型不同有差异）</li>
 *   <li>triggers：触发器列表（每条对应一个触发时机+动作）</li>
 *   <li>enabled：是否启用</li>
 * </ul>
 */
public final class RegistrableEntry {

    /** 注册项类型。 */
    public enum Type {
        ITEM("物品"),
        EVENT("事件"),
        COMMAND("指令"),
        SECRET_REALM("秘境");

        public final String label;
        Type(String l) { this.label = l; }
    }

    /** 类型 */
    private Type type;
    /** 唯一标识 key（物品 key、事件 key、指令名、秘境 key） */
    private String key;
    /** 显示名称 */
    private String name;
    /** 描述 */
    private String description;
    /** 额外信息（例如物品的稀有度、秘境的最低等级等，可选） */
    private String extraInfo;
    /** 触发器列表 */
    private final List<Trigger> triggers;
    /** 是否启用 */
    private boolean enabled;

    public RegistrableEntry() {
        this.type = Type.ITEM;
        this.key = "";
        this.name = "";
        this.description = "";
        this.extraInfo = "";
        this.triggers = new ArrayList<>();
        this.enabled = true;
    }

    public RegistrableEntry(Type type, String key, String name) {
        this();
        this.type = type;
        this.key = key;
        this.name = name;
    }

    // ==================== 便捷工厂 ====================

    public static RegistrableEntry newItem(String key, String name) {
        RegistrableEntry e = new RegistrableEntry(Type.ITEM, key, name);
        e.description = "示例物品";
        e.triggers.add(Trigger.forItemUse());
        return e;
    }

    public static RegistrableEntry newEvent(String key, String name) {
        RegistrableEntry e = new RegistrableEntry(Type.EVENT, key, name);
        e.description = "示例事件";
        e.triggers.add(Trigger.forEventFire());
        return e;
    }

    public static RegistrableEntry newCommand(String key, String name) {
        RegistrableEntry e = new RegistrableEntry(Type.COMMAND, key, name);
        e.description = "示例指令";
        e.triggers.add(Trigger.forCommandExecute());
        return e;
    }

    public static RegistrableEntry newSecretRealm(String key, String name) {
        RegistrableEntry e = new RegistrableEntry(Type.SECRET_REALM, key, name);
        e.description = "示例秘境";
        e.extraInfo = "最低等级: 1 | 体力消耗: 10";
        e.triggers.add(Trigger.forRealmEnter());
        e.triggers.add(Trigger.forRealmExit());
        return e;
    }

    // ==================== 访问器 ====================

    public Type getType() { return type; }
    public void setType(Type t) { this.type = t; }

    public String getKey() { return key == null ? "" : key; }
    public void setKey(String k) { this.key = k == null ? "" : k; }

    public String getName() { return name == null ? "" : name; }
    public void setName(String n) { this.name = n == null ? "" : n; }

    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }

    public String getExtraInfo() { return extraInfo == null ? "" : extraInfo; }
    public void setExtraInfo(String s) { this.extraInfo = s == null ? "" : s; }

    public List<Trigger> getTriggers() { return triggers; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean b) { this.enabled = b; }

    // ==================== 便捷：根据类型获取默认时机 ====================

    /** 根据当前注册项类型，返回其可选的触发时机列表。 */
    public Trigger.When[] availableWhens() {
        switch (type) {
            case ITEM:    return new Trigger.When[] { Trigger.When.ITEM_ON_USE, Trigger.When.ITEM_ON_OBTAIN };
            case EVENT:   return new Trigger.When[] { Trigger.When.EVENT_ON_FIRE };
            case COMMAND: return new Trigger.When[] { Trigger.When.COMMAND_ON_EXECUTE };
            case SECRET_REALM:
                return new Trigger.When[] {
                        Trigger.When.REALM_ON_ENTER,
                        Trigger.When.REALM_ON_EXIT,
                        Trigger.When.REALM_ON_ENCOUNTER
                };
        }
        return new Trigger.When[] { Trigger.When.ITEM_ON_USE };
    }

    // ==================== JSON 序列化 ====================

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        m.put("key", key);
        m.put("name", name);
        m.put("description", description);
        m.put("extraInfo", extraInfo);
        m.put("enabled", enabled);
        m.put("triggers", Trigger.listToMap(triggers));
        return m;
    }

    static RegistrableEntry fromMap(Map<String, Object> m) {
        RegistrableEntry e = new RegistrableEntry();
        try { e.type = Type.valueOf(MiniJson.getString(m, "type", "ITEM")); } catch (Exception ignored) {}
        e.key = MiniJson.getString(m, "key", "");
        e.name = MiniJson.getString(m, "name", "");
        e.description = MiniJson.getString(m, "description", "");
        e.extraInfo = MiniJson.getString(m, "extraInfo", "");
        e.enabled = MiniJson.getBoolean(m, "enabled", true);
        e.triggers.clear();
        e.triggers.addAll(Trigger.listFromMap(MiniJson.getListOfObjects(m, "triggers")));
        return e;
    }

    static List<Map<String, Object>> listToMap(List<RegistrableEntry> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegistrableEntry e : list) out.add(e.toMap());
        return out;
    }

    static List<RegistrableEntry> listFromMap(List<Map<String, Object>> list) {
        List<RegistrableEntry> out = new ArrayList<>();
        for (Map<String, Object> m : list) out.add(fromMap(m));
        return out;
    }
}
