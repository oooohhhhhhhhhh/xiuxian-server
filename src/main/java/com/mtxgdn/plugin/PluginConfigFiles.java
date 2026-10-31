package com.mtxgdn.plugin;

import com.mtxgdn.util.AppConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 插件独立配置文件 —— 每个插件一份 {@code config/plugins/<name>.yml}。
 *
 * <p><b>为什么要有这一层</b>：早先插件的开关在 {@code config/plugins.yml}（所有插件挤在一个
 * 文件里的一行 true/false），而各插件的可调参数又散落在 {@code application.yml} 的
 * {@code market} / {@code sect} / {@code world_boss} / {@code buff} / {@code inventory} /
 * {@code breakthrough} / {@code equipment} / {@code season} 等段里。
 * 想调"世界 Boss 的血量倍率"得先去一个 410 行的全局文件里找到 {@code world_boss} 段，
 * 而"这个模块到底能不能关"却在另一个文件 —— 两处割裂，且没有一处能完整回答
 * "world_boss 这个插件有哪些可调项"。</p>
 *
 * <p>现在每个插件一份文件，形如：</p>
 * <pre>
 *   # config/plugins/world_boss.yml
 *   enabled: true
 *   scope: global
 *   boss_count: 3
 *   hp_multiplier: 12
 * </pre>
 *
 * <p><b>兼容策略（关键）</b>：{@code AppConfig.get("world_boss.hp_multiplier")} 的调用点
 * 一行都不用改。本类在 {@link AppConfig} 的查找链里插了一层：
 * 插件文件 &gt; application.yml。这样 120 多个既有调用点、{@code -D} 覆盖、
 * 旧键兜底全部继续有效，而参数的实际归属变成了"哪个插件"。</p>
 *
 * <p><b>通用设置仍留在 application.yml</b>：{@code server} / {@code database} /
 * {@code jwt} / {@code logging} / {@code admin} / {@code smtp} / {@code network} /
 * {@code performance} 这些不属于任何单个插件，拆出去只会让运维找不到。
 * 只有"关掉某个插件时跟着一起失去意义"的参数才搬进插件文件。</p>
 *
 * <p><b>取值优先级</b>（高 → 低）：</p>
 * <ol>
 *   <li>{@code -D} 系统属性（运维应急，必须最高）</li>
 *   <li>运行期覆盖（{@link AppConfig#setRuntimeOverride}）</li>
 *   <li>{@code config/plugins/<name>.yml}（本类）</li>
 *   <li>{@code config/application.yml}</li>
 *   <li>jar 内模板</li>
 * </ol>
 *
 * <p>注意本类<b>不缓存</b>外部文件的解析结果 —— 由 {@link #reload()} 在启动时
 * 一次性读入内存快照，与 {@link AppConfig} 的语义保持一致（改文件需重启，
 * 或由 WebUI 写回后调 {@link #reload()}）。</p>
 */
public final class PluginConfigFiles {

    /** 插件配置文件所在子目录（相对 config/）。 */
    public static final String SUBDIR = "plugins";
    /** 每个插件文件里的开关键名。 */
    public static final String ENABLED_KEY = "enabled";

    /**
     * 参数归属表：配置键前缀 → 插件名。
     *
     * <p>这张表是"哪个参数属于哪个插件"的<b>唯一事实来源</b>。新增参数时在这里登记一次，
     * 拆分配置文件、WebUI 展示、校验覆盖率都读它。</p>
     *
     * <p>刻意用"前缀"而不是逐键枚举：{@code world_boss.*} 整体属于 world_boss 插件，
     * 将来给它加一个 {@code world_boss.new_option} 会自动归位，不必再改这张表。</p>
     */
    private static final Map<String, String> PREFIX_OWNER = buildPrefixOwner();

    private static Map<String, String> buildPrefixOwner() {
        Map<String, String> map = new LinkedHashMap<>();
        // ---- 玩法模块：键前缀与插件名一致，一一对应 ----
        map.put("inventory.", "inventory");
        map.put("market.", "trade");
        map.put("sect.", "sect");
        map.put("world_boss.", "world_boss");
        map.put("buff.", "buff");
        map.put("breakthrough.", "cultivation");
        map.put("equipment.", "inventory");
        map.put("season.", "cultivation");
        map.put("wilderness.", "map");
        map.put("onebot.", "onebot");
        map.put("minecraft.", "minecraft");
        return Collections.unmodifiableMap(map);
    }

    /** 插件名 → 该插件的全部配置项（键为去掉插件前缀后的短名）。 */
    private static Map<String, Map<String, String>> values = Collections.emptyMap();
    /** 插件名 → 配置文件路径。 */
    private static Map<String, Path> files = Collections.emptyMap();
    /** config/plugins 目录。 */
    private static Path configDir;
    /** 测试用：覆盖 config/plugins 目录。 */
    private static Path dirOverride;

    static {
        configDir = resolveConfigDir();
        reload();
    }

    private PluginConfigFiles() {
    }

    private static Path resolveConfigDir() {
        if (dirOverride != null) {
            return dirOverride;
        }
        return AppConfig.getJarDir().resolve("config").resolve(SUBDIR);
    }

    /** config/plugins 目录的绝对路径。 */
    public static Path getConfigDir() {
        return configDir;
    }

    /**
     * 重新扫描 {@code config/plugins/*.yml}。
     *
     * <p>启动时自动调用一次；WebUI 写回某个插件的配置后也调它，让改动立刻生效。</p>
     */
    public static synchronized void reload() {
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        Map<String, Path> loadedFiles = new LinkedHashMap<>();
        if (configDir != null && Files.isDirectory(configDir)) {
            try (var stream = Files.list(configDir)) {
                List<Path> ymls = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .sorted()
                        .toList();
                for (Path file : ymls) {
                    String name = stripExtension(file.getFileName().toString());
                    Map<String, String> flat = loadFile(file);
                    if (!flat.isEmpty()) {
                        loaded.put(name, flat);
                        loadedFiles.put(name, file);
                    }
                }
            } catch (Exception e) {
                System.err.println("[PluginConfigFiles] 扫描 " + configDir + " 失败: " + e.getMessage());
            }
        }
        values = Collections.unmodifiableMap(loaded);
        files = Collections.unmodifiableMap(loadedFiles);
    }

    /** 供测试使用：把 config/plugins 目录指向临时位置并重新加载。 */
    static synchronized void useConfigDirForTest(Path dir) {
        dirOverride = dir;
        configDir = dir;
        reload();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadFile(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(file.toFile())) {
            Object raw = new Yaml().load(in);
            if (raw instanceof Map) {
                flatten((Map<String, Object>) raw, "", result);
            }
        } catch (Exception e) {
            System.err.println("[PluginConfigFiles] 解析 " + file + " 失败: " + e.getMessage());
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
            } else if (value instanceof List<?> list) {
                // 与 AppConfig 保持一致：列表转逗号分隔，避免按 split(",") 解析失败
                result.put(key, list.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(",")));
            } else {
                result.put(key, value != null ? value.toString() : "");
            }
        }
    }

    // ==================== 参数归属 ====================

    /**
     * 某个配置键归属哪个插件；不属于任何插件（= 通用设置，留在 application.yml）时返回 null。
     *
     * @param fullKey 完整配置键，如 {@code world_boss.hp_multiplier}
     */
    public static String ownerOf(String fullKey) {
        if (fullKey == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : PREFIX_OWNER.entrySet()) {
            if (fullKey.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 所有已登记归属的插件名。 */
    public static List<String> getOwnerNames() {
        List<String> names = new ArrayList<>(new java.util.LinkedHashSet<>(PREFIX_OWNER.values()));
        Collections.sort(names);
        return names;
    }

    /** 前缀归属表（只读，供 WebUI / 测试核对）。 */
    public static Map<String, String> getPrefixOwnerMap() {
        return PREFIX_OWNER;
    }

    // ==================== 读取 ====================

    /**
     * 查某个完整配置键在插件文件里的值（未配置返回 null）。
     *
     * <p>这是 {@link AppConfig} 接入本类的唯一入口。键形如
     * {@code world_boss.hp_multiplier}，本方法会解析出归属插件 world_boss、
     * 去掉前缀得到短名 hp_multiplier，再去它的文件里找。</p>
     */
    public static String lookup(String fullKey) {
        if (fullKey == null) {
            return null;
        }
        String owner = ownerOf(fullKey);
        if (owner == null) {
            return null;
        }
        Map<String, String> pluginValues = values.get(owner);
        if (pluginValues == null) {
            return null;
        }
        String prefix = prefixFor(owner);
        String shortKey = prefix == null ? fullKey : fullKey.substring(prefix.length());
        return pluginValues.get(shortKey);
    }

    /** 归属插件的键前缀（如 world_boss → "world_boss."）。 */
    private static String prefixFor(String owner) {
        for (Map.Entry<String, String> entry : PREFIX_OWNER.entrySet()) {
            if (entry.getValue().equals(owner)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 某个插件的全部配置项（短名 → 值）；该插件没有文件时返回空 Map。 */
    public static Map<String, String> getPluginValues(String pluginName) {
        if (pluginName == null) {
            return Collections.emptyMap();
        }
        return values.getOrDefault(pluginName.toLowerCase(Locale.ROOT), Collections.emptyMap());
    }

    /** 某个插件的配置文件路径；尚未释放时返回 null。 */
    public static Path getPluginFile(String pluginName) {
        if (pluginName == null) {
            return null;
        }
        return files.get(pluginName.toLowerCase(Locale.ROOT));
    }

    /** 已经有独立配置文件的插件名。 */
    public static List<String> getConfiguredPluginNames() {
        List<String> names = new ArrayList<>(files.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * 读某个插件文件里的开关（{@code enabled:}）。
     *
     * @return 文件里没写 enabled 时返回 null，让调用方回退到插件自身默认值
     */
    public static Boolean readEnabled(String pluginName) {
        String raw = getPluginValues(pluginName).get(ENABLED_KEY);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return "true".equalsIgnoreCase(raw.trim());
    }
}
