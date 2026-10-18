package com.mtxgdn.game.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 野外随机地图定义（地图文件）。
 *
 * <p>已知世界（{@code map_locations} 中 {@code is_wilderness = 0} 的 15 个出厂地点）是固定的；
 * 本文件定义<b>已知世界之外</b>的野外 —— 一片<b>由世界种子确定性生成、可以无限向外扩展</b>的
 * 二维分片世界：地形带、命名、每个危险度档位的资源池、补货周期、首达奖励与传送/采集参数。</p>
 *
 * <p>关键性质：野外地形<b>不是</b>随机数堆出来的，而是 {@code (worldSeed, chunkX, chunkY)}
 * 的纯函数（见 {@code WildernessTerrain}）。因此同一片区域无论生成多少次都完全一致，
 * 分片被淘汰后重新生成也还是原来那片地形 —— 这是"探索过的内容不会被刷新掉"能成立的前提。
 * 本文件只提供生成参数，不参与生成过程本身。</p>
 *
 * <p>加载顺序：先读 {@code <jar 目录>/config/game/wilderness_map.json}（用户可改），
 * 读不到再退回 jar 内的同名资源；两者都读不到则用类内默认值并告警。
 * 因此删掉配置文件不会让服务起不来，只会失去自定义。</p>
 */
public class WildernessMapConfig {

    private static final GameLogger LOG = GameLogger.getLogger(WildernessMapConfig.class);
    private static final Gson GSON = new Gson();

    /** jar 内资源路径（同时决定释放到 config/ 下的相对路径）。 */
    public static final String RESOURCE_PATH = "game/wilderness_map.json";

    /** 旧版（固定槽位整批刷新）用过的字段名；读到就提示管理员删文件重新释放。 */
    private static final String[] LEGACY_FIELDS = {
            "idBase", "nodeCount", "refreshIntervalMinutes", "regions",
            "namePrefixes", "nameSuffixes", "descriptions"
    };

    private static volatile WildernessMapConfig instance;

    // ==================== 字段（与 wilderness_map.json 一一对应） ====================

    private boolean enabled = true;

    /** 世界种子：改它等于换一个全新的无限世界（已探索分片会与新地形对不上，慎改）。 */
    private long worldSeed = 20261015L;
    /** 每个分片 chunkSize × chunkSize 个节点。上限 8：id 编码里每片固定预留 64 个槽位。 */
    private int chunkSize = 4;
    /** 距原点（已知世界）的最大分片距离；4 格/片 × 256 片 = 1024 格。 */
    private int maxChunksFromOrigin = 256;
    /** 未探索分片空闲多久后被淘汰（分钟）。已探索分片<b>永不</b>淘汰。 */
    private int evictAfterMinutes = 1440;
    /** 资源原地补货周期（分钟）。注意是"补货"而不是"换地形"。 */
    private int restockIntervalMinutes = 180;

    private int travelTimeSeconds = 20;
    private double cultivationBonusBase = 1.0;
    private double cultivationBonusPerDanger = 0.08;
    private double explorationBonusBase = 1.15;
    private double explorationBonusPerDanger = 0.15;

    /** 每远离原点多少分片，危险度 +1。 */
    private int dangerPerChunks = 12;
    /** 每个危险度档位对应多少境界门槛。 */
    private int minRealmPerDanger = 3;
    /** 高程噪声尺度（分片）：越大则地形带越宽阔。 */
    private int elevationScale = 24;
    /** 湿度噪声尺度（分片）。 */
    private int moistureScale = 17;
    /** 跨分片边（相邻两片之间的通道）出现概率。 */
    private int borderEdgeChancePercent = 45;
    /** 分片内除生成树之外额外成环的边概率。 */
    private int extraEdgeChancePercent = 25;

    private List<Long> entryLocationIds = new ArrayList<>(List.of(5L, 7L, 8L, 12L, 15L));
    private List<BiomeSeed> biomes = new ArrayList<>();
    private Map<String, List<ResourceSeed>> resourcePools = new LinkedHashMap<>();

    private FirstVisitReward firstVisitReward = new FirstVisitReward();
    private Teleport teleport = new Teleport();
    private Gather gather = new Gather();

