package com.mtxgdn.game.map;

import com.mtxgdn.game.config.WildernessMapConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 野外地形生成器：把 {@code (worldSeed, chunkX, chunkY)} 确定性地映射成一片地形。
 *
 * <p><b>这是整个无限野外方案的地基，有两条性质必须守住：</b></p>
 *
 * <ol>
 *   <li><b>纯函数</b>：不读数据库、不用 {@code ThreadLocalRandom}、不依赖系统时间。
 *       同一组坐标无论生成多少次（包括分片被淘汰后重新生成）结果<b>逐字节一致</b>。
 *       正因为如此，"探索过的内容不会被刷新掉"才成立 —— 地形从来没有变过，
 *       变的只是"哪些分片已经落到库里"。</li>
 *   <li><b>id 是坐标的纯函数</b>（{@link #nodeId}）。分片重建后同一个世界位置的 id
 *       与原来完全相同，于是 {@code player_map_exploration}、玩家位置、
 *       {@code map_connections} 的外键全部无需改动即可继续有效。</li>
 * </ol>
 *
 * <h2>id 编码</h2>
 * <pre>
 *   chunkKey = (cx + COORD_BIAS) * COORD_SPAN + (cy + COORD_BIAS)
 *   id       = ID_BASE + chunkKey * SLOT_STRIDE + slot
 * </pre>
 * <p>{@code SLOT_STRIDE} 固定为 64，<b>与配置里的 chunkSize 解耦</b>：若把 stride 写成
 * {@code chunkSize²}，那么管理员把 chunkSize 从 4 改成 8 就会让每个分片的 id 整体平移，
 * 全部探索记录与玩家位置瞬间失效。用固定 stride 则只是每片多留了空槽位，
 * 代价可以忽略。同一分片的 id 落在连续区间内，因此"这片有人探索过吗"是一次 range scan。</p>
 *
 * <h2>地形</h2>
 * <p>高程与湿度两个低频值噪声场决定地形带（见 {@code WildernessMapConfig.biomes}），
 * 噪声做了平滑插值，所以相邻分片地形相近、不会出现"白噪声式"的一格一景。
 * 危险度取"地形基础危险度"与"离原点距离"的较大者 —— 于是越往深处走越险，
 * 无限扩张被境界门槛自然约束，而不需要一堵硬墙。</p>
 */
public final class WildernessTerrain {

    /** chunk 坐标偏移，支持 [-COORD_BIAS, COORD_BIAS) 即约 ±104 万片。 */
    public static final int COORD_BIAS = 1 << 20;
    /** chunk 坐标跨度，与 {@link #COORD_BIAS} 一起把二维坐标压成一个非负 long。 */
    public static final int COORD_SPAN = 1 << 21;
    /** 野外 id 起点：远高于已知世界（1~15）与管理台手工新建的 id。 */
    public static final long ID_BASE = 1_000_000L;
    /** 每个分片在 id 编码里固定占用的槽位数（上限 8×8 = 64 个节点）。 */
    public static final int SLOT_STRIDE = 64;

    /** 危险度上下限，与 {@code MapService.DANGER_NAMES} 的四档一一对应。 */
    public static final int MIN_DANGER = 1;
    public static final int MAX_DANGER = 4;

    /**
     * 入口方位环：{@code entryLocationIds[i]} 落在环上第 i 个分片，
     * 即"野外环绕在已知世界四周"。方向按顺时针排列，改顺序等于改入口方位。
     */
    private static final int[][] GATEWAY_RING = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    /** 名称尾缀用的字表（65 个字，两字组合即 4225 种，足够让地名不撞车）。 */
    private static final String TAG_ALPHABET =
            "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥"
                    + "风云雷雨霜雪雾露岭峰崖洞泉溪江河湖海"
                    + "松柏竹梅兰菊荷柳青赤白玄黄紫苍碧"
                    + "东南西北中阴晴晦明";

    // 噪声盐值：换一个盐值等于换一种地貌分布，但世界种子才是玩家可见的"换个世界"
    private static final long SALT_MOISTURE = 0x9E3779B97F4A7C15L;
    private static final long SALT_INTRA = 0xC2B2AE3D27D4EB4FL;
    private static final long SALT_EXTRA = 0x165667B19E3779F9L;
    private static final long SALT_BORDER = 0x27D4EB2F165667C5L;
    private static final long SALT_NAME = 0x85EBCA77C2B2AE63L;

    private WildernessTerrain() {
    }

    // ==================== id 编码 ====================

    /** 分片坐标 → 分片键（非负 long，可直接当主键）。 */
    public static long chunkKey(int cx, int cy) {
        return (long) (cx + COORD_BIAS) * COORD_SPAN + (cy + COORD_BIAS);
    }

    public static int chunkXOfKey(long chunkKey) {
        return (int) (chunkKey / COORD_SPAN) - COORD_BIAS;
    }

    public static int chunkYOfKey(long chunkKey) {
        return (int) (chunkKey % COORD_SPAN) - COORD_BIAS;
    }

    /** 世界位置 → 节点 id。这是全系统唯一允许生成野外 id 的地方。 */
    public static long nodeId(int cx, int cy, int slot) {
        return ID_BASE + chunkKey(cx, cy) * SLOT_STRIDE + slot;
    }

    /** 该 id 是否属于野外（已知世界与管理台新建地点都小于 {@link #ID_BASE}）。 */
    public static boolean isWildernessId(long id) {
        return id >= ID_BASE;
    }

    public static long chunkKeyOfNode(long id) {
        return (id - ID_BASE) / SLOT_STRIDE;
    }

    public static int chunkXOfNode(long id) {
        return chunkXOfKey(chunkKeyOfNode(id));
    }

    public static int chunkYOfNode(long id) {
        return chunkYOfKey(chunkKeyOfNode(id));
    }

    public static int slotOfNode(long id) {
        return (int) ((id - ID_BASE) % SLOT_STRIDE);
    }

    /** 该节点在分片内的槽位是否合法（超过 chunkSize² 的槽位永远不会被生成）。 */
    public static boolean isValidSlot(int slot, int chunkSize) {
        return slot >= 0 && slot < chunkSize * chunkSize;
    }

    // ==================== 入口分片 ====================

    /** 第 index 个入口地点所对应的野外入口分片坐标。 */
    public static int[] gatewayChunkFor(int index) {
        int[] direction = GATEWAY_RING[Math.floorMod(index, GATEWAY_RING.length)];
        return new int[]{direction[0], direction[1]};
    }

    /** 第 index 个入口地点所对应的野外入口节点 id（分片的 0 号槽位）。 */
    public static long gatewayNodeId(int index) {
        int[] chunk = gatewayChunkFor(index);
        return nodeId(chunk[0], chunk[1], 0);
    }

    public static boolean isGatewayChunk(int cx, int cy, int entryCount) {
        for (int i = 0; i < entryCount; i++) {
            int[] chunk = gatewayChunkFor(i);
            if (chunk[0] == cx && chunk[1] == cy) {
                return true;
            }
        }
        return false;
    }

    // ==================== 边界 ====================

    /** 切比雪夫距离（以分片为单位），用于危险度递增与硬上限判定。 */
    public static int distanceFromOrigin(int cx, int cy) {
        return Math.max(Math.abs(cx), Math.abs(cy));
    }

    /** 是否在世界硬上限之内；超出即"混沌未开"，不再生成。 */
    public static boolean inBounds(int cx, int cy, WildernessMapConfig config) {
        if (Math.abs(cx) >= COORD_BIAS || Math.abs(cy) >= COORD_BIAS) {
            return false;
        }
        return distanceFromOrigin(cx, cy) <= config.getMaxChunksFromOrigin();
    }

    // ==================== 生成 ====================

    /** 一个节点（= 地图上的一个"地点"）的生成结果。 */
    public record Node(int slot, int localX, int localY, String name, String description,
                       String biomeKey, String biomeName,
                       int danger, int minRealm,
                       double cultivationBonus, double explorationBonus) {
    }

    /** 一条边；{@code fromId}/{@code toId} 由调用方按需补全为双向或单向。 */
    public record Edge(long fromId, long toId, int travelSeconds) {
    }

    /** 一片分片的完整生成结果。 */
    public record GeneratedChunk(int chunkX, int chunkY, long chunkKey,
                                 String biomeKey, String biomeName,
                                 int biomeDanger, int danger, int distance,
                                 List<Node> nodes, List<Edge> edges) {
    }

    /**
     * 生成一片分片。
     *
     * <p>返回的 {@code edges} 只包含<b>片内</b>边；跨片边见 {@link #borderEdges}。
     * 拆开是因为跨片边需要两端都物化才能落库（见 {@code MapService.materializeArea}）。</p>
     */
    public static GeneratedChunk generate(long seed, int cx, int cy, WildernessMapConfig config) {
        int size = config.getChunkSize();
        double elevation = valueNoise(seed, cx, cy, config.getElevationScale());
        double moisture = valueNoise(seed ^ SALT_MOISTURE, cx, cy, config.getMoistureScale());
        WildernessMapConfig.BiomeSeed biome = pickBiome(config, elevation, moisture);

        int distance = distanceFromOrigin(cx, cy);
        int danger = dangerFor(biome, distance, config);

        List<Node> nodes = new ArrayList<>(size * size);
        for (int slot = 0; slot < size * size; slot++) {
            nodes.add(buildNode(seed, cx, cy, slot, size, biome, danger, config));
        }
        List<Edge> edges = intraChunkEdges(seed, cx, cy, size, config);
        return new GeneratedChunk(cx, cy, chunkKey(cx, cy),
                biome.getKey(), biome.getName(), biome.getDanger(), danger, distance, nodes, edges);
    }

    /** 最终危险度 = max(地形基础危险度, 距离增量)，并夹在 1~4。 */
    public static int dangerFor(WildernessMapConfig.BiomeSeed biome, int distance, WildernessMapConfig config) {
        int distanceDanger = distance / Math.max(1, config.getDangerPerChunks());
        return Math.max(MIN_DANGER, Math.min(MAX_DANGER, Math.max(biome.getDanger(), distanceDanger)));
    }

    /** 按声明顺序取第一个命中的地形；都不命中则退回最后一条（约定为兜底地形）。 */
    public static WildernessMapConfig.BiomeSeed pickBiome(WildernessMapConfig config,
                                                          double elevation, double moisture) {
        List<WildernessMapConfig.BiomeSeed> biomes = config.getBiomes();
        for (WildernessMapConfig.BiomeSeed biome : biomes) {
            if (biome.covers(elevation, moisture)) {
                return biome;
            }
        }
        return biomes.get(biomes.size() - 1);
    }

    private static Node buildNode(long seed, int cx, int cy, int slot, int size,
                                  WildernessMapConfig.BiomeSeed biome, int danger,
                                  WildernessMapConfig config) {
        long h = splitmix64(seed ^ SALT_NAME ^ (chunkKey(cx, cy) * 31L + slot));
        List<String> prefixes = biome.getNamePrefixes();
        List<String> suffixes = biome.getNameSuffixes();
        String prefix = prefixes.get((int) Math.floorMod(h, prefixes.size()));
        String suffix = suffixes.get((int) Math.floorMod(h >>> 8, suffixes.size()));
        // 尾缀两字由坐标派生，保证不同分片的同名地形不会撞成同一个地名
        // —— 地名是 /地图 go <地名> 唯一的解析键，重名会让玩家走到错误的地方
        char tag1 = TAG_ALPHABET.charAt((int) Math.floorMod(h >>> 16, TAG_ALPHABET.length()));
        char tag2 = TAG_ALPHABET.charAt((int) Math.floorMod(h >>> 24, TAG_ALPHABET.length()));
        String name = prefix + suffix + "·" + tag1 + tag2;

        List<String> templates = biome.getDescriptions();
        String template = templates.get((int) Math.floorMod(h >>> 32, templates.size()));
        String description = template.replace("{biome}", biome.getName());

        return new Node(slot, slot % size, slot / size, name, description,
                biome.getKey(), biome.getName(), danger,
                danger * config.getMinRealmPerDanger(),
                config.getCultivationBonusBase() + config.getCultivationBonusPerDanger() * danger,
                config.getExplorationBonusBase() + config.getExplorationBonusPerDanger() * danger);
    }

    /**
     * 片内边：先按种子洗牌后跑 Kruskal 求生成树（保证片内任意两点连通、绝无孤岛），
     * 再按概率把剩余的网格边加回去形成回路 —— 纯生成树走起来全是死胡同，很难受。
     */
    private static List<Edge> intraChunkEdges(long seed, int cx, int cy, int size, WildernessMapConfig config) {
        List<int[]> gridEdges = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int slot = y * size + x;
                if (x + 1 < size) {
                    gridEdges.add(new int[]{slot, slot + 1});
                }
                if (y + 1 < size) {
                    gridEdges.add(new int[]{slot, slot + size});
                }
            }
        }
        Random random = new Random(seed ^ SALT_INTRA ^ chunkKey(cx, cy));
        shuffle(gridEdges, random);

        int[] parent = new int[size * size];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }

        int travelSeconds = config.getTravelTimeSeconds();
        double extraChance = config.getExtraEdgeChancePercent() / 100.0;
        List<Edge> edges = new ArrayList<>();
        List<int[]> rest = new ArrayList<>();
        for (int[] edge : gridEdges) {
            if (union(parent, edge[0], edge[1])) {
                edges.add(new Edge(nodeId(cx, cy, edge[0]), nodeId(cx, cy, edge[1]), travelSeconds));
            } else {
                rest.add(edge);
            }
        }
        for (int[] edge : rest) {
            if (random.nextDouble() < extraChance) {
                edges.add(new Edge(nodeId(cx, cy, edge[0]), nodeId(cx, cy, edge[1]), travelSeconds));
            }
        }
        return edges;
    }

    /**
     * 跨片边：只生成"东边界"与"南边界"两个方向，因此每条边恰好被生成一次，
     * 不会因为两侧各生成一次而出现重复边。
     *
     * <p>两侧的坐标对经过规范化后哈希相同，所以无论从哪一侧看这条边是否存在，
     * 结论都一致 —— 不需要两侧协调，这是无限世界能"局部生成"的前提。</p>
     *
     * <p>每侧至少保留一条通道（概率全落空时强制补第 0 行）：否则会出现两片之间
     * 完全没有通路的"孤岛分片"，玩家走到边界就再也过不去了。</p>
     */
    public static List<Edge> borderEdges(long seed, int cx, int cy, WildernessMapConfig config) {
        int size = config.getChunkSize();
        int travelSeconds = config.getTravelTimeSeconds();
        double chance = config.getBorderEdgeChancePercent() / 100.0;
        List<Edge> edges = new ArrayList<>();

        // 东边界：(cx,cy) 的最右列 ↔ (cx+1,cy) 的最左列
        int matched = 0;
        for (int y = 0; y < size; y++) {
            if (hashUnit(seed ^ SALT_BORDER, cx * 4 + 1, cy * 64 + y) < chance) {
                edges.add(new Edge(nodeId(cx, cy, y * size + size - 1),
                        nodeId(cx + 1, cy, y * size), travelSeconds));
                matched++;
            }
        }
        if (matched == 0) {
            int y = size - 1;
            edges.add(new Edge(nodeId(cx, cy, y * size + size - 1),
                    nodeId(cx + 1, cy, y * size), travelSeconds));
        }

        // 南边界：(cx,cy) 的最下行 ↔ (cx,cy+1) 的最上行
        matched = 0;
        for (int x = 0; x < size; x++) {
            if (hashUnit(seed ^ SALT_BORDER, cx * 64 + x, cy * 4 + 2) < chance) {
                edges.add(new Edge(nodeId(cx, cy, (size - 1) * size + x),
                        nodeId(cx, cy + 1, x), travelSeconds));
                matched++;
            }
        }
        if (matched == 0) {
            int x = size - 1;
            edges.add(new Edge(nodeId(cx, cy, (size - 1) * size + x),
                    nodeId(cx, cy + 1, x), travelSeconds));
        }
        return edges;
    }

    // ==================== 查询（按 id 现算，不查库） ====================

    private static final int CHUNK_CACHE_LIMIT = 256;
    /** 生成结果缓存：野外节点的每次查询都要用到它，而生成一次要跑噪声 + 洗牌 + 生成树。 */
    private static final java.util.Map<Long, GeneratedChunk> CHUNK_CACHE =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, GeneratedChunk> eldest) {
                    return size() > CHUNK_CACHE_LIMIT;
                }
            };
    /** 缓存签名（世界种子 + 分片边长）：任一变化都会让缓存整体失效。 */
    private static long cacheSignature = Long.MIN_VALUE;

    /**
     * 取一片分片（带缓存）。
     *
     * <p>缓存签名里同时包含世界种子与分片边长：管理员改任一项之后，
     * 同一坐标生成出的地形已经不同，缓存必须整体作废，否则会读出上一份世界的节点。</p>
     */
    public static GeneratedChunk chunkAt(long seed, int cx, int cy, WildernessMapConfig config) {
        long signature = seed * 31L + config.getChunkSize();
        synchronized (CHUNK_CACHE) {
            if (cacheSignature != signature) {
                CHUNK_CACHE.clear();
                cacheSignature = signature;
            }
            long key = chunkKey(cx, cy);
            GeneratedChunk cached = CHUNK_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            GeneratedChunk generated = generate(seed, cx, cy, config);
            CHUNK_CACHE.put(key, generated);
            return generated;
        }
    }

    /** 测试用：清空生成结果缓存。 */
    public static void clearCache() {
        synchronized (CHUNK_CACHE) {
            CHUNK_CACHE.clear();
            cacheSignature = Long.MIN_VALUE;
        }
    }

    /** 取某个野外节点；坐标越界或槽位超出本片范围时返回 null。 */
    public static Node nodeAt(long seed, long id, WildernessMapConfig config) {
        int cx = chunkXOfNode(id);
        int cy = chunkYOfNode(id);
        int slot = slotOfNode(id);
        if (!isValidSlot(slot, config.getChunkSize()) || !inBounds(cx, cy, config)) {
            return null;
        }
        return chunkAt(seed, cx, cy, config).nodes().get(slot);
    }

    /**
     * 求某野外节点的全部邻居 id。
     *
     * <p>需要拼三处边：本片的片内边与"东/南跨片边"，西邻片的东跨片边，
     * 北邻片的南跨片边 —— 因为跨片边只由西/北一侧生成（保证每条边只被生成一次），
     * 所以从任意一侧看都能得到同一个邻接集合。</p>
     *
     * <p>越界邻居（超出硬上限）会被过滤掉，表现为"再往外就是混沌未开"。</p>
     */
    public static List<Long> neighborIds(long seed, long id, WildernessMapConfig config) {
        int cx = chunkXOfNode(id);
        int cy = chunkYOfNode(id);
        java.util.Set<Long> neighbors = new java.util.LinkedHashSet<>();
        collectNeighbors(id, chunkAt(seed, cx, cy, config).edges(), neighbors);
        collectNeighbors(id, borderEdges(seed, cx, cy, config), neighbors);
        collectNeighbors(id, borderEdges(seed, cx - 1, cy, config), neighbors);
        collectNeighbors(id, borderEdges(seed, cx, cy - 1, config), neighbors);
        neighbors.removeIf(neighbor -> !inBounds(chunkXOfNode(neighbor), chunkYOfNode(neighbor), config));
        return new ArrayList<>(neighbors);
    }

    private static void collectNeighbors(long id, List<Edge> edges, java.util.Set<Long> target) {
        for (Edge edge : edges) {
            if (edge.fromId() == id) {
                target.add(edge.toId());
            } else if (edge.toId() == id) {
                target.add(edge.fromId());
            }
        }
    }

    /** 生成参数指纹：写进分片文件，用于识别"世界种子被改过"的陈旧存档。 */
    public static long seedHash(long seed, int chunkSize) {
        return splitmix64(seed * 0x9E3779B97F4A7C15L + chunkSize);
    }

    // ==================== 噪声 ====================

    /**
     * 低频值噪声，返回 [-1, 1]。
     *
     * <p>对格点哈希值做平滑插值（smoothstep），因此相邻分片取值连续 ——
     * 直接对坐标哈希会得到"一格一景"的白噪声，那样根本形不成地形带。</p>
     */
    public static double valueNoise(long seed, double x, double y, double scale) {
        double fx = x / scale;
        double fy = y / scale;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double tx = smoothstep(fx - x0);
        double ty = smoothstep(fy - y0);

        double v00 = hashUnit(seed, x0, y0);
        double v10 = hashUnit(seed, x0 + 1, y0);
        double v01 = hashUnit(seed, x0, y0 + 1);
        double v11 = hashUnit(seed, x0 + 1, y0 + 1);

        double top = v00 + (v10 - v00) * tx;
        double bottom = v01 + (v11 - v01) * tx;
        return (top + (bottom - top) * ty) * 2 - 1;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 二维格点 → [0,1) 的确定性哈希。 */
    private static double hashUnit(long seed, int x, int y) {
        long h = splitmix64(seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL));
        return (h >>> 11) * 0x1.0p-53;
    }

    /** splitmix64：分布好、无状态、跨 JVM 完全一致（不像 Random 那样依赖调用顺序）。 */
    static long splitmix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void shuffle(List<int[]> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private static int find(int[] parent, int node) {
        int root = node;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[node] != root) {
            int next = parent[node];
            parent[node] = root;
            node = next;
        }
        return root;
    }

    private static boolean union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA == rootB) {
            return false;
        }
        parent[rootB] = rootA;
        return true;
    }
}
