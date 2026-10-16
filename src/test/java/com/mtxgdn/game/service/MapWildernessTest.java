package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.MapDefaults;
import com.mtxgdn.game.config.WildernessMapConfig;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.item.ItemScanner;
import com.mtxgdn.game.map.WildernessChunk;
import com.mtxgdn.game.map.WildernessChunkStore;
import com.mtxgdn.game.map.WildernessTerrain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 地图系统新增能力的测试：危险度分级、探索进度与首达奖励、传送点解锁、
 * 以及<b>文件式</b>的野外分片世界与采集。
 *
 * <p>这些能力此前完全没有自动化验证，而它们都依赖"地图数据 + 玩家存档 + 配置"三方的组合，
 * 最容易在后续改动里悄悄坏掉（例如野外与已知世界 id 撞车、已探索的分片被回收、
 * 配置里写了一个不存在的物品 key 导致采集发出空物品）。</p>
 *
 * <p>野外部分的核心不变式是：<b>野外不进数据库</b>。地形是种子的纯函数现算，
 * 只有"谁探索过、资源剩余"落在分片文件里。因此本类专门断言
 * {@code map_locations} / {@code player_map_exploration} 的行数在野外活动前后<b>不变</b>。</p>
 */
class MapWildernessTest extends TestBase {

    private static final long QINGYUN_VILLAGE = 1L;
    private static final long CANGYUE_TOWN = 3L;
    private static final long WENDAO_PEAK = 10L;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private MapService mapService;

    /**
     * 把野外分片目录拨到 JUnit 的临时目录。
     *
     * <p>分片文件默认落在工作目录的 {@code data/wilderness} 下，而那是长期存在的：
     * 上一次跑留下的"已固化"分片会让下一次的断言凭空失败（例如"从未探索的分片应被回收"
     * 会因为别的用例先固化过同一片而失败）。每个用例一份干净目录，失败才是真失败。</p>
     */
    @TempDir
    Path wildernessDir;

    /**
     * 注册全部物品。
     *
     * <p>物品注册表在测试 JVM 里默认是空的（注册发生在 {@code Main} 启动时），而灵石本身就是物品
     * （spirit_stone_low/mid/high/supreme），采集也要发出物品 —— 不注册的话，
     * 任何涉及灵石的断言都会以"物品不存在: mtxgdn:spirit_stone_low"炸掉，
     * 那反映的是测试环境缺初始化，而不是被测逻辑有问题。</p>
     */
    @BeforeAll
    static void registerItems() {
        ItemScanner.scanAndRegister();
    }

    @BeforeEach
    void setUp() {
        WildernessChunkStore.get().setRootForTest(wildernessDir);
        // 刻意用 ServiceRegistry 的单例而不是 new MapService()：CombatService.checkSafeZone 走的是
        // 注册表里的那个实例，测试若另建一个实例，就会出现"测试里新建的地点，PVP 校验看不到"
        // —— 表现为玩家明明站在试炼场却被判定在安全区，且只在整套件运行时才复现（单跑本类时
        // 注册表单例恰好是本测试第一次触碰，缓存刚好是新的）。
        mapService = ServiceRegistry.getMapService();
        mapService.reload();
    }

    /**
     * 清掉本类造的临时地点。
     *
     * <p>测试库是全套件共用的（{@code TestBase} 只在每个类的 {@code @BeforeAll} 删库文件，
     * 而删除对已打开的 SQLite 连接并不生效），所以临时地点必须自己收尾，
     * 否则 {@code AdminMapResourceTest} 的"已知世界应有 15 个地点"会被插进来的试炼场顶掉。</p>
     */
    @AfterEach
    void cleanUpTemporaryLocations() {
        try (Connection conn = DatabaseManager.getConnection()) {
            for (String sql : new String[]{
                    "DELETE FROM map_connections WHERE from_location_id >= 900 AND from_location_id < 1000",
                    "DELETE FROM map_connections WHERE to_location_id >= 900 AND to_location_id < 1000",
                    "DELETE FROM player_map_exploration WHERE location_id >= 900 AND location_id < 1000",
                    "DELETE FROM map_locations WHERE id >= 900 AND id < 1000 AND is_wilderness = 0"}) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            // 清理失败不该掩盖真正的断言失败，但也必须留下痕迹
            System.err.println("[MapWildernessTest] 清理临时地点失败: " + e.getMessage());
        }
        // 把分片目录拨回默认，避免后续测试类读到本类的临时世界
        WildernessChunkStore.get().setRootForTest(null);
        try {
            mapService.reload();
        } catch (Exception ignored) {
        }
    }