    /**
     * 地形（biome）。
     *
     * <p>用高程/湿度两个噪声场的取值区间来描述，生成时<b>按声明顺序取第一个命中的</b>，
     * 所以最后一条应当是一个覆盖全域的兜底地形（默认是灵田平原）。
     * 这样"地形带"是连续的：相邻分片的噪声值接近，命中的地形自然也就相同。</p>
     */
    public static class BiomeSeed {
        private String key = "plain";
        private String name = "灵田平原";
        /** 该地形的基础危险度；最终危险度还会叠加"离原点距离"的增量。 */
        private int danger = 1;
        private double elevationMin = -1.0;
        private double elevationMax = 1.0;
        private double moistureMin = -1.0;
        private double moistureMax = 1.0;
        /** 指定专用资源池键；为空则按最终危险度取 resourcePools 的对应档位。 */
        private String resourcePool;

        private List<String> namePrefixes = new ArrayList<>();
        private List<String> nameSuffixes = new ArrayList<>();
        private List<String> descriptions = new ArrayList<>();

        /**
         * 地形名与描述的词条 key。
         *
         * <p>{@code nameKey} 指向地形名（如 {@code wilderness.biome.plain.name}）；
         * {@code descKeys} 与 {@code descriptions} 逐位对应，第 i 条描述的 key
         * 放在第 i 位——某一位为空或查不到时回落到同位置的 {@code descriptions}。</p>
         *
         * <p>前缀/后缀（{@code namePrefixes}/{@code nameSuffixes}）不参与 i18n：
         * 它们是程序化地名的构词材料，混着不同语言的词根拼出来的地名会很难看。
         * 要换语言的地名风格，应当整份换掉这个地形定义。</p>
         */
        private String nameKey;
        private List<String> descKeys = new ArrayList<>();

        public String getKey() { return key; }

        /** 本地化后的地形名；无 key 或查不到时回落到 {@code name} 字面量。 */
        public String getName() {
            return nameKey == null || nameKey.isBlank() ? name : I18n.tOr(nameKey, name);
        }

        /** 原始中文地形名，不经过本地化。 */
        public String getRawName() { return name; }

        public String getNameKey() { return nameKey; }
        public List<String> getDescKeys() { return descKeys; }
        public int getDanger() { return danger; }
        public double getElevationMin() { return elevationMin; }
        public double getElevationMax() { return elevationMax; }
        public double getMoistureMin() { return moistureMin; }
        public double getMoistureMax() { return moistureMax; }
        public String getResourcePool() { return resourcePool; }
        public List<String> getNamePrefixes() { return namePrefixes; }
        public List<String> getNameSuffixes() { return nameSuffixes; }

        /**
         * 本地化后的描述模板列表，逐位回落到 {@code descriptions}。
         *
         * <p>模板里的 {@code {biome}} 占位符由调用方替换成地形名——
         * 注意这里用花括号而不是 {@code {0}}：它不是 {@code I18n.format}
         * 的位置参数，而是地名生成器自己的占位符，两者不能混用。</p>
         */
        public List<String> getDescriptions() {
            if (descKeys == null || descKeys.isEmpty()) {
                return descriptions;
            }
            List<String> resolved = new ArrayList<>(descriptions.size());
            for (int i = 0; i < descriptions.size(); i++) {
                String literal = descriptions.get(i);
                String k = i < descKeys.size() ? descKeys.get(i) : null;
                resolved.add(k == null || k.isBlank() ? literal : I18n.tOr(k, literal));
            }
            return resolved;
        }

        /** 原始中文描述，不经过本地化。 */
        public List<String> getRawDescriptions() { return descriptions; }

        /** 该地形是否覆盖给定的高程/湿度。 */
        public boolean covers(double elevation, double moisture) {
            return elevation >= elevationMin && elevation <= elevationMax
                    && moisture >= moistureMin && moisture <= moistureMax;
        }
    }

    /** 资源池条目：某档位可刷出的物品及其数量区间。 */
    public static class ResourceSeed {
        private String itemKey;
        private int weight = 1;
        private int minQuantity = 1;
        private int maxQuantity = 1;

