package com.mtxgdn.game.collection;

import com.mtxgdn.util.AppConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图鉴奖励配置 —— {@code config/collection.yml}。
 *
 * <p>为什么单独一个文件而不是塞进 {@code config/plugins/collection.yml}：
 * 那个文件是"插件开关 + 平铺标量参数"，由 {@link com.mtxgdn.plugin.PluginConfigFiles}
 * 按 {@code AppConfig} 的键值语义加载，表达不了"分类 → 默认奖励 → 单条覆盖"这种嵌套结构。
 * 这里跟 {@code config/onebot_group_config.yml} 一样，自己读结构化的 YAML。</p>
 *
 * <p>缺失即用代码默认值：新装、老配置升级、文件被删都不会让图鉴功能失效，
 * 只是奖励回到内置默认。改文件后下一次读取自动生效（文件指纹失效，无需重启）。</p>
 *
 * <p>配置形如：</p>
 * <pre>
 * categories:
 *   item: true          # 关掉后该分类不再记录、不再发奖（已记录数据保留）
 *
 * default_reward:       # 全局兜底（可选）
 *   gold: 0
 *
 * rewards:
 *   title:
 *     default: { gold: 1000, spirit_stones: 10 }
 *     completion: { gold: 100000, spirit_stones: 500 }
 *     entries:
 *       arena_champion: { gold: 5000, spirit_stones: 50 }
 * </pre>
 */
public final class CollectionConfig {

    /** 配置文件名（相对 config/ 目录）。 */
    public static final String FILE_NAME = "collection.yml";

    private final Path path;
    private volatile Snapshot snapshot;
    private volatile long cachedStamp = Long.MIN_VALUE;

    /** 生产环境：{@code <jar目录>/config/collection.yml}，不存在时从 jar 内模板释放。 */
    public CollectionConfig() {
        this(defaultPath(), true);
    }

    /** 测试用：直接指定文件，不复制模板。 */
    public static CollectionConfig forTest(Path path) {
        return new CollectionConfig(path, false);
    }

    CollectionConfig(Path path, boolean copyTemplate) {
        this.path = path;
        if (copyTemplate) {
            copyTemplateIfMissing(path);
        }
        reload();
    }

    public static Path defaultPath() {
        return AppConfig.getJarDir().resolve("config").resolve(FILE_NAME);
    }

    public Path getPath() {
        return path;
    }

    /** 重新解析配置文件；失败时保留旧快照并打印告警（配置写错不该让图鉴崩掉）。 */
    public synchronized void reload() {
        Map<String, Object> root = loadRoot();
        if (root == null) {
            if (snapshot == null) {
                snapshot = Snapshot.empty();
            }
            return;
        }
        try {
            snapshot = parse(root);
            try {
                cachedStamp = stampOf(path);
            } catch (IOException ignored) {
                cachedStamp = Long.MIN_VALUE;
            }
        } catch (RuntimeException e) {
            System.err.println("[Collection] 解析 " + path + " 失败，沿用上一份配置: " + e.getMessage());
            if (snapshot == null) {
                snapshot = Snapshot.empty();
            }
        }
    }

    /** 分类是否记录（配置 + 默认 true）。 */
    public boolean isCategoryEnabled(CollectionCategory category) {
        Boolean value = current().categoryEnabled.get(category.getKey());
        return value == null || value;
    }

    /** 某条目的解锁奖励（条目覆盖 > 分类默认 > 全局默认 > 代码默认）。 */
    public CollectionReward rewardFor(CollectionCategory category, String entryKey) {
        Snapshot snap = current();
        CategoryRewards rewards = snap.rewards.get(category.getKey());
        if (rewards != null) {
            CollectionReward entry = rewards.entries.get(entryKey);
            if (entry != null) {
                return entry;
            }
            if (rewards.defaultReward != null) {
                return rewards.defaultReward;
            }
        }
        if (snap.globalDefault != null) {
            return snap.globalDefault;
        }
        return codeDefaultReward(category);
    }

    /** 某分类集齐后的奖励。 */
    public CollectionReward completionReward(CollectionCategory category) {
        Snapshot snap = current();
        CategoryRewards rewards = snap.rewards.get(category.getKey());
        if (rewards != null && rewards.completionReward != null) {
            return rewards.completionReward;
        }
        if (snap.globalCompletion != null) {
            return snap.globalCompletion;
        }
        return codeCompletionReward(category);
    }

    /** 代码内置的解锁奖励兜底（配置文件没写时用）。 */
    static CollectionReward codeDefaultReward(CollectionCategory category) {
        return switch (category) {
            case ITEM -> reward(50, 0, 0);
            case TITLE -> reward(1000, 10, 0);
            case TECHNIQUE -> reward(500, 0, 0);
            case SKILL -> reward(300, 0, 0);
            case SECRET_REALM -> reward(800, 0, 200);
            case EXPLORATION_EVENT -> reward(300, 0, 0);
            case MAP_LOCATION -> reward(200, 0, 0);
            case RECIPE -> reward(400, 0, 0);
        };
    }

    /** 代码内置的集齐奖励兜底。 */
    static CollectionReward codeCompletionReward(CollectionCategory category) {
        return switch (category) {
            case ITEM -> new CollectionReward(20000, 0, 0,
                    Map.of("mtxgdn:cultivation_elixir", 3L));
            case TITLE -> reward(100000, 500, 0);
            case TECHNIQUE -> reward(10000, 0, 0);
            case SKILL -> reward(8000, 0, 0);
            case SECRET_REALM -> reward(20000, 0, 5000);
            case EXPLORATION_EVENT -> reward(15000, 0, 0);
            case MAP_LOCATION -> reward(10000, 0, 0);
            case RECIPE -> reward(12000, 0, 0);
        };
    }