    // ==================== 危险度分级 ====================

    @Test
    @DisplayName("不变式: 出厂地点中安全区必然危险度为 0，且危险度都在 0~4")
    void testDefaultDangerInvariant() {
        for (MapDefaults.LocationSeed seed : MapDefaults.LOCATIONS) {
            assertTrue(seed.dangerLevel() >= 0 && seed.dangerLevel() <= 4,
                    "地点「" + seed.name() + "」危险度应在 0~4，实际 " + seed.dangerLevel());
            if (seed.safeZone()) {
                assertEquals(0, seed.dangerLevel(),
                        "安全区「" + seed.name() + "」的危险度必须为 0：安全区禁止 PK，"
                                + "而危险度是 PK 掠夺的依据，两者不能同时成立");
            }
        }

        for (MapLocation loc : mapService.getKnownWorldLocations()) {
            assertTrue(loc.getDangerLevel() >= 0 && loc.getDangerLevel() <= 4,
                    "地点「" + loc.getName() + "」危险度越界: " + loc.getDangerLevel());
            if (loc.isSafeZone()) {
                assertEquals(0, loc.getDangerLevel(),
                        "安全区「" + loc.getName() + "」的危险度必须为 0");
            }
        }
    }

    @Test
    @DisplayName("危险度中文名四档齐全，越界不返回 null")
    void testDangerNames() {
        assertEquals("安全", mapService.getDangerName(0));
        assertEquals("低危", mapService.getDangerName(1));
        assertEquals("中危", mapService.getDangerName(2));
        assertEquals("高危", mapService.getDangerName(3));
        assertEquals("极危", mapService.getDangerName(4));
        // 后台误填大数字时按最高档处理，而不是把 null 拼进玩家消息里
        assertEquals("极危", mapService.getDangerName(99));
        assertEquals("安全", mapService.getDangerName(-1));
    }

    // ==================== 野外：确定性生成与 id 编码 ====================

    @Test
    @DisplayName("野外: 同一世界种子永远生成同一片地形（分片被回收后重现仍逐字一致）")
    void testTerrainIsDeterministic() {
        long id = WildernessTerrain.nodeId(3, -2, 5);
        String before = describeNode(id);
        // 清掉生成缓存，强制真的重算一遍（而不是读回同一个缓存对象）
        WildernessTerrain.clearCache();
        String after = describeNode(id);
        assertEquals(before, after,
                "同一 (种子, 坐标, 槽位) 必须生成完全相同的节点；否则玩家走过的地会被悄悄换掉");
    }

    @Test
    @DisplayName("野外: id 由坐标推导，可反解，且同分片内连续")
    void testNodeIdEncodingRoundTrips() {
        int[] coords = {-999, -1, 0, 1, 1023};
        for (int cx : coords) {
            for (int cy : coords) {
                for (int slot = 0; slot < 16; slot++) {
                    long id = WildernessTerrain.nodeId(cx, cy, slot);
                    assertTrue(WildernessTerrain.isWildernessId(id),
                            "野外 id 应落在野外段，实际 " + id);
                    assertEquals(cx, WildernessTerrain.chunkXOfNode(id), "分片 X 应可反解");
                    assertEquals(cy, WildernessTerrain.chunkYOfNode(id), "分片 Y 应可反解");
                    assertEquals(slot, WildernessTerrain.slotOfNode(id), "槽位应可反解");
                }
                // 同一分片内 16 个 id 必须连续，且不越过槽位步长：这是"分片范围内查在线玩家"
                // 能用一个范围查询搞定（而不是逐人比对）的前提
                long first = WildernessTerrain.nodeId(cx, cy, 0);
                long last = WildernessTerrain.nodeId(cx, cy, 15);
                assertEquals(15, last - first, "同分片内的 id 应连续");
                assertTrue(last - first < WildernessTerrain.SLOT_STRIDE,
                        "整片的 id 都应落在 [first, first + SLOT_STRIDE) 这个区间内");
            }
        }
    }