        public String getItemKey() { return itemKey; }
        public int getWeight() { return weight; }
        public int getMinQuantity() { return minQuantity; }
        public int getMaxQuantity() { return maxQuantity; }
    }

    /** 首达奖励：基础值 + 危险度 × 每级值；野外再乘野外倍率。 */
    public static class FirstVisitReward {
        private long spiritStonesBase = 20;
        private long spiritStonesPerDanger = 30;
        private double spiritStonesWildernessMultiplier = 2.0;
        private long experienceBase = 50;
        private long experiencePerDanger = 80;
        private double experienceWildernessMultiplier = 2.0;
        private long completionBonusSpiritStones = 2000;

        public long getSpiritStonesBase() { return spiritStonesBase; }
        public long getSpiritStonesPerDanger() { return spiritStonesPerDanger; }
        public double getSpiritStonesWildernessMultiplier() { return spiritStonesWildernessMultiplier; }
        public long getExperienceBase() { return experienceBase; }
        public long getExperiencePerDanger() { return experiencePerDanger; }
        public double getExperienceWildernessMultiplier() { return experienceWildernessMultiplier; }
        public long getCompletionBonusSpiritStones() { return completionBonusSpiritStones; }
    }

    /** 传送参数。 */
    public static class Teleport {
        private boolean enabled = true;
        private long baseCost = 50;
        private long costPerDanger = 60;
        private double wildernessSurcharge = 1.5;
        private long minCost = 10;

        public boolean isEnabled() { return enabled; }
        public long getBaseCost() { return baseCost; }
        public long getCostPerDanger() { return costPerDanger; }
        public double getWildernessSurcharge() { return wildernessSurcharge; }
        public long getMinCost() { return minCost; }
    }

    /** 采集参数。 */
    public static class Gather {
        private int cooldownSeconds = 60;
        private int maxPerGather = 3;

        public int getCooldownSeconds() { return cooldownSeconds; }
        public int getMaxPerGather() { return maxPerGather; }
    }

    // ==================== 加载 ====================

    /** 取配置（首次访问时加载）。 */
    public static WildernessMapConfig get() {
        WildernessMapConfig local = instance;
        if (local == null) {
            synchronized (WildernessMapConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** 丢弃缓存，下次访问重新读文件（管理台改完地图文件后调用）。 */
    public static void reload() {
        synchronized (WildernessMapConfig.class) {
            instance = load();
        }
    }

    private static WildernessMapConfig load() {
        WildernessMapConfig config = readFromDisk();
        if (config == null) {
            config = readFromClasspath();
        }
        if (config == null) {
            LOG.warn("未找到野外地图文件（" + RESOURCE_PATH + "），使用内置默认值");
            config = new WildernessMapConfig();
        }
        config.normalize();
        return config;
    }

    private static WildernessMapConfig readFromDisk() {
        Path path = AppConfig.getJarDir().resolve("config").resolve(RESOURCE_PATH);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            WildernessMapConfig config = parse(json, path.toAbsolutePath().toString());
            if (config != null) {
                LOG.info("已加载野外地图文件: " + path.toAbsolutePath());
            }
            return config;
        } catch (Exception e) {
            LOG.error("读取野外地图文件失败，将退回内置默认值: " + path.toAbsolutePath(), e);
            return null;
        }
    }

    private static WildernessMapConfig readFromClasspath() {
        try (InputStream in = WildernessMapConfig.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json, "jar 内 " + RESOURCE_PATH);
        } catch (Exception e) {
            LOG.error("读取 jar 内野外地图资源失败", e);
            return null;
        }
    }

    /**
     * 解析 JSON，并对旧版字段给出明确提示。
     *
     * <p>配置文件是"只释放不覆盖"的，所以升级上来的部署一定还留着旧版文件。
     * 旧字段会被 Gson 静默忽略、整份配置退回默认值 —— 那种"改了没生效也不报错"
     * 的体验最难排查，因此这里主动点名。</p>
     */
    private static WildernessMapConfig parse(String json, String source) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<String> legacy = new ArrayList<>();
        for (String field : LEGACY_FIELDS) {
            if (root.has(field)) {
                legacy.add(field);
            }
        }
        if (!legacy.isEmpty()) {
            LOG.warn("野外地图文件仍是旧版结构（" + source + "），下列字段已废弃且会被忽略: "
                    + String.join("、", legacy)
                    + "；建议删除该文件让服务重新释放新版（地形改为世界种子确定性生成）");
        }
        return GSON.fromJson(root, WildernessMapConfig.class);
    }

