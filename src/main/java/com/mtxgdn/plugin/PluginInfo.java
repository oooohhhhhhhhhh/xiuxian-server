package com.mtxgdn.plugin;

/**
 * 插件元数据，由 plugin.json 或类上的 @PluginMeta 注解解析而来。
 */
public final class PluginInfo {
    private final String name;
    private final String version;
    private final String author;
    private final String description;
    private final String mainClass;

    public PluginInfo(String name, String version, String author, String description, String mainClass) {
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.mainClass = mainClass;
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public String getMainClass() { return mainClass; }

    @Override
    public String toString() {
        return name + " v" + version + " (作者: " + author + ")";
    }
}