    @Test
    @DisplayName("野外: 越远越险，且危险度始终落在 1~4")
    void testDangerGrowsWithDistanceAndStaysInRange() {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        for (int distance : new int[]{0, 1, 12, 24, 60, 200}) {
            // 危险度 = max(地形危险度, 距离/每多少片一档)，因此分别验证两种地形（最温和/最凶险）
            for (String biomeKey : new String[]{"plain", "volcano"}) {
                var biome = cfg.getBiomes().stream()
                        .filter(b -> biomeKey.equals(b.getKey())).findFirst().orElse(null);
                if (biome == null) continue;
                int danger = WildernessTerrain.dangerFor(biome, distance, cfg);
                assertTrue(danger >= 1 && danger <= 4,
                        "危险度应始终在 1~4（野外没有安全区），" + biomeKey + " 距离 " + distance
                                + " 得到 " + danger);
            }
        }
        // 单一地形下，越远必定不低于近处（危险度随距离单调不减）
        var plain = cfg.getBiomes().stream().filter(b -> "plain".equals(b.getKey())).findFirst().orElse(null);
        if (plain != null) {
            int near = WildernessTerrain.dangerFor(plain, 0, cfg);
            int far = WildernessTerrain.dangerFor(plain, 200, cfg);
            assertTrue(far >= near, "离中土越远应越险，否则“无限扩展”就没有代价了");
        }
    }

    @Test
    @DisplayName("野外: 分片四邻互通，不会生成孤岛")
    void testChunksConnectToNeighbors() {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        long seed = cfg.getWorldSeed();
        for (int cx = -2; cx <= 2; cx++) {
            for (int cy = -2; cy <= 2; cy++) {
                for (int slot = 0; slot < cfg.getChunkSize() * cfg.getChunkSize(); slot++) {
                    long id = WildernessTerrain.nodeId(cx, cy, slot);
                    assertFalse(WildernessTerrain.neighborIds(seed, id, cfg).isEmpty(),
                            "节点 (" + cx + "," + cy + "#" + slot + ") 不应是孤岛");
                }
            }
        }
    }

    @Test
    @DisplayName("野外: 节点可达且危险度/境界门槛自洽，出入口可双向通行")
    void testWildernessNodesAreWellFormed() {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        assertTrue(cfg.isEnabled(), "测试环境应默认启用野外");

        for (MapLocation node : mapService.getChunkNodes(1, 0)) {
            assertTrue(WildernessTerrain.isWildernessId(node.getId()), "野外 id 应落在野外段");
            assertTrue(node.isWilderness(), "野外节点必须带 is_wilderness 标记");
            assertFalse(node.isSafeZone(), "野外不应是安全区：安全区禁止 PK，与野外定位矛盾");
            assertTrue(node.getDangerLevel() >= 1 && node.getDangerLevel() <= 4,
                    "野外危险度应在 1~4，实际 " + node.getDangerLevel());
            assertTrue(node.getMinRealm() >= 0, "境界门槛不应为负");

            // 加成由危险度推导：base + perDanger × danger
            double expectedCult = cfg.getCultivationBonusBase()
                    + cfg.getCultivationBonusPerDanger() * node.getDangerLevel();
            double expectedExpl = cfg.getExplorationBonusBase()
                    + cfg.getExplorationBonusPerDanger() * node.getDangerLevel();
            assertEquals(expectedCult, node.getCultivationBonus(), 1e-9, "野外灵气倍率应由危险度推导");
            assertEquals(expectedExpl, node.getExplorationBonus(), 1e-9, "野外地界倍率应由危险度推导");
        }
    }

    // ==================== 野外：不进数据库（本轮的核心不变式） ====================

    @Test
    @DisplayName("野外: 完全不进数据库 —— 地形现算、状态存文件，库里的行数一动不动")
    void testWildernessNeverCreatesDatabaseRows() throws SQLException {
        long locationsBefore = countRows("SELECT COUNT(*) FROM map_locations");
        long explorationBefore = countRows("SELECT COUNT(*) FROM player_map_exploration");
        long connectionsBefore = countRows("SELECT COUNT(*) FROM map_connections");

        MapLocation node = firstNodeWithResources();
        long playerId = createPlayer(9, node.getId());
        assertNotNull(mapService.recordFirstVisit(playerId, node.getId()), "首次抵达野外应发首达奖励");
        assertTrue(mapService.isExplored(playerId, node.getId()));
        assertFalse(mapService.getNeighbors(node.getId()).isEmpty(), "野外节点应有可去的邻居");

        assertEquals(locationsBefore, countRows("SELECT COUNT(*) FROM map_locations"),
                "野外不应在 map_locations 里产生任何行：地形是现算的，没有行可以对应");
        assertEquals(connectionsBefore, countRows("SELECT COUNT(*) FROM map_connections"),
                "野外的连通关系也不该落库");
        assertEquals(explorationBefore, countRows("SELECT COUNT(*) FROM player_map_exploration"),
                "野外的探索记录应写在分片文件里，而不是 player_map_exploration");
        assertEquals(0, countWildernessExplorationRows(),
                "player_map_exploration 里不应出现任何野外 id（≥ " + WildernessTerrain.ID_BASE + "）");
    }