    /**
     * 修正非法值。
     *
     * <p>刻意在这里兜底而不是抛异常：配置文件是给管理员手改的，写错一个数字
     * 不该让整个野外系统（乃至服务启动）失败。修正时打告警，便于发现写错。</p>
     */
    private void normalize() {
        if (chunkSize < 1) {
            LOG.warn("野外地图 chunkSize=" + chunkSize + " 非法，已改为 4");
            chunkSize = 4;
        } else if (chunkSize > 8) {
            // id 编码里每个分片固定预留 64 个槽位，chunkSize 超过 8 会越界串到下一片
            LOG.warn("野外地图 chunkSize=" + chunkSize + " 超过上限 8（每片最多 64 个节点），已收敛为 8");
            chunkSize = 8;
        }
        if (maxChunksFromOrigin < 1) {
            LOG.warn("野外地图 maxChunksFromOrigin=" + maxChunksFromOrigin + " 非法，已改为 256");
            maxChunksFromOrigin = 256;
        } else if (maxChunksFromOrigin > 100000) {
            LOG.warn("野外地图 maxChunksFromOrigin=" + maxChunksFromOrigin + " 过大，已收敛为 100000");
            maxChunksFromOrigin = 100000;
        }
        if (evictAfterMinutes < 1) {
            LOG.warn("野外地图 evictAfterMinutes=" + evictAfterMinutes + " 非法，已改为 1440");
            evictAfterMinutes = 1440;
        }
        if (restockIntervalMinutes < 1) {
            LOG.warn("野外地图 restockIntervalMinutes=" + restockIntervalMinutes + " 非法，已改为 180");
            restockIntervalMinutes = 180;
        }
        if (travelTimeSeconds < 1) {
            travelTimeSeconds = 20;
        }
        if (dangerPerChunks < 1) {
            dangerPerChunks = 12;
        }
        if (minRealmPerDanger < 0) {
            minRealmPerDanger = 0;
        }
        if (elevationScale < 1) {
            elevationScale = 24;
        }
        if (moistureScale < 1) {
            moistureScale = 17;
        }
        borderEdgeChancePercent = clampPercent(borderEdgeChancePercent, "borderEdgeChancePercent", 45);
        extraEdgeChancePercent = clampPercent(extraEdgeChancePercent, "extraEdgeChancePercent", 25);

        if (entryLocationIds == null || entryLocationIds.isEmpty()) {
            LOG.warn("野外地图 entryLocationIds 为空，已退回默认边境地点 5/7/8/12/15");
            entryLocationIds = new ArrayList<>(List.of(5L, 7L, 8L, 12L, 15L));
        }
        normalizeBiomes();
        normalizeResourcePools();

        if (firstVisitReward == null) {
            firstVisitReward = new FirstVisitReward();
        }
        if (teleport == null) {
            teleport = new Teleport();
        }
        if (gather == null) {
            gather = new Gather();
        }
        if (gather.cooldownSeconds < 0) {
            gather.cooldownSeconds = 0;
        }
        if (gather.maxPerGather < 1) {
            gather.maxPerGather = 1;
        }
    }

    private static int clampPercent(int value, String field, int fallback) {
        if (value < 0 || value > 100) {
            LOG.warn("野外地图 " + field + "=" + value + " 应在 0~100，已改为 " + fallback);
            return fallback;
        }
        return value;
    }

