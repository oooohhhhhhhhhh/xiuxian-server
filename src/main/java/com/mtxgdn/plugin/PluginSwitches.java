package com.mtxgdn.plugin;

import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.YamlFileEditor;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件开关配置 —— 独立文件 {@code config/plugins.yml}，开关集中管理。
 * <p>
 * 历史上插件开关混在 {@code config/application.yml} 的 {@code built_in_plugins} 段
 * （实验性玩法开关则混在 {@code config/experimental.yml}），既难查找也难迁移。
 * 现在全部收敛到本文件，根节点为 {@code plugins}：
 * <pre>
 *   plugins:
 *     farm: true                    # 内置玩法模块
 *     experimental_sect_war: false  # 实验性插件
 *     onebot: true                  # 消息通道适配器
 * </pre>
 * <p>
 * <b>取值优先级</b>（高 → 低）：
 * <ol>
 *   <li>本文件 {@code plugins.<key>}（WebUI 启停即写回此处）</li>
 *   <li>jar 内模板 {@code plugins.yml}（作为缺失键的默认值）</li>
 *   <li>旧安装的只读兜底：{@code built_in_plugins.<key>}（application.yml）
 *       与 {@code experimental.<key>}（config/experimental.yml，仅外部文件）</li>
 *   <li>插件自身默认值</li>
 * </ol>
 * 旧键兜底只读：升级不会丢失老安装里被关掉的模块，但一旦 WebUI 写过本文件，
 * 本文件的值始终优先；旧键会在 {@code BuiltInPluginRegistry.init()} 时一次性迁移到本文件。
 */
public final class PluginSwitches {

    /** 新配置文件（相对 config/ 目录）。 */
    public static final String CONFIG_FILE = "plugins.yml";
    /** 新配置文件的根节点名。 */
    public static final String ROOT = "plugins";
    /** 新键前缀。 */
    public static final String KEY_PREFIX = ROOT + ".";
    /** 旧开关键前缀（application.yml 的 built_in_plugins 段，只读兜底）。 */
    public static final String LEGACY_KEY_PREFIX = "built_in_plugins.";
    /** 旧实验性开关键前缀（config/experimental.yml，只读兜底）。 */
    public static final String LEGACY_EXPERIMENTAL_PREFIX = "experimental.";
    /** 覆盖配置文件路径的系统属性（仅供测试隔离使用）。 */
    public static final String PATH_PROPERTY = "xiuxian.plugins.config";

    /** 生效配置：模板默认值 ← 外部文件覆盖。 */
    private static Map<String, String> values;
    /** jar 内模板的平铺键值（文件里缺键时的默认值来源）。 */
    private static final Map<String, String> template;
    /** 旧 experimental.yml 的平铺键值（仅外部文件，缺省为空）。 */
    private static Map<String, String> legacyExperimental;
    /** 新配置文件绝对路径。 */
    private static Path externalPath;
    /** 旧 config/experimental.yml 的路径（只读兜底）。 */
    private static Path legacyExperimentalPath;
    /** 测试用：临时替换旧 application.yml 键的来源（null = 走真实 AppConfig）。 */
    private static Map<String, String> legacyAppValuesOverride;

    static {
        template = Collections.unmodifiableMap(loadClasspathTemplate());
        externalPath = resolveConfigPath();
        if (!Files.exists(externalPath)) {
            extractTemplateFromClasspath(externalPath);
        }
        legacyExperimentalPath = resolveLegacyExperimentalPath(externalPath);
        reload();
        System.out.println("[PluginSwitches] 插件开关已加载: " + externalPath.toAbsolutePath()
                + "（" + values.size() + " 项）");
        if (!legacyExperimental.isEmpty()) {
            System.out.println("[PluginSwitches] 检测到旧 config/experimental.yml，"
                    + "其开关会并入 " + CONFIG_FILE + "（该文件可删除）");
        }
    }

    private PluginSwitches() {
    }

    /** 重新读取外部配置文件与旧 experimental.yml（配置迁移后 / 测试隔离用）。 */
    private static synchronized void reload() {
        values = Collections.unmodifiableMap(loadMergedValues());
        legacyExperimental = Collections.unmodifiableMap(loadFile(legacyExperimentalPath));
    }

    /**
     * 供测试使用：替换配置文件基准路径并重新加载。
     * <p>生产代码永远按 {@link #PATH_PROPERTY}／jar 目录解析，不需要调用本方法。
     */
    static synchronized void useConfigPathsForTest(Path newConfigPath, Path legacyExperimentalFile) {
        externalPath = newConfigPath;
        legacyExperimentalPath = legacyExperimentalFile;
        reload();
    }