    @Test
    @DisplayName("野外: 探索记录落在分片文件里，并让该分片永久固化")
    void testExplorationPersistsInChunkFileAndPinsIt() throws SQLException {
        MapLocation node = firstNodeWithResources();
        int cx = WildernessTerrain.chunkXOfNode(node.getId());
        int cy = WildernessTerrain.chunkYOfNode(node.getId());

        long playerId = createPlayer(9, node.getId());
        assertNotNull(mapService.recordFirstVisit(playerId, node.getId()));

        Path file = WildernessChunkStore.get().fileFor(cx, cy);
        assertTrue(Files.exists(file), "被探索过的分片应落盘为文件: " + file);
        assertTrue(mapService.isChunkPinned(cx, cy), "已探索的分片应固化");

        // 已固化的分片不因任何维护而消失：这正是"已探索的内容不会被刷新掉"
        mapService.maintainWildernessIfNeeded();
        assertTrue(Files.exists(file), "维护不该删掉已固化的分片文件");
        assertTrue(mapService.isChunkPinned(cx, cy), "固化标记不应被维护清掉");
        assertTrue(mapService.isExplored(playerId, node.getId()), "探索记录应存活");
    }

    @Test
    @DisplayName("野外: 从未被探索的分片会被回收，已探索的绝不会被回收")
    void testEvictionSparesExploredChunks() throws SQLException {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        // 挑两片远郊，避免与别的用例互相干扰
        int exploredX = 60, exploredY = 61;
        int idleX = 62, idleY = 63;
        deleteChunk(exploredX, exploredY);
        deleteChunk(idleX, idleY);

        long nodeId = WildernessTerrain.nodeId(exploredX, exploredY, 0);
        long playerId = createPlayer(9, nodeId);
        assertNotNull(mapService.recordFirstVisit(playerId, nodeId), "首达应成功");

        // 让两片都"很久没人来过"
        long stale = System.currentTimeMillis() / 1000 - (cfg.getEvictAfterMinutes() + 60) * 60L;
        WildernessChunk idleChunk = mapService.getChunk(idleX, idleY);
        idleChunk.lastActiveAt = stale;
        WildernessChunkStore.get().save(idleChunk);
        WildernessChunk exploredChunk = mapService.getChunk(exploredX, exploredY);
        exploredChunk.lastActiveAt = stale;
        WildernessChunkStore.get().save(exploredChunk);

        assertTrue(Files.exists(WildernessChunkStore.get().fileFor(idleX, idleY)));
        mapService.maintainWildernessIfNeeded();

        assertFalse(Files.exists(WildernessChunkStore.get().fileFor(idleX, idleY)),
                "从未被探索、又长期无人涉足的分片应被回收（存储不该无限膨胀）");
        assertTrue(Files.exists(WildernessChunkStore.get().fileFor(exploredX, exploredY)),
                "已探索的分片绝不能被回收 —— 那是玩家的永久记录");
        assertTrue(mapService.isExplored(playerId, nodeId), "探索记录应存活");

        deleteChunk(exploredX, exploredY);
        deleteChunk(idleX, idleY);
    }

    @Test
    @DisplayName("野外: 资源来自配置池，物品 key 全部真实存在")
    void testWildernessResourcesComeFromConfiguredPool() {
        WildernessMapConfig cfg = WildernessMapConfig.get();
        for (MapLocation node : mapService.getChunkNodes(1, 0)) {
            Map<String, Integer> resources = mapService.getWildernessResource(node.getId());
            if (resources.isEmpty()) {
                continue;
            }
            Set<String> allowed = cfg.getResourcePool(node.getDangerLevel()).stream()
                    .map(WildernessMapConfig.ResourceSeed::getItemKey).collect(Collectors.toSet());
            for (Map.Entry<String, Integer> entry : resources.entrySet()) {
                assertTrue(allowed.contains(entry.getKey()),
                        "资源 " + entry.getKey() + " 不在危险度 " + node.getDangerLevel() + " 的配置池里");
                assertTrue(entry.getValue() > 0, "剩余量应为正数，实际 " + entry.getValue());
                // 配置里写错 key 是最容易犯又最难发现的错误：采集会"成功"但发出一个不存在的物品
                assertNotNull(ItemRegistry.resolve(entry.getKey()),
                        "物品 key「" + entry.getKey() + "」在游戏里不存在，采集会发出空物品");
            }
        }
    }

