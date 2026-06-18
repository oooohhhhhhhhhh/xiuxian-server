package com.mtxgdn.plugin.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件制作配置 —— GUI 中用户设置的全部内容。
 * <p>
 * 包含：
 * <ul>
 *   <li>基础信息（插件名、版本、作者、描述、包名、主类名、输出目录）</li>
 *   <li>物品列表（List&lt;RegistrableEntry&gt;）—— 每个物品可含触发器</li>
 *   <li>事件列表（List&lt;RegistrableEntry&gt;）—— 每个事件可含触发器</li>
 *   <li>指令列表（List&lt;RegistrableEntry&gt;）—— 每个指令可含触发器</li>
 *   <li>秘境列表（List&lt;RegistrableEntry&gt;）—— 每个秘境可含触发器</li>
 *   <li>JSON 格式持久化</li>
 * </ul>
 */
public final class PluginConfig {

    // ===== 基础信息
    private String pluginName;
    private String version;
    private String author;
    private String description;
    private String artifactId;
    private String groupId;
    private String mainClass;
    private String outputDir;

    // ===== 注册项列表（触发器是每个注册项的属性，不再是顶层）
    private final List<RegistrableEntry> items;
    private final List<RegistrableEntry> events;
    private final List<RegistrableEntry> commands;
    private final List<RegistrableEntry> secretRealms;

    public PluginConfig() {
        this.pluginName = "我的插件";
        this.version = "1.0.0";
        this.author = "匿名";
        this.description = "由 PluginMaker 生成的示例插件";
        this.artifactId = "my-plugin";
        this.groupId = "com.example";
        this.mainClass = "MyPlugin";
        this.outputDir = "./my-plugin";

        this.items = new ArrayList<>();
        this.events = new ArrayList<>();
        this.commands = new ArrayList<>();
        this.secretRealms = new ArrayList<>();

        // 默认示例，便于用户理解结构
        this.items.add(RegistrableEntry.newItem("demo_talisman", "示例符箓"));
        this.commands.add(RegistrableEntry.newCommand("/你好", "打招呼指令"));
    }

    // ==================== 访问器 ====================

    public String getPluginName() { return pluginName; }
    public void setPluginName(String v) { this.pluginName = nullToEmpty(v); }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = nullToEmpty(v); }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = nullToEmpty(v); }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = nullToEmpty(v); }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String v) { this.artifactId = nullToEmpty(v); }
    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { this.groupId = nullToEmpty(v); }
    public String getMainClass() { return mainClass; }
    public void setMainClass(String v) { this.mainClass = nullToEmpty(v); }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String v) { this.outputDir = nullToEmpty(v); }

    public List<RegistrableEntry> getItems() { return items; }
    public List<RegistrableEntry> getEvents() { return events; }
    public List<RegistrableEntry> getCommands() { return commands; }
    public List<RegistrableEntry> getSecretRealms() { return secretRealms; }

    /** 合成的 Java 包名（groupId.artifactId）。 */
    public String getPackageName() { return groupId + (artifactId.isEmpty() ? "" : "." + artifactId); }
    /** 合成的目录路径（相对 src/main/java）。 */
    public String getPackagePath() { return getPackageName().replace('.', '/'); }

    /** 返回注册项总数（用于 UI 统计）。 */
    public int totalEntries() {
        return items.size() + events.size() + commands.size() + secretRealms.size();
    }
    /** 返回所有注册项的触发器总数（用于 UI 统计）。 */
    public int totalTriggers() {
        int total = 0;
        for (RegistrableEntry e : items) total += e.getTriggers().size();
        for (RegistrableEntry e : events) total += e.getTriggers().size();
        for (RegistrableEntry e : commands) total += e.getTriggers().size();
        for (RegistrableEntry e : secretRealms) total += e.getTriggers().size();
        return total;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    // ==================== JSON 序列化 ====================

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pluginName", pluginName);
        m.put("version", version);
        m.put("author", author);
        m.put("description", description);
        m.put("artifactId", artifactId);
        m.put("groupId", groupId);
        m.put("mainClass", mainClass);
        m.put("outputDir", outputDir);

        m.put("items", RegistrableEntry.listToMap(items));
        m.put("events", RegistrableEntry.listToMap(events));
        m.put("commands", RegistrableEntry.listToMap(commands));
        m.put("secretRealms", RegistrableEntry.listToMap(secretRealms));
        return m;
    }

    public static PluginConfig fromMap(Map<String, Object> m) {
        PluginConfig c = new PluginConfig();
        c.pluginName = MiniJson.getString(m, "pluginName", c.pluginName);
        c.version = MiniJson.getString(m, "version", c.version);
        c.author = MiniJson.getString(m, "author", c.author);
        c.description = MiniJson.getString(m, "description", c.description);
        c.artifactId = MiniJson.getString(m, "artifactId", c.artifactId);
        c.groupId = MiniJson.getString(m, "groupId", c.groupId);
        c.mainClass = MiniJson.getString(m, "mainClass", c.mainClass);
        c.outputDir = MiniJson.getString(m, "outputDir", c.outputDir);

        c.items.clear();
        c.items.addAll(RegistrableEntry.listFromMap(MiniJson.getListOfObjects(m, "items")));
        c.events.clear();
        c.events.addAll(RegistrableEntry.listFromMap(MiniJson.getListOfObjects(m, "events")));
        c.commands.clear();
        c.commands.addAll(RegistrableEntry.listFromMap(MiniJson.getListOfObjects(m, "commands")));
        c.secretRealms.clear();
        c.secretRealms.addAll(RegistrableEntry.listFromMap(MiniJson.getListOfObjects(m, "secretRealms")));
        return c;
    }

    // ==================== 文件持久化 ====================

    public void save(File file) throws IOException {
        String json = MiniJson.stringifyPretty(toMap());
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    public static PluginConfig load(File file) throws IOException {
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> map = MiniJson.parseObject(json);
        return fromMap(map);
    }

    public void save(String path) throws IOException { save(new File(path)); }
    public static PluginConfig load(String path) throws IOException { return load(new File(path)); }
}
