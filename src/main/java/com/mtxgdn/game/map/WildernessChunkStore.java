package com.mtxgdn.game.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 野外分片的<b>文件存储</b>：一个分片一个 JSON 文件。
 *
 * <h2>为什么用文件而不是数据库</h2>
 * <p>地形是 {@code (worldSeed, chunkX, chunkY)} 的纯函数，本来就不需要存储；
 * 真正要落盘的只有"谁探索过、资源还剩多少"这点可变状态。既然每个分片的持久化数据
 * 都是自包含的一小块，用文件就有三个数据库给不了的好处：</p>
 * <ul>
 *   <li><b>库体积不随世界扩张增长</b>：无限世界里，"已经生成过的区域"可以无限大，
 *       而数据库表是单点的、需要索引和迁移的。文件天然按分片散列到目录树里。</li>
 *   <li><b>可读、可备份、可手工修</b>：管理员打开一个文件就能看到这片野外的全部状态，
 *       不依赖任何工具；备份就是拷目录。</li>
 *   <li><b>零 schema 迁移</b>：以后给分片加字段，老文件缺字段就是默认值，不需要
 *       {@code ALTER TABLE}。</li>
 * </ul>
 *
 * <h2>目录布局</h2>
 * <pre>
 *   data/wilderness/
 *     0/0/0_0.json          ← chunk(0,0)
 *     0/1/0_1.json          ← chunk(0,1)
 *     -1/-1/-1_-1.json      ← chunk(-1,-1)（负号在文件名里没问题，Windows 也接受）
 * </pre>
 * <p>中间两级是"每 64 片一个目录"的分桶：直接把几十万个文件塞进同一个目录，
 * 连 {@code ls} 都会卡住。分桶让任一目录下的文件数保持在可控范围。</p>
 *
 * <h2>一致性</h2>
 * <p>写入走 {@code .tmp} + {@code ATOMIC_MOVE}（与 {@code PlayerDataService} 同一套约定），
 * 因此进程在写一半时被杀不会留下半个 JSON 文件。内存里维护一份 LRU 缓存，
 * 避免每次读节点都碰磁盘。</p>
 */
public final class WildernessChunkStore {