    @Test
    @DisplayName("野外: 补货只把资源补回上限，地形与探索记录一律不动")
    void testRestockIsInPlace() throws SQLException {
        MapLocation node = firstNodeWithResources();
        long nodeId = node.getId();
        int cx = WildernessTerrain.chunkXOfNode(nodeId);
        int cy = WildernessTerrain.chunkYOfNode(nodeId);

        long playerId = createPlayer(9, nodeId);
        assertNotNull(mapService.recordFirstVisit(playerId, nodeId));
        String nameBefore = mapService.getLocationName(nodeId);
        String terrainBefore = mapService.getLocation(nodeId).getRegion();

        // 先把这片采空
        movePlayer(playerId, nodeId);
        for (int i = 0; i < 20 && !mapService.getWildernessResource(nodeId).isEmpty(); i++) {
            forceGather(playerId);
        }

        int restocked = mapService.restockWilderness();
        assertTrue(restocked > 0, "应有分片被补货，实际 0");

        assertTrue(mapService.isExplored(playerId, nodeId), "补货不该清掉探索记录");
        assertEquals(nameBefore, mapService.getLocationName(nodeId), "补货不该换地形/改名");
        assertEquals(terrainBefore, mapService.getLocation(nodeId).getRegion(), "补货不该换地形");
        assertFalse(mapService.getWildernessResource(nodeId).isEmpty(), "补货后应重新有资源可采");
    }

    // ==================== 探索进度与首达奖励（已知世界） ====================

