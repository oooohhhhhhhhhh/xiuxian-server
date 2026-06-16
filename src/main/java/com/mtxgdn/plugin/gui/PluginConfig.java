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
 * 支持：
 * <ul>
 *   <li>基础信息（插件名、版本、作者、描述、包名、主类名）</li>
 *   <li>功能开关（是否注册命令/物品/事件/秘境）</li>
 *   <li>事件触发器列表（用户在触发器面板中配置的条目）</li>
 *   <li>JSON 格式持久化（保存/加载到文件）</li>
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

    // ===== 功能开关
    private boolean includeCommand;
    private boolean includeItem;
    private boolean includeEvent;
    private boolean includeSecretRealm;

    // ===== 触发器列表
    private final List<TriggerConfig> triggers;

    public PluginConfig() {
        this.pluginName = "我的插件";
        this.version = "1.0.0";
        this.author = "匿名";
        this.description = "由 PluginMaker 生成的示例插件";
        this.artifactId = "my-plugin";
        this.groupId = "com.example";
        this.mainClass = "MyPlugin";
        this.outputDir = "./my-plugin";
        this.includeCommand = true;
        this.includeItem = true;
        this.includeEvent = false;
        this.includeSecretRealm = false;
        this.triggers = new ArrayList<>();
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
    public boolean isIncludeCommand() { return includeCommand; }
    public void setIncludeCommand(boolean b) { this.includeCommand = b; }
    public boolean isIncludeItem() { return includeItem; }
    public void setIncludeItem(boolean b) { this.includeItem = b; }
    public boolean isIncludeEvent() { return includeEvent; }
    public void setIncludeEvent(boolean b) { this.includeEvent = b; }
    public boolean isIncludeSecretRealm() { return includeSecretRealm; }
    public void setIncludeSecretRealm(boolean b) { this.includeSecretRealm = b; }

    public List<TriggerConfig> getTriggers() { return triggers; }

    /** 合成的 Java 包名（groupId.artifactId）。 */
    public String getPackageName() { return groupId + (artifactId.isEmpty() ? "" : "." + artifactId); }
    /** 合成的目录路径（相对 src/main/java）。 */
    public String getPackagePath() { return getPackageName().replace('.', '/'); }

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
        m.put("includeCommand", includeCommand);
        m.put("includeItem", includeItem);
        m.put("includeEvent", includeEvent);
        m.put("includeSecretRealm", includeSecretRealm);
        m.put("triggers", TriggerConfig.listToMap(triggers));
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
        c.includeCommand = MiniJson.getBoolean(m, "includeCommand", c.includeCommand);
        c.includeItem = MiniJson.getBoolean(m, "includeItem", c.includeItem);
        c.includeEvent = MiniJson.getBoolean(m, "includeEvent", c.includeEvent);
        c.includeSecretRealm = MiniJson.getBoolean(m, "includeSecretRealm", c.includeSecretRealm);
        c.triggers.clear();
        c.triggers.addAll(TriggerConfig.listFromMap(MiniJson.getListOfObjects(m, "triggers")));
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