    private static final GameLogger LOG = GameLogger.getLogger(WildernessChunkStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 分桶粒度：每 64×64 片一个目录。 */
    private static final int BUCKET_SIZE = 64;
    /** 内存缓存上限（分片数）。超出后按最久未使用淘汰，磁盘上仍有文件，不影响正确性。 */
    private static final int CACHE_LIMIT = 512;

    private static final WildernessChunkStore INSTANCE = new WildernessChunkStore();

    /**
     * 数据根目录。刻意不是 final：测试要把它拨到自己的临时目录。
     *
     * <p>分片文件跟玩家数据一样落在工作目录下的 data/ 里，测试若共用它会互相污染：
     * 上一次跑留下的"已固化"分片，会让下一次的"回收未探索分片"断言凭空失败。
     * 生产环境从不调用 {@link #setRootForTest}，因此这里没有并发风险。</p>
     */
    private volatile Path root;

    /** LRU 缓存：accessOrder=true 让 get 也会把条目移到队尾。 */
    private final Map<Long, WildernessChunk> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, WildernessChunk> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private long loadCount;
    private long saveCount;

    private WildernessChunkStore() {
        this.root = Paths.get(AppConfig.get("wilderness.dir", "data/wilderness"));
    }

    public static WildernessChunkStore get() {
        return INSTANCE;
    }

    /** 切换数据目录（仅测试用）；传 null 表示恢复默认目录。 */
    public synchronized void setRootForTest(Path dir) {
        cache.clear();
        this.root = dir != null ? dir : Paths.get(AppConfig.get("wilderness.dir", "data/wilderness"));
    }

    public Path root() {
        return root;
    }

    /** 该分片的文件路径。 */
    public Path fileFor(int cx, int cy) {
        return root
                .resolve(String.valueOf(Math.floorDiv(cx, BUCKET_SIZE)))
                .resolve(String.valueOf(Math.floorDiv(cy, BUCKET_SIZE)))
                .resolve(cx + "_" + cy + ".json");
    }

    /**
     * 读取分片状态：内存缓存 → 磁盘文件 → 新建。
     *
     * <p>新建（而不是报错）是常态：野外是无限的，绝大多数分片从来没被访问过，
     * 第一次走到这里才凭空产生一个状态对象。</p>
     *
     * <p>文件里的 {@code seedHash} 与当前生成参数不一致时<b>丢弃文件</b>并重建：
     * 管理员改过 {@code worldSeed} 之后，同一个分片的坐标已经指向另一片地形，
     * 旧文件里的探索记录与资源会挂到完全不同的节点上，那比丢失更糟。</p>
     */
    public synchronized WildernessChunk load(int cx, int cy, long seedHash, int chunkSize,
                                             boolean gateway, long now, long restockIntervalSeconds) {
        long key = WildernessTerrain.chunkKey(cx, cy);
        WildernessChunk cached = cache.get(key);
        if (cached != null && cached.seedHash == seedHash) {
            return cached;
        }

        WildernessChunk chunk = readFile(cx, cy);
        if (chunk != null && chunk.seedHash != seedHash) {
            LOG.warn("分片 (" + cx + "," + cy + ") 的存档与当前世界种子不符（存档 "
                    + chunk.seedHash + " / 当前 " + seedHash + "），已丢弃并按新地形重建；"
                    + "该分片的探索记录与资源库存随之失效");
            chunk = null;
        }
        if (chunk == null) {
            chunk = fresh(cx, cy, seedHash, chunkSize, gateway, now, restockIntervalSeconds);
            loadCount++;
        }
        cache.put(key, chunk);
        return chunk;
    }

    /** 新建一个空状态：无探索记录、资源待生成（由 {@code MapService} 按资源池抽）、到点补货。 */
    public static WildernessChunk fresh(int cx, int cy, long seedHash, int chunkSize,
                                        boolean gateway, long now, long restockIntervalSeconds) {
        WildernessChunk chunk = new WildernessChunk();
        chunk.chunkX = cx;
        chunk.chunkY = cy;
        chunk.seedHash = seedHash;
        chunk.chunkSize = chunkSize;
        chunk.gateway = gateway;
        chunk.pinned = gateway;
        chunk.generatedAt = now;
        chunk.lastActiveAt = now;
        chunk.restockAt = now + restockIntervalSeconds;
        return chunk;
    }

    private WildernessChunk readFile(int cx, int cy) {
        Path path = fileFor(cx, cy);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            WildernessChunk chunk = GSON.fromJson(json, WildernessChunk.class);
            if (chunk == null) {
                return null;
            }
            // 文件内容里也带坐标，读回来以文件名为准：手工改过文件名时不至于错位
            chunk.chunkX = cx;
            chunk.chunkY = cy;
            return chunk;
        } catch (Exception e) {
            LOG.error("读取野外分片文件失败（将按新分片处理）: " + path.toAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 原子写入分片文件。
     *
     * <p>刻意<b>同步写盘</b>而不是攒批：分片文件只有 1~3 KB，而触发写入的都是
     * "抵达新地点""采集"这类低频动作，代价可以忽略；换来的是"玩家做了什么，
     * 磁盘上立刻就是什么"，进程被 kill 也不会丢掉刚刚的探索。</p>
     */
    public synchronized boolean save(WildernessChunk chunk) {
        if (chunk == null) {
            return false;
        }
        cache.put(chunk.chunkKey(), chunk);
        Path target = fileFor(chunk.chunkX, chunk.chunkY);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(tmp, GSON.toJson(chunk).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            saveCount++;
            return true;
        } catch (IOException e) {
            LOG.error("写入野外分片文件失败: " + target.toAbsolutePath(), e);
            deleteQuietly(tmp);
            return false;
        }
    }

    /**
     * 删除一个分片文件（仅用于回收<b>从未被探索过</b>的分片）。
     *
     * <p>调用方必须先确认 {@code !pinned}：已探索的分片是玩家的永久记录，
     * 本方法不做二次校验，因为"谁能删"这个判断属于业务层。</p>
     */
    public synchronized boolean delete(int cx, int cy) {
        cache.remove(WildernessTerrain.chunkKey(cx, cy));
        Path path = fileFor(cx, cy);
        try {
            boolean removed = Files.deleteIfExists(path);
            if (removed) {
                deleteEmptyBuckets(path);
            }
            return removed;
        } catch (IOException e) {
            LOG.warn("删除野外分片文件失败: " + path.toAbsolutePath() + "（" + e.getMessage() + "）");
            return false;
        }
    }

    /** 分片删完后顺手清掉空的分桶目录，避免留下成千上万个空目录。 */
    private void deleteEmptyBuckets(Path file) {
        Path bucket = file.getParent();
        for (int level = 0; level < 2 && bucket != null && !bucket.equals(root); level++) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(bucket)) {
                if (stream.iterator().hasNext()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(bucket);
            } catch (IOException e) {
                return;
            }
            bucket = bucket.getParent();
        }
    }

    /**
     * 列出磁盘上全部分片的坐标。
     *
     * <p>用于回收扫描与管理台统计。会遍历整个目录树，因此调用方应当限频
     * （{@code MapService} 每 30 分钟才扫一次）。</p>
     */
    public List<int[]> listChunks(int limit) {
        List<int[]> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root, 3)) {
            Iterator<Path> iterator = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .iterator();
            while (iterator.hasNext() && result.size() < limit) {
                int[] coords = parseCoordinates(iterator.next().getFileName().toString());
                if (coords != null) {
                    result.add(coords);
                }
            }
        } catch (IOException e) {
            LOG.warn("遍历野外分片目录失败: " + e.getMessage());
        }
        return result;
    }

    /** 从 {@code "3_-7.json"} 解析出坐标；解析不出来则返回 null（手工塞进来的杂物）。 */
    static int[] parseCoordinates(String fileName) {
        String name = fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length())
                : fileName;
        int underscore = name.indexOf('_');
        if (underscore <= 0 || underscore == name.length() - 1) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(name.substring(0, underscore)),
                    Integer.parseInt(name.substring(underscore + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public synchronized int cachedCount() {
        return cache.size();
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("dir", root.toAbsolutePath().toString());
        stats.put("cachedChunks", cache.size());
        stats.put("fileReads", loadCount);
        stats.put("fileWrites", saveCount);
        return stats;
    }

    /** 测试用：丢弃内存缓存，强制下次从磁盘读。 */
    public synchronized void clearCache() {
        cache.clear();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