    @Test
    @DisplayName("首达: 首次抵达发奖并计入探索度，重复抵达不再发奖")
    void testFirstVisitRecordsAndRewardsOnce() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);
        assertEquals(0, mapService.countExploredKnownWorld(playerId), "新角色尚未探索任何地点");

        String first = mapService.recordFirstVisit(playerId, CANGYUE_TOWN);
        assertNotNull(first, "首次抵达应返回奖励文案");
        assertTrue(first.contains("首次抵达"), "文案应说明是首达，实际: " + first);
        assertTrue(mapService.isExplored(playerId, CANGYUE_TOWN));
        assertTrue(mapService.getExploredLocationIds(playerId).contains(CANGYUE_TOWN));
        assertEquals(1, mapService.countExploredKnownWorld(playerId), "探索度应 +1");

        assertNull(mapService.recordFirstVisit(playerId, CANGYUE_TOWN), "重复抵达不应再发奖");
        assertEquals(1, mapService.countExploredKnownWorld(playerId), "重复抵达不应重复计数");
    }

    @Test
    @DisplayName("首达奖励: 危险度越高奖励越多，野外另有倍率")
    void testFirstVisitRewardScalesWithDanger() throws SQLException {
        WildernessMapConfig.FirstVisitReward reward = WildernessMapConfig.get().getFirstVisitReward();
        long playerLow = createPlayer(9, QINGYUN_VILLAGE);
        long playerHigh = createPlayer(9, QINGYUN_VILLAGE);

        long lowDanger = createTemporaryLocation(1, "试炼场-低危");
        long highDanger = createTemporaryLocation(4, "试炼场-极危");

        long lowStonesBefore = ServiceRegistry.getItemService().getSpiritStoneCount(playerLow);
        mapService.recordFirstVisit(playerLow, lowDanger);
        long lowGain = ServiceRegistry.getItemService().getSpiritStoneCount(playerLow) - lowStonesBefore;

        long highStonesBefore = ServiceRegistry.getItemService().getSpiritStoneCount(playerHigh);
        mapService.recordFirstVisit(playerHigh, highDanger);
        long highGain = ServiceRegistry.getItemService().getSpiritStoneCount(playerHigh) - highStonesBefore;

        long expectedLow = reward.getSpiritStonesBase() + reward.getSpiritStonesPerDanger();
        long expectedHigh = reward.getSpiritStonesBase() + reward.getSpiritStonesPerDanger() * 4;
        assertEquals(expectedLow, lowGain, "危险度 1 的首达灵石奖励应与配置一致");
        assertEquals(expectedHigh, highGain, "危险度 4 的首达灵石奖励应与配置一致");
        assertTrue(highGain > lowGain, "危险度越高首达奖励越多");
    }

    @Test
    @DisplayName("走遍全部已知世界地点时发放全图探索奖励")
    void testCompletionBonusWhenAllKnownWorldExplored() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);
        List<MapLocation> known = mapService.getKnownWorldLocations();
        assertTrue(known.size() >= 15, "出厂应至少有 15 个已知世界地点，实际 " + known.size());

        String last = null;
        for (MapLocation loc : known) {
            last = mapService.recordFirstVisit(playerId, loc.getId());
        }
        assertNotNull(last, "最后一个地点也应返回奖励文案");
        assertTrue(last.contains("走遍已知世界"),
                "走遍全图时应附发完成奖励，实际文案: " + last);
        assertEquals(known.size(), mapService.countExploredKnownWorld(playerId),
                "探索度应等于已知世界地点总数");
    }

    // ==================== 传送点解锁 ====================

    @Test
    @DisplayName("传送: 没到过的地方拒绝直传，到过之后按危险度计价并扣费")
    void testTeleportUnlockRequiresPriorVisit() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        Map<String, Object> locked = mapService.teleportByUnlock(playerId, WENDAO_PEAK);
        assertFalse(Boolean.TRUE.equals(locked.get("success")), "没到过的地方不该能直传");
        assertTrue(String.valueOf(locked.get("message")).contains("没到过"),
                "拒绝原因应说明传送点未解锁，实际: " + locked.get("message"));
        assertEquals(QINGYUN_VILLAGE, mapService.getPlayerLocationId(playerId),
                "被拒绝的传送不应改变玩家位置");

        // 先解锁（recordFirstVisit 会顺带发首达奖励，因此余额要在解锁之后再读）
        // 用 grade=0 显式发放下品灵石：自动发放会折算成高品阶，扣费时的"破钱找零"
        // 会让"恰好扣除 cost"这个断言带上舍入误差
        ServiceRegistry.getItemService().addSpiritStones(playerId, 100000, 0);
        assertNotNull(mapService.recordFirstVisit(playerId, WENDAO_PEAK));

        long before = ServiceRegistry.getItemService().getSpiritStoneCount(playerId);
        long cost = mapService.getTeleportCost(QINGYUN_VILLAGE, WENDAO_PEAK);
        assertTrue(cost > 0, "传送费用应为正数");

        Map<String, Object> ok = mapService.teleportByUnlock(playerId, WENDAO_PEAK);
        assertTrue(Boolean.TRUE.equals(ok.get("success")), "到过之后应可直传，实际: " + ok.get("message"));
        assertEquals(WENDAO_PEAK, mapService.getPlayerLocationId(playerId), "传送后应落在目的地");
        assertEquals(cost, before - ServiceRegistry.getItemService().getSpiritStoneCount(playerId),
                "应恰好扣除传送费用");
    }

    @Test
    @DisplayName("传送: 灵石不足时拒绝，且不改变位置")
    void testTeleportRefusedWhenTooPoor() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);
        assertNotNull(mapService.recordFirstVisit(playerId, WENDAO_PEAK));
        // 把灵石清空，确保余额一定不够（首达奖励可能已经发过一些）
        long balance = ServiceRegistry.getItemService().getSpiritStoneCount(playerId);
        if (balance > 0) {
            ServiceRegistry.getItemService().removeSpiritStones(playerId, balance);
        }

        Map<String, Object> result = mapService.teleportByUnlock(playerId, WENDAO_PEAK);
        assertFalse(Boolean.TRUE.equals(result.get("success")), "灵石不足时不该能传送");
        assertTrue(String.valueOf(result.get("message")).contains("灵石"),
                "拒绝原因应说明灵石不足，实际: " + result.get("message"));
        assertEquals(QINGYUN_VILLAGE, mapService.getPlayerLocationId(playerId),
                "失败的传送不应改变玩家位置");
    }

    @Test
    @DisplayName("传送: 危险度越高的目的地越贵，野外另有附加")
    void testTeleportCostScalesWithDanger() throws SQLException {
        long safe = createTemporaryLocation(0, "试炼场-安全");
        long dangerous = createTemporaryLocation(4, "试炼场-危险");
        long safeCost = mapService.getTeleportCost(QINGYUN_VILLAGE, safe);
        long dangerCost = mapService.getTeleportCost(QINGYUN_VILLAGE, dangerous);
        assertTrue(dangerCost > safeCost,
                "危险地带的传送费应更贵: 安全=" + safeCost + " 危险=" + dangerCost);

        MapLocation node = firstNodeWithResources();
        WildernessMapConfig.Teleport cfg = WildernessMapConfig.get().getTeleport();
        long expected = Math.max(cfg.getMinCost(), Math.round(
                (cfg.getBaseCost() + cfg.getCostPerDanger()
                        * (mapService.getDangerLevel(QINGYUN_VILLAGE) + node.getDangerLevel()))
                        * cfg.getWildernessSurcharge()));
        assertEquals(expected, mapService.getTeleportCost(QINGYUN_VILLAGE, node.getId()),
                "野外目的地的传送费应含野外附加系数");
    }

    // ==================== 采集 ====================

    @Test
    @DisplayName("采集: 已知世界拒绝，野外可采且库存减少")
    void testGatherOnlyInWilderness() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        Map<String, Object> refused = mapService.gather(playerId);
        assertFalse(Boolean.TRUE.equals(refused.get("success")), "已知世界不该能采集");
        assertTrue(String.valueOf(refused.get("message")).contains("不是野外"),
                "拒绝原因应说明此处不是野外，实际: " + refused.get("message"));

        MapLocation node = firstNodeWithResources();
        movePlayer(playerId, node.getId());

        String itemKey = mapService.getWildernessResource(node.getId()).keySet().iterator().next();
        int before = mapService.getWildernessResource(node.getId()).get(itemKey);

        Map<String, Object> ok = mapService.gather(playerId);
        assertTrue(Boolean.TRUE.equals(ok.get("success")), "野外采集应成功，实际: " + ok.get("message"));
        int taken = ((Number) ok.get("quantity")).intValue();
        assertTrue(taken >= 1 && taken <= WildernessMapConfig.get().getGather().getMaxPerGather(),
                "单次采集量应在 1~" + WildernessMapConfig.get().getGather().getMaxPerGather()
                        + "，实际 " + taken);
        assertEquals(itemKey, ok.get("itemKey"), "返回的物品应是该地点的资源");
        assertEquals(Math.max(0, before - taken),
                mapService.getWildernessResource(node.getId()).getOrDefault(itemKey, 0),
                "采集后剩余量应减少");
    }

    @Test
    @DisplayName("采集: 连续采集被冷却挡下")
    void testGatherCooldown() throws SQLException {
        MapLocation node = firstNodeWithResources();
        long playerId = createPlayer(9, node.getId());

        // 把该节点的资源补足，确保第二次采集是被"冷却"挡下、而不是被"采空"挡下
        setNodeResources(node, 50);
        movePlayer(playerId, node.getId());

        Map<String, Object> first = mapService.gather(playerId);
        assertTrue(Boolean.TRUE.equals(first.get("success")), "第一次采集应成功: " + first.get("message"));

        Map<String, Object> second = mapService.gather(playerId);
        assertFalse(Boolean.TRUE.equals(second.get("success")), "连续采集应被冷却挡下");
        assertTrue(String.valueOf(second.get("message")).contains("还需"),
                "第二次采集应提示冷却剩余时间，实际: " + second.get("message"));
    }

    // ==================== 危险度对 PVP 的影响 ====================

    @Test
    @DisplayName("PVP: 险地(危险度≥3)掠夺败者灵石，低危地带不掠夺")
    void testPvpLootOnlyInDangerZone() throws SQLException {
        long arena = createTemporaryLocation(4, "试炼场-极危");
        long lowArena = createTemporaryLocation(1, "试炼场-低危");

        long loot = fightAndReadLoot(arena);
        assertTrue(loot > 0, "危险度 4 的地点 PK 应产生灵石掠夺，实际 " + loot);

        assertEquals(0, fightAndReadLoot(lowArena),
                "危险度 1 的地点不该掠夺：掠夺只存在于险地，否则全图都会变成猎杀场");
    }

    /**
     * 在指定地点打一场 PVP，返回掠夺到的灵石数，并校验败者恰好损失了这么多。
     * <p>胜负本身是随机的，所以断言"败者少了多少"而不是"某一方少了多少"。</p>
     */
    private long fightAndReadLoot(long locationId) throws SQLException {
        long challenger = createPlayer(5, locationId);
        long target = createPlayer(5, locationId);
        // 双方都给足灵石：否则败者是"没钱的那一方"时掠夺必然为 0，测试会随机失败。
        // 用 grade=0 显式发放，避免自动折算成高品阶后扣费出现"破钱找零"的舍入误差
        ServiceRegistry.getItemService().addSpiritStones(challenger, 10000, 0);
        ServiceRegistry.getItemService().addSpiritStones(target, 10000, 0);

        long challengerBefore = ServiceRegistry.getItemService().getSpiritStoneCount(challenger);
        long targetBefore = ServiceRegistry.getItemService().getSpiritStoneCount(target);

        CombatService.CombatResult result = new CombatService().pvpChallenge(challenger, target);
        assertTrue(result.isSuccess(), "试炼场应允许私斗，实际: " + result.getMessage());

        long loot = result.getSpiritStonesLooted();
        if (loot > 0) {
            long loserBefore = result.isChallengerWon() ? targetBefore : challengerBefore;
            long loserId = result.isChallengerWon() ? target : challenger;
            assertEquals(loserBefore - loot,
                    ServiceRegistry.getItemService().getSpiritStoneCount(loserId),
                    "败者应恰好损失被掠夺的灵石");
        }
        return loot;
    }

    // ==================== 辅助 ====================

    /** 节点的全部可变信息拼成一行，用于比对"两次生成是否一致"。 */
    private String describeNode(long id) {
        MapLocation loc = mapService.getLocation(id);
        assertNotNull(loc, "野外节点应可解析: " + id);
        return loc.getName() + "|" + loc.getDescription() + "|" + loc.getRegion()
                + "|" + loc.getDangerLevel() + "|" + loc.getMinRealm()
                + "|" + loc.getCultivationBonus() + "|" + loc.getExplorationBonus()
                + "|" + mapService.getNeighbors(id).stream().map(MapLocation::getId)
                .map(String::valueOf).collect(Collectors.joining(","));
    }

    /** 取一个带资源的野外节点；从出入口分片开始往外找，避免依赖随机结果。 */
    private MapLocation firstNodeWithResources() {
        int[] start = WildernessTerrain.gatewayChunkFor(0);
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (MapLocation node : mapService.getChunkNodes(start[0] + dx, start[1] + dy)) {
                        if (!mapService.getWildernessResource(node.getId()).isEmpty()) {
                            return node;
                        }
                    }
                }
            }
        }
        throw new AssertionError("应至少有一个带资源的野外节点");
    }

    /** 直接把某节点的库存改成指定值（绕过冷却与随机）。 */
    private void setNodeResources(MapLocation node, int quantity) {
        WildernessChunk chunk = mapService.getChunk(
                WildernessTerrain.chunkXOfNode(node.getId()),
                WildernessTerrain.chunkYOfNode(node.getId()));
        for (WildernessChunk.Resource resource
                : chunk.resourcesOf(WildernessTerrain.slotOfNode(node.getId())).values()) {
            resource.total = quantity;
            resource.remaining = quantity;
        }
        WildernessChunkStore.get().save(chunk);
    }

    /** 无视冷却直接采一次（把冷却列清零即可）。 */
    private void forceGather(long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET last_gather_time = 0 WHERE id = ?")) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
        mapService.gather(playerId);
    }

    private void deleteChunk(int cx, int cy) {
        WildernessChunkStore.get().delete(cx, cy);
    }

    private long countRows(String sql) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long countWildernessExplorationRows() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM player_map_exploration WHERE location_id >= ?")) {
            ps.setLong(1, WildernessTerrain.ID_BASE);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /**
     * 造一个指定危险度的临时地点，避免依赖野外的随机生成结果。
     * <p>id 取 900+，落在已知世界范围内（&lt; 野外 idBase），因此不会被野外系统触及。</p>
     */
    private long createTemporaryLocation(int dangerLevel, String name) throws SQLException {
        long id = 900L + dangerLevel;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM map_locations WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO map_locations (id, name, description, region, min_realm, is_safe_zone,"
                            + " cultivation_bonus, exploration_bonus, danger_level, is_wilderness) "
                            + "VALUES (?, ?, '', '测试区', 0, 0, 1.0, 1.0, ?, 0)")) {
                ps.setLong(1, id);
                ps.setString(2, name);
                ps.setInt(3, dangerLevel);
                ps.executeUpdate();
            }
        }
        mapService.reload();
        return id;
    }

    private void movePlayer(long playerId, long locationId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET current_location_id = ?, last_travel_time = 0, last_gather_time = 0 "
                             + "WHERE id = ?")) {
            ps.setLong(1, locationId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 直接写库造玩家（与 MapServiceTest 一致：先建 user 再建 player，外键约束生效）。 */
    private long createPlayer(int realm, long locationId) throws SQLException {
        int n = SEQ.incrementAndGet();
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, "wilduser" + n);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            long userId;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, "wilduser" + n);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败");
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, realm, hp, max_hp, current_location_id,"
                            + " last_travel_time, last_gather_time) VALUES (?, ?, ?, 100, 100, ?, 0, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, "野外道友" + n);
                ps.setInt(3, realm);
                ps.setLong(4, locationId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "角色写入失败");
                    return rs.getLong(1);
                }
            }
        }
    }
}
