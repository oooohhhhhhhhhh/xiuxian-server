package com.mtxgdn.plugin.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个触发器 —— 属于某个注册项（物品/事件/秘境/指令）的触发配置。
 * <p>
 * 包含：
 * <ul>
 *   <li>triggerWhen：触发时机（物品使用时 / 探索事件触发时 / 指令执行时）</li>
 *   <li>action：动作类型（发送消息/给予物品/执行Java代码等）</li>
 *   <li>actionParam：动作参数</li>
 *   <li>javaCode：自定义 Java 代码</li>
 *   <li>description：描述</li>
 *   <li>enabled：是否启用</li>
 * </ul>
 */
public final class Trigger {

    /** 动作类型枚举 —— 触发器被激活时执行的操作。 */
    public enum Action {
        /** 发送一条消息（记录日志 + 可选广播） */
        SEND_MESSAGE("发送消息"),
        /** 给予玩家灵石 */
        GIVE_SPIRIT_STONES("给予灵石"),
        /** 给予玩家物品 */
        GIVE_ITEM("给予物品"),
        /** 执行一段自定义 Java 代码 */
        RUN_JAVA("执行代码"),
        /** 仅记录日志（调试用） */
        LOG_ONLY("仅记录日志");

        public final String label;
        Action(String l) { this.label = l; }
    }

    /**
     * 触发时机 —— 不同注册项类型有不同的可选值。
     *
     * <p>这里只保留服务端<b>真实存在</b>钩子的时机：</p>
     * <ul>
     *   <li>{@link #ITEM_ON_USE} —— 物品的 {@code ItemEffect} 被使用物品时执行；</li>
     *   <li>{@link #EVENT_ON_FIRE} —— 探索事件的 {@code execute(...)} 被游历系统调用；</li>
     *   <li>{@link #COMMAND_ON_EXECUTE} —— 指令的 {@code execute(CommandContext)} 被玩家触发。</li>
     * </ul>
     *
     * <p>曾经存在的「物品·获得时」「秘境·进入/退出/遭遇」已移除：服务端核心没有对应的事件
     * （{@code PluginEvent.Type} 里没有「获得物品」「进入秘境」），生成出来只会是调不通的代码。</p>
     */
    public enum When {
        // ===== 物品相关 =====
        /** 玩家使用物品（/使用 &lt;key&gt;）时触发 */
        ITEM_ON_USE("物品 · 使用时"),

        // ===== 事件相关 =====
        /** 探索事件被 /游历 抽取并执行时触发 */
        EVENT_ON_FIRE("探索事件 · 触发时"),

        // ===== 指令相关 =====
        /** 玩家执行该指令时触发 */
        COMMAND_ON_EXECUTE("指令 · 执行时");

        public final String label;
        When(String l) { this.label = l; }
    }

    /** 触发时机 */
    private When triggerWhen;
    /** 动作类型 */
    private Action action;
    /** 动作参数（例如消息内容、灵石数量、物品 key） */
    private String actionParam;
    /** 自定义 Java 代码（当 action = RUN_JAVA 时） */
    private String javaCode;
    /** 描述（便于识别该触发器） */
    private String description;
    /** 是否启用 */
    private boolean enabled;

    public Trigger() {
        this.triggerWhen = When.ITEM_ON_USE;
        this.action = Action.SEND_MESSAGE;
        this.actionParam = "道友安好！";
        this.javaCode = "// 在此编写自定义 Java 代码\n// context.getLogger().info(\"hello\");";
        this.description = "";
        this.enabled = true;
    }

    // 便捷构造：为某个注册项类型创建默认值
    public static Trigger forItemUse() {
        Trigger t = new Trigger();
        t.triggerWhen = When.ITEM_ON_USE;
        t.description = "玩家使用物品时触发";
        return t;
    }

    public static Trigger forEventFire() {
        Trigger t = new Trigger();
        t.triggerWhen = When.EVENT_ON_FIRE;
        t.action = Action.LOG_ONLY;
        t.actionParam = "";
        t.description = "探索事件被抽取时记录日志";
        return t;
    }

    public static Trigger forCommandExecute() {
        Trigger t = new Trigger();
        t.triggerWhen = When.COMMAND_ON_EXECUTE;
        t.description = "玩家执行指令时回复消息";
        return t;
    }

    // ==================== 访问器 ====================

    public When getTriggerWhen() { return triggerWhen; }
    public void setTriggerWhen(When w) { this.triggerWhen = w; }

    public Action getAction() { return action; }
    public void setAction(Action a) { this.action = a; }

    public String getActionParam() { return actionParam == null ? "" : actionParam; }
    public void setActionParam(String p) { this.actionParam = p == null ? "" : p; }

    public String getJavaCode() { return javaCode == null ? "" : javaCode; }
    public void setJavaCode(String c) { this.javaCode = c == null ? "" : c; }

    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean b) { this.enabled = b; }

    // ==================== JSON 序列化 ====================

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("triggerWhen", triggerWhen.name());
        m.put("action", action.name());
        m.put("actionParam", actionParam);
        m.put("javaCode", javaCode);
        m.put("description", description);
        m.put("enabled", enabled);
        return m;
    }

    static Trigger fromMap(Map<String, Object> m) {
        Trigger t = new Trigger();
        try { t.triggerWhen = When.valueOf(MiniJson.getString(m, "triggerWhen", "ITEM_ON_USE")); } catch (Exception ignored) {}
        try { t.action = Action.valueOf(MiniJson.getString(m, "action", "SEND_MESSAGE")); } catch (Exception ignored) {}
        t.actionParam = MiniJson.getString(m, "actionParam", "");
        t.javaCode = MiniJson.getString(m, "javaCode", "");
        t.description = MiniJson.getString(m, "description", "");
        t.enabled = MiniJson.getBoolean(m, "enabled", true);
        return t;
    }

    static List<Map<String, Object>> listToMap(List<Trigger> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Trigger t : list) out.add(t.toMap());
        return out;
    }

    static List<Trigger> listFromMap(List<Map<String, Object>> list) {
        List<Trigger> out = new ArrayList<>();
        for (Map<String, Object> m : list) out.add(fromMap(m));
        return out;
    }
}
