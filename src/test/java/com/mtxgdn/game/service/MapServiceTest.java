package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.config.MapDefaults;
import com.mtxgdn.game.item.TeleportEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 地图系统测试。
 *
 * <p>覆盖此前完全没有自动化验证的四段核心逻辑：境界门槛、邻接校验、赶路冷却、
 * 数据驱动的加成与事件偏置，以及并发移动的原子性。</p>
 */
class MapServiceTest extends TestBase {

    /** 地点 id 常量，与 MapDefaults 的出厂数据一致。 */
    private static final long QINGYUN_VILLAGE = 1L;
    private static final long QINGYUN_MOUNTAIN = 2L;
    private static final long CANGYUE_TOWN = 3L;
    private static final long TAIXU_CITY = 4L;
    private static final long WENDAO_PEAK = 10L;
    private static final long WANYAO_VALLEY = 12L;
    private static final long LINGYAO_VALLEY = 13L;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private MapService mapService;

    @BeforeEach
    void setUp() throws SQLException {
        resetSeededMapData();
        mapService = new MapService();
        mapService.ensureInitialized();
    }

    /** 把出厂地点恢复成 MapDefaults 的值，使用例不依赖残留库状态（测试库跨轮次复用）。 */
    private void resetSeededMapData() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_locations SET name = ?, cultivation_bonus = ?, exploration_bonus = ? WHERE id = ?")) {
            for (MapDefaults.LocationSeed seed : MapDefaults.LOCATIONS) {
                ps.setString(1, seed.name());
                ps.setDouble(2, seed.cultivationBonus());
                ps.setDouble(3, seed.explorationBonus());
                ps.setLong(4, seed.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ==================== 数据驱动的加成 ====================

    @Test
    @DisplayName("出厂地点与灵气/地界加成来自数据库，而非硬编码常量表")
    void testBonusesComeFromDatabase() {
        assertEquals(15, mapService.getAllLocations().size());

        assertEquals(1.50, mapService.getCultivationBonus(WENDAO_PEAK), 1e-9, "问道峰灵气应为 1.50");
        assertEquals(1.00, mapService.getCultivationBonus(QINGYUN_VILLAGE), 1e-9, "青云村灵气应为 1.00");
        assertEquals(1.35, mapService.getExplorationBonus(WANYAO_VALLEY), 1e-9, "万妖谷地界加成应为 1.35");

        // 未知地点回落 1.0，而不是抛异常
        assertEquals(1.0, mapService.getCultivationBonus(99999L), 1e-9);
        assertEquals(1.0, mapService.getExplorationBonus(99999L), 1e-9);
    }

    @Test
    @DisplayName("境界门槛按境界名渲染，不再拼出「炼气+7」这种错文案")
    void testRealmNames() {
        assertEquals("凡人", mapService.getRealmName(0));
        assertEquals("炼气期", mapService.getRealmName(1));
        assertEquals("大乘期", mapService.getRealmName(7), "minRealm=7 是大乘期而非「炼气+7」");
    }

    @Test
    @DisplayName("老库刚升级时未播种（NULL）的加成列会被出厂值回填")
    void testUnseededBonusColumnsAreBackfilled() throws SQLException {
        setBonusColumns(WENDAO_PEAK, null, null);
        mapService.reload();

        assertEquals(1.50, mapService.getCultivationBonus(WENDAO_PEAK), 1e-9);
        assertEquals(1.35, mapService.getExplorationBonus(WENDAO_PEAK), 1e-9);
    }

    @Test
    @DisplayName("管理员调过的加成不会被回填覆盖")
    void testAdminEditedBonusIsPreserved() throws SQLException {
        setBonusColumns(WENDAO_PEAK, 2.5, 2.5);
        mapService.reload();

        assertEquals(2.5, mapService.getCultivationBonus(WENDAO_PEAK), 1e-9);
        assertEquals(2.5, mapService.getExplorationBonus(WENDAO_PEAK), 1e-9);

        setBonusColumns(WENDAO_PEAK, 1.50, 1.35); // 复原，避免影响其它用例
        mapService.reload();
    }

    @Test
    @DisplayName("reload() 让后台改库立即生效，无需重启")
    void testReloadPicksUpEdits() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE map_locations SET name = ? WHERE id = ?")) {
            ps.setString(1, "问道峰·临时");
            ps.setLong(2, WENDAO_PEAK);
            ps.executeUpdate();
        }
        assertEquals("问道峰", mapService.getLocationName(WENDAO_PEAK), "未 reload 前仍是旧缓存");

        mapService.reload();
        assertEquals("问道峰·临时", mapService.getLocationName(WENDAO_PEAK));

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE map_locations SET name = ? WHERE id = ?")) {
            ps.setString(1, "问道峰");
            ps.setLong(2, WENDAO_PEAK);
            ps.executeUpdate();
        }
        mapService.reload();
    }

    @Test
    @DisplayName("地界事件偏置来自数据库，让地点描述作用于事件抽取")
    void testEventBiasLoadedFromDatabase() {
        Map<String, Double> valleyBias = mapService.getEventBias(WANYAO_VALLEY);
        assertEquals(3.0, valleyBias.get("monster"), 1e-9, "万妖谷应显著偏向妖兽事件");

        Map<String, Double> herbBias = mapService.getEventBias(LINGYAO_VALLEY);
        assertEquals(3.0, herbBias.get("herb"), 1e-9, "灵药谷应显著偏向灵草事件");

        assertTrue(mapService.getEventBias(QINGYUN_VILLAGE).isEmpty(), "出生地不应有偏置");
    }

    // ==================== 移动校验 ====================

    @Test
    @DisplayName("境界不足无法前往高阶地界")
    void testRealmGateBlocksTravel() throws SQLException {
        long playerId = createPlayer(0, QINGYUN_VILLAGE);

        Map<String, Object> result = mapService.travel(playerId, WENDAO_PEAK);

        assertFalse((Boolean) result.get("success"));
        String message = (String) result.get("message");
        assertTrue(message.contains("境界不足"), message);
        assertTrue(message.contains("大乘期"), "应提示真实境界名: " + message);
    }

    @Test
    @DisplayName("只能前往相邻地点")
    void testTravelRequiresAdjacency() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        Map<String, Object> result = mapService.travel(playerId, WENDAO_PEAK);

        assertFalse((Boolean) result.get("success"));
        assertTrue(((String) result.get("message")).contains("无法直接前往"), (String) result.get("message"));
    }

    @Test
    @DisplayName("相邻地点可以正常移动并落库")
    void testTravelSucceedsAndPersists() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        Map<String, Object> result = mapService.travel(playerId, QINGYUN_MOUNTAIN);

        assertTrue((Boolean) result.get("success"), (String) result.get("message"));
        assertEquals("青云村", result.get("from"));
        assertEquals("青云山", result.get("to"));
        assertEquals(QINGYUN_MOUNTAIN, mapService.getPlayerLocationId(playerId));
    }

    @Test
    @DisplayName("赶路冷却期间无法连续移动")
    void testTravelCooldown() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        assertTrue((Boolean) mapService.travel(playerId, QINGYUN_MOUNTAIN).get("success"));

        Map<String, Object> second = mapService.travel(playerId, QINGYUN_VILLAGE);
        assertFalse((Boolean) second.get("success"));
        assertTrue(((String) second.get("message")).contains("才能再次动身"), (String) second.get("message"));
        assertEquals(QINGYUN_MOUNTAIN, mapService.getPlayerLocationId(playerId), "冷却中被拒绝的移动不得落库");
    }

    @Test
    @DisplayName("展示的路程秒数与实际冷却一致（说几秒就等几秒）")
    void testReportedTravelSecondsMatchEnforcedCooldown() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        Map<String, Object> result = mapService.travel(playerId, QINGYUN_MOUNTAIN);
        assertTrue((Boolean) result.get("success"), (String) result.get("message"));

        // 返回给客户端的路程秒数，必须正好是随后真正生效的冷却时长
        long reported = ((Number) result.get("travelSeconds")).longValue();
        Map<String, Object> immediate = mapService.travel(playerId, QINGYUN_VILLAGE);
        assertFalse((Boolean) immediate.get("success"));
        String message = (String) immediate.get("message");
        assertTrue(message.contains(String.valueOf(reported)),
                "提示里的剩余秒数应基于同一口径的路程值 " + reported + "，实际: " + message);
    }

    @Test
    @DisplayName("并发移动只有一次生效")
    void testConcurrentTravelOnlyOneWins() throws Exception {
        // 苍月镇相邻 青云山 / 太虚城 / 灵药谷，两个线程各去一处
        long playerId = createPlayer(9, CANGYUE_TOWN);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> toCity = pool.submit(() -> {
                start.await();
                return (Boolean) mapService.travel(playerId, TAIXU_CITY).get("success");
            });
            Future<Boolean> toValley = pool.submit(() -> {
                start.await();
                return (Boolean) mapService.travel(playerId, LINGYAO_VALLEY).get("success");
            });
            start.countDown();

            int succeeded = (Boolean.TRUE.equals(toCity.get(20, TimeUnit.SECONDS)) ? 1 : 0)
                    + (Boolean.TRUE.equals(toValley.get(20, TimeUnit.SECONDS)) ? 1 : 0);

            assertEquals(1, succeeded, "并发移动必须恰好一次成功");
            long finalLocation = mapService.getPlayerLocationId(playerId);
            assertTrue(finalLocation == TAIXU_CITY || finalLocation == LINGYAO_VALLEY,
                    "最终位置必须是其中一个目标，实际=" + finalLocation);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("失效位置回落到出生地，而不是「当前位置异常」卡死")
    void testStaleLocationFallsBackToSpawn() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);
        setPlayerLocation(playerId, 99999L);

        assertEquals(QINGYUN_VILLAGE, mapService.getPlayerLocationId(playerId));

        Map<String, Object> result = mapService.travel(playerId, QINGYUN_MOUNTAIN);
        assertTrue((Boolean) result.get("success"), "失效位置应可正常移动: " + result.get("message"));
    }

    // ==================== 传送符 ====================

    @Test
    @DisplayName("传送符按声明的目的地传送，支持别名并回落主城")
    void testTeleportHonoursDestination() throws SQLException {
        long playerId = createPlayer(9, QINGYUN_VILLAGE);

        assertTrue(TeleportEffect.to("万妖谷").execute(playerId, null, null).startsWith("传送成功"));
        assertEquals(WANYAO_VALLEY, mapService.getPlayerLocationId(playerId), "目的地参数必须真正生效");

        assertTrue(TeleportEffect.to("主城").execute(playerId, null, null).startsWith("传送成功"));
        assertEquals(TAIXU_CITY, mapService.getPlayerLocationId(playerId), "「主城」别名应解析为太虚城");

        // 无法解析的目的地回落主城，而不是静默失败
        assertTrue(TeleportEffect.to("不存在的地方").execute(playerId, null, null).startsWith("传送成功"));
        assertEquals(TAIXU_CITY, mapService.getPlayerLocationId(playerId));
    }

    @Test
    @DisplayName("传送符仍受境界门槛约束")
    void testTeleportStillGatedByRealm() throws SQLException {
        long playerId = createPlayer(0, QINGYUN_VILLAGE);

        String outcome = TeleportEffect.to("万妖谷").execute(playerId, null, null);

        assertTrue(outcome.startsWith("传送失败"), outcome);
        assertEquals(QINGYUN_VILLAGE, mapService.getPlayerLocationId(playerId));
    }

    // ==================== 安全区 ====================

    @Test
    @DisplayName("安全区内禁止 PVP 私斗")
    void testSafeZoneBlocksPvp() throws SQLException {
        long challenger = createPlayer(3, TAIXU_CITY);
        long target = createPlayer(3, QINGYUN_MOUNTAIN);

        CombatService.CombatResult result = new CombatService().createChallenge(challenger, target);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("安全区"), result.getMessage());
    }

    @Test
    @DisplayName("非安全区可正常发起挑战")
    void testNonSafeZoneAllowsPvp() throws SQLException {
        long challenger = createPlayer(3, QINGYUN_MOUNTAIN);
        long target = createPlayer(3, LINGYAO_VALLEY);

        CombatService.CombatResult result = new CombatService().createChallenge(challenger, target);

        assertTrue(result.isSuccess(), result.getMessage());
    }

    // ==================== 辅助 ====================

    /** 建一个用户 + 角色，返回 playerId。realm 直接落库以越过修炼流程。 */
    private long createPlayer(int realm, long locationId) throws SQLException {
        int n = SEQ.incrementAndGet();
        try (Connection conn = DatabaseManager.getConnection()) {
            long userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, "mapuser" + n);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, "mapuser" + n);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败");
                    userId = rs.getLong(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, realm, hp, max_hp, current_location_id, last_travel_time) "
                            + "VALUES (?, ?, ?, 100, 100, ?, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
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

    private void setPlayerLocation(long playerId, long locationId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players SET current_location_id = ?, last_travel_time = 0 WHERE id = ?")) {
            ps.setLong(1, locationId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private void setBonusColumns(long locationId, Double cultivation, Double exploration) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE map_locations SET cultivation_bonus = ?, exploration_bonus = ? WHERE id = ?")) {
            if (cultivation == null) ps.setNull(1, java.sql.Types.DOUBLE); else ps.setDouble(1, cultivation);
            if (exploration == null) ps.setNull(2, java.sql.Types.DOUBLE); else ps.setDouble(2, exploration);
            ps.setLong(3, locationId);
            ps.executeUpdate();
        }
    }
}
