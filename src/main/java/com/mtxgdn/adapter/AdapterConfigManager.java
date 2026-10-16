package com.mtxgdn.adapter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtxgdn.util.GameLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理 adapters.json 的加载、保存和热重载。
 */
public class AdapterConfigManager {

    private static final GameLogger LOG = GameLogger.getLogger(AdapterConfigManager.class);

    public static final String CONFIG_FILE = "config/adapters.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static AdaptersConfig currentConfig;
    private static Path configPath;

    private AdapterConfigManager() {
    }

    /** 加载配置，不存在则自动创建默认配置 */
    public static synchronized AdaptersConfig load() {
        configPath = Paths.get(CONFIG_FILE);

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                currentConfig = GSON.fromJson(json, AdaptersConfig.class);
                if (currentConfig == null || currentConfig.getAdapters() == null) {
                    currentConfig = createDefaultConfig();
                    save();
                }
                LOG.info("已加载适配器配置: " + configPath.toAbsolutePath() + " (" + currentConfig.getAdapters().size() + " 个适配器)");
                return currentConfig;
            } catch (IOException e) {
                LOG.error("加载 adapters.json 失败，使用默认配置", e);
                currentConfig = createDefaultConfig();
                return currentConfig;
            }
        }

        LOG.info("adapters.json 不存在，创建默认配置");
        currentConfig = createDefaultConfig();
        save();
        return currentConfig;
    }

    /** 保存当前配置到文件 */
    public static synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = GSON.toJson(currentConfig);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            LOG.error("保存 adapters.json 失败", e);
        }
    }

    /** 获取当前配置 */
    public static synchronized AdaptersConfig getConfig() {
        if (currentConfig == null) {
            load();
        }
        return currentConfig;
    }

    /** 获取当前适配器列表快照 */
    public static synchronized List<AdapterConfig> getAdapters() {
        return getConfig().getAdapters();
    }

    /** 重新加载配置（用于热重载） */
    public static synchronized AdaptersConfig reload() {
        try {
            String json = Files.readString(configPath);
            AdaptersConfig newConfig = GSON.fromJson(json, AdaptersConfig.class);
            if (newConfig != null && newConfig.getAdapters() != null) {
                currentConfig = newConfig;
                LOG.info("已重载适配器配置: " + currentConfig.getAdapters().size() + " 个适配器");
            }
        } catch (IOException e) {
            LOG.error("重载 adapters.json 失败", e);
        }
        return currentConfig;
    }

    /** 更新配置并保存（用于 API 调用） */
    public static synchronized void updateConfig(AdaptersConfig newConfig) {
        currentConfig = newConfig;
        save();
    }

    /** 创建默认配置 */
    private static AdaptersConfig createDefaultConfig() {
        Map<String, Object> onebotConfig = new LinkedHashMap<>();
        onebotConfig.put("port", 6700);
        onebotConfig.put("mode", "ws_server");
        onebotConfig.put("send_mode", "text");
        onebotConfig.put("screenshot_window_title", "");
        onebotConfig.put("screenshot_poll_interval", 2000);

        Map<String, Object> minecraftConfig = new LinkedHashMap<>();
        minecraftConfig.put("jar_path", "");
        minecraftConfig.put("server_dir", "mc_server");
        minecraftConfig.put("java_path", "java");
        minecraftConfig.put("min_memory_mb", 512);
        minecraftConfig.put("max_memory_mb", 1024);
        minecraftConfig.put("auto_start", false);
        minecraftConfig.put("ready_timeout_seconds", 120);
        minecraftConfig.put("extra_jvm_args", "");
        minecraftConfig.put("online_mode", false);
        minecraftConfig.put("command_prefix", "xiuxian");

        return new AdaptersConfig(List.of(
                new AdapterConfig("onebot-default", "onebot", true, onebotConfig),
                new AdapterConfig("minecraft-default", "minecraft", false, minecraftConfig)
        ));
    }
}
