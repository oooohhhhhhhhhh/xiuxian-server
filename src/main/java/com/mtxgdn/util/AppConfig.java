package com.mtxgdn.util;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URISyntaxException;
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

    public static Path getJarDir() {
        try {
            String jarPath = AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);
            File parent = jarFile.isDirectory() ? jarFile : jarFile.getParentFile();
            return parent != null ? parent.toPath() : Paths.get("").toAbsolutePath();
        } catch (URISyntaxException e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    static {
        Path jarDir = getJarDir();
        Path externalPath = jarDir.resolve(CONFIG_DIR).resolve(CONFIG_FILE);

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
            } else if (value instanceof java.util.List) {
                // YAML 列表统一转成逗号分隔字符串，避免 "[a, b]" 形态导致按 split(",") 解析失败
                result.put(key, ((java.util.List<?>) value).stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")));
            } else {
                result.put(key, value != null ? value.toString() : "");
            }
        }
        return result;
    }

    /**
     * 取配置项。
     *
     * <p><b>系统属性优先</b>：{@code -Dkey=value} 会覆盖 {@code application.yml} 里的同名项。
     * 这既是运维的应急开关（改一个值不必动配置文件、不必重新分发），
     * 也让测试能把"写文件"的目录整体拨到 {@code target/} 下，
     * 而不是往真实的 {@code data/players} 里灌一堆测试快照。</p>
     */
    public static String get(String path) {
        String override = System.getProperty(path);
        if (override != null) {
            return override;
        }
        Object value = config.get(path);
        return value != null ? value.toString() : null;
    }

    /**
     * 取配置项，缺失时用默认值。
     *
     * <p>必须委托给 {@link #get(String)}，否则 {@code -D} 覆盖在这一层就断了
     * —— 绝大多数调用点用的正是这个带默认值的重载，
     * 曾经因为这里直接读 {@code config} 而导致系统属性只对少数单参调用生效。</p>
     */
    public static String get(String path, String defaultValue) {
        String value = get(path);
        return value != null ? value : defaultValue;
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

    /** 是否存在以指定前缀开头的配置键（如旧安装遗留的 {@code built_in_plugins.}）。 */
    public static boolean hasKeyPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        for (String key : config.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建一个以 block 风格（缩进分行、可读）输出的 Yaml 实例。
     * <p>默认 {@code new Yaml()} 在 SnakeYAML 下会把嵌套 Map 序列化为
     * flow 紧凑风格（如 {@code app: {name: xxx}}），可读性差且与手写模板
     * 不一致。使用 block 风格后，安装向导 / 配置升级写回的
     * application.yml 会与仓库模板保持一致的缩进结构。</p>
     */
    public static Yaml newBlockYaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        return new Yaml(options);
    }

    /**
     * 更新 application.yml 中的若干键并写入磁盘（保留注释与排版）。
     * <p>v2：不再用 SnakeYAML 整文件 dump（旧实现会丢弃全部注释并按默认排版重排），
     * 改为对目标键做<b>行级值替换</b>：只改值，保留原有注释、空行与缩进结构；
     * 不存在的键追加到对应父节末尾（父节不存在则追加到文件末尾）。</p>
     * <p>值语义：{@code null} 表示删除该键所在行（含紧随其后缩进更深的注释延续行，
     * 例如旧模板中 admin.password 的说明注释）；空字符串表示写入空值 {@code ''}；
     * 其余值按 YAML 标量写入（必要时自动加引号）。</p>
     * <p>注意：仅更新外部 config/application.yml；若不存在则先从 classpath 释放模板再写入。
     * 写盘使用临时文件 + rename 的原子方式，避免半截写入。</p>
     * <p>v3：行级编辑逻辑抽到 {@link YamlFileEditor}，与 plugins.yml 等独立配置文件共用。</p>
     */
    public static synchronized void update(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Path jarDir = getJarDir();
        Path externalPath = jarDir.resolve(CONFIG_DIR).resolve(CONFIG_FILE);
        if (!Files.exists(externalPath)) {
            extractConfigFromClasspath(externalPath);
        }
        YamlFileEditor.update(externalPath, updates);
    }

}