    private static CollectionReward reward(long gold, long stones, long exp) {
        return new CollectionReward(gold, stones, exp, Map.of());
    }

    // ==================== 内部：读取与解析 ====================

    private Snapshot current() {
        if (path != null && Files.exists(path)) {
            try {
                long stamp = stampOf(path);
                if (stamp != cachedStamp) {
                    reload();
                }
            } catch (IOException ignored) {
                // 读不到就继续用旧快照
            }
        }
        Snapshot snap = snapshot;
        return snap == null ? Snapshot.empty() : snap;
    }

    private static long stampOf(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis() * 31L + Files.size(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadRoot() {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return loaded instanceof Map ? (Map<String, Object>) loaded : null;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Collection] 读取 " + path + " 失败: " + e.getMessage());
            return null;
        }
    }

    private static Snapshot parse(Map<String, Object> root) {
        Map<String, Boolean> categoryEnabled = new LinkedHashMap<>();
        Object categoriesRaw = root.get("categories");
        if (categoriesRaw instanceof Map<?, ?> categories) {
            for (Map.Entry<?, ?> entry : categories.entrySet()) {
                if (entry.getKey() != null) {
                    categoryEnabled.put(String.valueOf(entry.getKey()).toLowerCase(),
                            asBoolean(entry.getValue(), true));
                }
            }
        }

        CollectionReward globalDefault = parseReward(root.get("default_reward"));
        CollectionReward globalCompletion = parseReward(root.get("default_completion_reward"));

        Map<String, CategoryRewards> rewards = new LinkedHashMap<>();
        Object rewardsRaw = root.get("rewards");
        if (rewardsRaw instanceof Map<?, ?> rewardMap) {
            for (Map.Entry<?, ?> entry : rewardMap.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> categoryMap)) {
                    continue;
                }
                CollectionReward defaultReward = parseReward(firstNonNull(
                        categoryMap.get("default"), categoryMap.get("default_reward")));
                CollectionReward completionReward = parseReward(firstNonNull(
                        categoryMap.get("completion"), categoryMap.get("completion_reward")));
                Map<String, CollectionReward> entries = new LinkedHashMap<>();
                Object entriesRaw = categoryMap.get("entries");
                if (entriesRaw instanceof Map<?, ?> entryMap) {
                    for (Map.Entry<?, ?> item : entryMap.entrySet()) {
                        if (item.getKey() == null) {
                            continue;
                        }
                        CollectionReward parsed = parseReward(item.getValue());
                        // 显式写 {} 表示"这条不发奖"，与"没配置"要能区分开
                        entries.put(String.valueOf(item.getKey()),
                                parsed == null ? CollectionReward.EMPTY : parsed);
                    }
                }
                rewards.put(String.valueOf(entry.getKey()).toLowerCase(),
                        new CategoryRewards(defaultReward, completionReward, entries));
            }
        }
        return new Snapshot(categoryEnabled, globalDefault, globalCompletion, rewards);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static CollectionReward parseReward(Object raw) {
        if (raw instanceof Number number) {
            // 允许直接写数字：等同只给金币
            return new CollectionReward(Math.max(0, number.longValue()), 0, 0, Map.of());
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        long gold = asLong(map.get("gold"));
        long stones = asLong(firstNonNull(map.get("spirit_stones"), map.get("stones")));
        long exp = asLong(firstNonNull(map.get("exp"), map.get("experience")));
        Map<String, Long> items = new LinkedHashMap<>();
        Object itemsRaw = map.get("items");
        if (itemsRaw instanceof Map<?, ?> itemMap) {
            for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                long amount = asLong(entry.getValue());
                if (amount > 0) {
                    items.put(String.valueOf(entry.getKey()), amount);
                }
            }
        }
        return new CollectionReward(gold, stones, exp, items);
    }

    private static long asLong(Object raw) {
        if (raw instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (raw instanceof String text) {
            try {
                return Math.max(0, Long.parseLong(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean asBoolean(Object raw, boolean fallback) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private static void copyTemplateIfMissing(Path target) {
        if (target == null || Files.exists(target)) {
            return;
        }
        try (InputStream in = CollectionConfig.class.getClassLoader()
                .getResourceAsStream("config/" + FILE_NAME)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            System.out.println("[Collection] 已释放配置文件: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Collection] 释放 " + target + " 失败: " + e.getMessage());
        }
    }

    /** 配置解析结果（不可变快照）。 */
    private record Snapshot(Map<String, Boolean> categoryEnabled,
                            CollectionReward globalDefault,
                            CollectionReward globalCompletion,
                            Map<String, CategoryRewards> rewards) {

        static Snapshot empty() {
            return new Snapshot(Collections.emptyMap(), null, null, Collections.emptyMap());
        }
    }

    /** 单个分类的奖励配置；{@code null} 表示"没配这一档"，回落到全局/代码默认。 */
    private record CategoryRewards(CollectionReward defaultReward,
                                   CollectionReward completionReward,
                                   Map<String, CollectionReward> entries) {

        CategoryRewards {
            entries = entries == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /** 便于指令/REST 展示：分类当前生效的默认解锁奖励与集齐奖励。 */
    public Map<CollectionCategory, List<CollectionReward>> describeAll() {
        Map<CollectionCategory, List<CollectionReward>> result = new EnumMap<>(CollectionCategory.class);
        for (CollectionCategory category : CollectionCategory.values()) {
            if (!isCategoryEnabled(category)) {
                continue;
            }
            result.put(category, new ArrayList<>(List.of(
                    rewardFor(category, ""),
                    completionReward(category))));
        }
        return result;
    }
}