    private void normalizeBiomes() {
        if (biomes == null || biomes.isEmpty()) {
            LOG.warn("野外地图 biomes 为空，已退回内置地形表");
            biomes = defaultBiomes();
            return;
        }
        for (BiomeSeed biome : biomes) {
            if (biome.key == null || biome.key.isBlank()) {
                biome.key = "biome";
            }
            if (biome.name == null || biome.name.isBlank()) {
                biome.name = biome.key;
            }
            biome.danger = Math.max(1, Math.min(4, biome.danger));
            biome.elevationMin = clampUnit(biome.elevationMin);
            biome.elevationMax = clampUnit(biome.elevationMax);
            biome.moistureMin = clampUnit(biome.moistureMin);
            biome.moistureMax = clampUnit(biome.moistureMax);
            if (biome.elevationMax < biome.elevationMin) {
                double swap = biome.elevationMin;
                biome.elevationMin = biome.elevationMax;
                biome.elevationMax = swap;
            }
            if (biome.moistureMax < biome.moistureMin) {
                double swap = biome.moistureMin;
                biome.moistureMin = biome.moistureMax;
                biome.moistureMax = swap;
            }
            if (biome.namePrefixes == null || biome.namePrefixes.isEmpty()) {
                biome.namePrefixes = new ArrayList<>(List.of("荒", "幽", "枯", "断", "寒"));
            }
            if (biome.nameSuffixes == null || biome.nameSuffixes.isEmpty()) {
                biome.nameSuffixes = new ArrayList<>(List.of("野", "谷", "林", "泽", "峡"));
            }
            if (biome.descriptions == null || biome.descriptions.isEmpty()) {
                biome.descriptions = new ArrayList<>(List.of("一片无人测绘过的{biome}。"));
            }
        }
        // 地形表必须能覆盖整个 (高程, 湿度) 平面，否则会有一大片区域生成不出节点
        boolean coversAll = biomes.stream().anyMatch(b -> b.covers(0, 0) || b.covers(1, 1) || b.covers(-1, -1));
        if (!coversAll) {
            LOG.warn("野外地图 biomes 未覆盖全域（缺兜底地形），已追加默认灵田平原");
            BiomeSeed fallback = defaultBiomes().get(defaultBiomes().size() - 1);
            biomes.add(fallback);
        }
    }

