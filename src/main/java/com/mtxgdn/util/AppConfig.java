package com.mtxgdn.util;

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

public class AppConfig {

    private static final String CONFIG_FILE = "application.yml";
    private static final String CONFIG_DIR = "config";
    private static final Map<String, Object> config;

    static {
        Path externalPath = Paths.get(CONFIG_DIR, CONFIG_FILE);

        if (!Files.exists(externalPath)) {
            extractConfigFromClasspath(externalPath);
        }

        Map<String, Object> base = loadClasspathOrDefault();
        Map<String, Object> external = loadExternal(externalPath);
        Map<String, Object> merged = deepMerge(base, external);

        System.out.println("[Config] 配置加载完成 (" + merged.size() + " 项)");

        config = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
    }

    private static void extractConfigFromClasspath(Path target) {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                System.out.println("[Config] 内置 " + CONFIG_FILE + " 不存在，跳过自动释放");
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Config] 已自动释放配置文件: " + target.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[Config] 自动释放 " + CONFIG_FILE + " 失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadClasspathOrDefault() {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                Yaml yaml = new Yaml();
                Object raw = yaml.load(in);
                if (raw instanceof Map) {
                    return flatten((Map<String, Object>) raw, "");
                }
            }
        } catch (Exception e) {
            System.err.println("[Config] 读取内置 " + CONFIG_FILE + " 失败: " + e.getMessage());
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadExternal(Path externalPath) {
        if (!Files.exists(externalPath)) {
            return Collections.emptyMap();
        }
        try (InputStream in = new FileInputStream(externalPath.toFile())) {
            Yaml yaml = new Yaml();
            Object raw = yaml.load(in);
            if (raw instanceof Map) {
                System.out.println("[Config] 已加载外部配置: " + externalPath.toAbsolutePath());
                return flatten((Map<String, Object>) raw, "");
            }
        } catch (Exception e) {
            System.err.println("[Config] 读取外部 " + CONFIG_FILE + " 失败: " + e.getMessage());
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object baseVal = result.get(key);
            Object overrideVal = entry.getValue();
            if (baseVal instanceof Map && overrideVal instanceof Map) {
                result.put(key, deepMerge((Map<String, Object>) baseVal, (Map<String, Object>) overrideVal));
            } else {
                result.put(key, overrideVal);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> source, String prefix) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.putAll(flatten((Map<String, Object>) value, key));
            } else {
                result.put(key, value != null ? value.toString() : "");
            }
        }
        return result;
    }

    public static String get(String path) {
        Object value = config.get(path);
        return value != null ? value.toString() : null;
    }

    public static String get(String path, String defaultValue) {
        Object value = config.get(path);
        return value != null ? value.toString() : defaultValue;
    }

    public static int getInt(String path, int defaultValue) {
        String value = get(path);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String path, boolean defaultValue) {
        String value = get(path);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }
}
