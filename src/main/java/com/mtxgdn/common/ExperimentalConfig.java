package com.mtxgdn.common;

import com.mtxgdn.util.AppConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验性功能配置 —— 独立于 application.yml，开关集中管理
 */
public class ExperimentalConfig {

    private static final String CONFIG_FILE = "experimental.yml";
    private static final Map<String, String> config;

    static {
        // 使用 AppConfig 统一的 jar 目录基准
        Path jarDir = AppConfig.getJarDir();
        Path externalPath = jarDir.resolve("config").resolve(CONFIG_FILE);

        if (!Files.exists(externalPath)) {
            extractConfigFromClasspath(externalPath);
        }

        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(load(""));
        merged.putAll(load(externalPath.toString()));

        config = Collections.unmodifiableMap(merged);
        System.out.println("[ExperimentalConfig] 加载完成，共 " + config.size() + " 项");
    }

    private static void extractConfigFromClasspath(Path target) {
        try (InputStream in = ExperimentalConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[ExperimentalConfig] 已释放配置文件: " + target.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ExperimentalConfig] 释放失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String path) {
        Map<String, String> result = new LinkedHashMap<>();
        InputStream in = null;
        try {
            if (path.isEmpty()) {
                in = ExperimentalConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
            } else {
                Path ext = Paths.get(path);
                if (Files.exists(ext)) {
                    in = new FileInputStream(ext.toFile());
                }
            }
            if (in == null) return result;

            Yaml yaml = new Yaml();
            Object raw = yaml.load(in);
            if (raw instanceof Map) {
                flatten((Map<String, Object>) raw, "", result);
            }
        } catch (Exception e) {
            System.err.println("[ExperimentalConfig] 读取失败: " + e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> source, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten((Map<String, Object>) value, key, result);
            } else {
                result.put(key, value != null ? value.toString() : "false");
            }
        }
    }

    public static boolean isEnabled(String feature) {
        return "true".equalsIgnoreCase(config.get("experimental." + feature));
    }

    public static String get(String key, String defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }
}