    /**
     * 供测试使用：临时替换“旧 application.yml 键”的来源（{@code built_in_plugins.*}），
     * 避免测试去改动仓库里真实的 config/application.yml。
     */
    static synchronized void useLegacyAppValuesForTest(Map<String, String> legacyAppValues) {
        legacyAppValuesOverride = legacyAppValues == null ? null : new LinkedHashMap<>(legacyAppValues);
    }

    /** 配置文件实际路径（测试可用系统属性 {@link #PATH_PROPERTY} 覆盖）。 */
    public static Path getConfigPath() {
        return externalPath;
    }

    private static Path resolveConfigPath() {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return java.nio.file.Paths.get(override).toAbsolutePath();
        }
        return AppConfig.getJarDir().resolve("config").resolve(CONFIG_FILE);
    }

    private static Path resolveLegacyExperimentalPath(Path newConfigPath) {
        Path parent = newConfigPath.getParent();
        return parent == null
                ? java.nio.file.Paths.get("config", "experimental.yml")
                : parent.resolve("experimental.yml");
    }

    private static void extractTemplateFromClasspath(Path target) {
        try (InputStream in = PluginSwitches.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in == null) {
                System.out.println("[PluginSwitches] 内置 " + CONFIG_FILE + " 不存在，跳过自动释放（使用插件默认值）");
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[PluginSwitches] 已释放配置文件: " + target.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[PluginSwitches] 释放 " + CONFIG_FILE + " 失败: " + e.getMessage());
        }
    }

    private static Map<String, String> loadClasspathTemplate() {
        try (InputStream in = PluginSwitches.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            return load(in);
        } catch (Exception e) {
            System.err.println("[PluginSwitches] 读取内置 " + CONFIG_FILE + " 失败: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> loadFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return Collections.emptyMap();
        }
        try (InputStream in = new FileInputStream(path.toFile())) {
            return load(in);
        } catch (Exception e) {
            System.err.println("[PluginSwitches] 读取 " + path + " 失败: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 合并 jar 内模板与外部文件，得到当前生效值。
     * <p>结果缓存在 {@link #values} 中，由 {@link #reload()} 在启动、写回与迁移后刷新；
     * 手工编辑 {@code plugins.yml} 需重启服务端生效（与其它启动期配置一致）。
     */
    private static Map<String, String> loadMergedValues() {
        Map<String, String> merged = new LinkedHashMap<>(template);
        merged.putAll(loadFile(externalPath));
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(InputStream in) {
        Map<String, String> result = new LinkedHashMap<>();
        if (in == null) {
            return result;
        }
        try {
            Object raw = new Yaml().load(in);
            if (raw instanceof Map) {
                flatten((Map<String, Object>) raw, "", result);
            }
        } catch (Exception e) {
            System.err.println("[PluginSwitches] 解析 YAML 失败: " + e.getMessage());
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
                result.put(key, value != null ? value.toString() : "");
            }
        }
    }

    // ==================== 读取 ====================

    /** 新配置文件中的原始值（含模板默认），未配置返回 null。 */
    public static String getRaw(String key) {
        return values.get(KEY_PREFIX + key);
    }

    /**
     * 解析插件开关最终生效值：新文件 → 旧 application.yml 键 → 旧 experimental.yml 键 → 默认值。
     *
     * @param key         插件名（新文件中的键，如 {@code experimental_sect_war}）
     * @param defaultValue 插件自身默认值
     */
    public static boolean isEnabled(String key, boolean defaultValue) {
        return resolve(key, defaultValue).enabled;
    }

    /**
     * 解析插件开关并附带来源信息（供 WebUI 提示“该开关来自旧配置，重启后请迁移”）。
     *
     * @param key          插件名
     * @param defaultValue 插件自身默认值
     */
    public static Resolved resolve(String key, boolean defaultValue) {
        String raw = values.get(KEY_PREFIX + key);
        if (raw != null) {
            return new Resolved(parse(raw, defaultValue), Source.CONFIG_FILE);
        }
        String legacy = legacyValue(key);
        if (legacy != null) {
            return new Resolved(parse(legacy, defaultValue), Source.LEGACY);
        }
        return new Resolved(defaultValue, Source.DEFAULT);
    }

    /** 旧键只读兜底查询：application.yml 的 built_in_plugins.<key> 或 experimental.yml 的 experimental.<key>。 */
    public static String legacyValue(String key) {
        String legacy = readLegacyAppValue(key);
        if (legacy != null && !legacy.isBlank()) {
            return legacy;
        }
        return legacyExperimental.get(LEGACY_EXPERIMENTAL_PREFIX + key);
    }

    /** 读取旧 application.yml 里的 built_in_plugins.<key>（测试可临时替换来源）。 */
    private static String readLegacyAppValue(String key) {
        Map<String, String> override = legacyAppValuesOverride;
        if (override != null) {
            return override.get(LEGACY_KEY_PREFIX + key);
        }
        return AppConfig.get(LEGACY_KEY_PREFIX + key);
    }

    /** 是否存在任何旧键兜底配置（启动日志用）。 */
    public static boolean hasLegacyKeys() {
        return !legacyExperimental.isEmpty()
                || (legacyAppValuesOverride != null
                        ? !legacyAppValuesOverride.isEmpty()
                        : AppConfig.hasKeyPrefix(LEGACY_KEY_PREFIX));
    }

    private static boolean parse(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(raw.trim());
    }

    // ==================== 写入 ====================

    /**
     * 把单个插件开关写回 {@code config/plugins.yml}（行级替换，保留注释与排版）。
     *
     * @return true 表示本文件的值已发生变化
     */
    public static synchronized boolean set(String key, boolean enabled) {
        boolean changed = YamlFileEditor.update(externalPath,
                Collections.singletonMap(KEY_PREFIX + key.toLowerCase(), enabled));
        reload();
        return changed;
    }

    /**
     * 首次启动的旧配置迁移：把旧 {@code application.yml} 的 built_in_plugins.*
     * 与旧 {@code config/experimental.yml} 中<b>实际存在</b>的开关写入本文件。
     * <p>
     * 只迁移“旧配置里确实写过的”键，因此：
     * <ul>
     *   <li>全新安装（没有旧键）不会写出多余条目；</li>
     *   <li>老安装被用户手工关掉的模块会带着 false 一起迁移过来，升级不丢设置；</li>
     *   <li>旧文件依旧保留（只读兜底），不做删除。</li>
     * </ul>
     *
     * @param knownPluginNames 已知插件名（通常来自 {@link PluginCatalog}），
     *                         用于判断哪些旧键值得迁移；传空集合则只迁移旧 experimental.yml 的键
     * @return 迁移的键数量
     */
    public static synchronized int migrateLegacyKeys(Collection<String> knownPluginNames) {
        Map<String, Object> pending = new LinkedHashMap<>();

        // 旧 config/experimental.yml 的 experimental.* 键：键名即插件名（旧键 ↔ 新键一一对应）
        for (Map.Entry<String, String> entry : legacyExperimental.entrySet()) {
            String flat = entry.getKey();
            if (!flat.startsWith(LEGACY_EXPERIMENTAL_PREFIX)) {
                continue;
            }
            String key = flat.substring(LEGACY_EXPERIMENTAL_PREFIX.length()).toLowerCase();
            if (!key.contains(".")) {
                pending.put(KEY_PREFIX + key, parse(entry.getValue(), false));
            }
        }

        // 旧 application.yml 的 built_in_plugins.* 键：按已知插件名逐个回查（AppConfig 不可枚举）
        if (knownPluginNames != null) {
            for (String name : knownPluginNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                String key = name.toLowerCase();
                if (pending.containsKey(KEY_PREFIX + key)) {
                    continue;
                }
                String legacy = readLegacyAppValue(key);
                if (legacy != null && !legacy.isBlank()) {
                    pending.put(KEY_PREFIX + key, parse(legacy, true));
                }
            }
        }

        if (pending.isEmpty()) {
            return 0;
        }
        try {
            int migrated = YamlFileEditor.update(externalPath, pending) ? pending.size() : 0;
            if (migrated > 0) {
                System.out.println("[PluginSwitches] 已把 " + migrated + " 项旧插件开关迁移到 "
                        + externalPath.toAbsolutePath() + "（旧键保留为只读兜底，可自行清理）");
                reload();
            }
            return migrated;
        } catch (Exception e) {
            System.err.println("[PluginSwitches] 旧插件开关迁移失败（本次仍按旧键兜底生效）: " + e.getMessage());
            return 0;
        }
    }

    /** 解析结果：最终值 + 取值来源。 */
    public static final class Resolved {
        private final boolean enabled;
        private final Source source;

        Resolved(boolean enabled, Source source) {
            this.enabled = enabled;
            this.source = source;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Source getSource() {
            return source;
        }
    }

    /** 开关取值来源。 */
    public enum Source {
        /** 本文件 plugins.yml（或在其中的模板默认）。 */
        CONFIG_FILE,
        /** 旧键只读兜底（application.yml 的 built_in_plugins.* 或 experimental.yml）。 */
        LEGACY,
        /** 插件自身默认值。 */
        DEFAULT
    }
}