    private static double clampUnit(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private void normalizeResourcePools() {
        if (resourcePools == null) {
            resourcePools = new LinkedHashMap<>();
        }
        // 资源池里出现空 itemKey 会让采集发不出物品，提前剔除并告警
        for (Map.Entry<String, List<ResourceSeed>> entry : resourcePools.entrySet()) {
            List<ResourceSeed> pool = entry.getValue();
            if (pool == null) {
                entry.setValue(new ArrayList<>());
                continue;
            }
            pool.removeIf(seed -> {
                if (seed == null || seed.itemKey == null || seed.itemKey.isBlank()) {
                    LOG.warn("野外地图资源池 " + entry.getKey() + " 存在空 itemKey，已剔除");
                    return true;
                }
                if (seed.maxQuantity < seed.minQuantity) {
                    seed.maxQuantity = seed.minQuantity;
                }
                if (seed.minQuantity < 1) {
                    seed.minQuantity = 1;
                }
                if (seed.weight < 1) {
                    seed.weight = 1;
                }
                return false;
            });
        }
    }

    /** 内置地形表：前 5 条是具体地形，最后一条覆盖全域兜底。 */
    private static List<BiomeSeed> defaultBiomes() {
        List<BiomeSeed> list = new ArrayList<>();
        list.add(biome("marsh", "幽冥沼泽", 3, -1.0, -0.25, 0.1, 1.0,
                List.of("幽", "沼", "阴", "腐"),
                List.of("泽", "沼", "洼", "汀"),
                List.of("{biome}的瘴气贴着地面翻涌，脚下的泥沼冒着气泡。")));
        list.add(biome("snow", "寒霜雪原", 3, 0.35, 1.0, -1.0, 1.0,
                List.of("寒", "霜", "冰", "雪"),
                List.of("原", "岭", "川", "崖"),
                List.of("{biome}上终年不化的积雪把日光反射得刺眼，风里带着冰碴。")));
        list.add(biome("volcano", "赤炎火山", 4, 0.1, 0.35, -1.0, -0.25,
                List.of("赤", "炎", "熔", "焦"),
                List.of("岭", "谷", "窟", "壑"),
                List.of("{biome}的岩缝里透出暗红的光，空气被烤得扭曲。")));
        list.add(biome("desert", "流沙荒漠", 2, -1.0, 0.35, -1.0, -0.25,
                List.of("流", "黄", "枯", "旱"),
                List.of("漠", "丘", "碛", "滩"),
                List.of("{biome}的沙丘一直铺到天边，风过处只留下移动的沙脊。")));
        list.add(biome("forest", "幽暗密林", 2, -0.25, 0.35, 0.1, 1.0,
                List.of("幽", "苍", "古", "青"),
                List.of("林", "森", "涧", "麓"),
                List.of("{biome}的树冠遮住了天光，只有零星的绿光在枝叶间游走。")));
        list.add(biome("plain", "灵田平原", 1, -1.0, 1.0, -1.0, 1.0,
                List.of("青", "平", "丰", "沃"),
                List.of("原", "野", "田", "畴"),
                List.of("{biome}在脚下铺开，成片的灵草随着地势起伏。")));
        return list;
    }

    private static BiomeSeed biome(String key, String name, int danger,
                                   double elevationMin, double elevationMax,
                                   double moistureMin, double moistureMax,
                                   List<String> prefixes, List<String> suffixes, List<String> descriptions) {
        BiomeSeed seed = new BiomeSeed();
        seed.key = key;
        seed.name = name;
        seed.danger = danger;
        seed.elevationMin = elevationMin;
        seed.elevationMax = elevationMax;
        seed.moistureMin = moistureMin;
        seed.moistureMax = moistureMax;
        seed.namePrefixes = new ArrayList<>(prefixes);
        seed.nameSuffixes = new ArrayList<>(suffixes);
        seed.descriptions = new ArrayList<>(descriptions);
        return seed;
    }

    /** 该危险度档位的资源池；无对应档位时回退到不高于它的最近档位。 */
    public List<ResourceSeed> getResourcePool(int dangerLevel) {
        List<ResourceSeed> pool = resourcePools.get(String.valueOf(dangerLevel));
        if (pool != null && !pool.isEmpty()) {
            return pool;
        }
        for (int level = dangerLevel - 1; level >= 1; level--) {
            List<ResourceSeed> fallback = resourcePools.get(String.valueOf(level));
            if (fallback != null && !fallback.isEmpty()) {
                return fallback;
            }
        }
        return new ArrayList<>();
    }

    /** 某地形 + 危险度实际使用的资源池：地形指定了专用池就用它，否则按危险度档位。 */
    public List<ResourceSeed> getResourcePool(String biomeKey, int dangerLevel) {
        if (biomeKey != null) {
            List<ResourceSeed> dedicated = resourcePools.get(biomeKey);
            if (dedicated != null && !dedicated.isEmpty()) {
                return dedicated;
            }
        }
        return getResourcePool(dangerLevel);
    }

    // ==================== 只读访问 ====================

    public boolean isEnabled() { return enabled; }
    public long getWorldSeed() { return worldSeed; }
    public int getChunkSize() { return chunkSize; }
    public int getMaxChunksFromOrigin() { return maxChunksFromOrigin; }
    public int getEvictAfterMinutes() { return evictAfterMinutes; }
    public int getRestockIntervalMinutes() { return restockIntervalMinutes; }
    public int getTravelTimeSeconds() { return travelTimeSeconds; }
    public double getCultivationBonusBase() { return cultivationBonusBase; }
    public double getCultivationBonusPerDanger() { return cultivationBonusPerDanger; }
    public double getExplorationBonusBase() { return explorationBonusBase; }
    public double getExplorationBonusPerDanger() { return explorationBonusPerDanger; }
    public int getDangerPerChunks() { return dangerPerChunks; }
    public int getMinRealmPerDanger() { return minRealmPerDanger; }
    public int getElevationScale() { return elevationScale; }
    public int getMoistureScale() { return moistureScale; }
    public int getBorderEdgeChancePercent() { return borderEdgeChancePercent; }
    public int getExtraEdgeChancePercent() { return extraEdgeChancePercent; }
    public List<Long> getEntryLocationIds() { return entryLocationIds; }
    public List<BiomeSeed> getBiomes() { return biomes; }
    public FirstVisitReward getFirstVisitReward() { return firstVisitReward; }
    public Teleport getTeleport() { return teleport; }
    public Gather getGather() { return gather; }
}
