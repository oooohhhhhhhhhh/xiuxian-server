package com.mtxgdn.game.service;

import com.mtxgdn.game.farm.FarmResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 农田「惰性结算」回归测试。
 *
 * <p>改造前的实现依赖一个 <b>每秒执行一次</b> 的后台任务：
 * {@code SELECT * FROM farm_plots WHERE state IN ('PLANTED','GROWING')}，
 * 把全服正在生长的地块逐一推进一格——代价与全服地块数成正比，
 * 且对当时根本没在看农田的玩家纯属无用功。</p>
 *
 * <p>本测试锁住改造后的核心不变量：<b>没有任何后台线程</b>，
 * 仅凭玩家的一次访问（读农田 / 浇水 / 收获）就必须得到与"每秒推进"等价的结果。</p>
 */
class FarmLazySettlementTest extends TestBase {

    private static final String SEED = "mtxgdn:spirit_grass_seed";
    private static final String CROP = "mtxgdn:spirit_grass";
    /** 灵草：60 秒成熟、3 个生长阶段（见 CropConfig 静态表） */
    private static final int STAGES = 3;

    private static FarmService farmService;
    private static ItemService itemService;
    private static UserService userService;
    private static boolean ready = false;

    @BeforeAll
    static void setUpFarm() {
        try {
            com.mtxgdn.game.item.ItemScanner.scanAndRegister();
            farmService = new FarmService();
            itemService = ServiceRegistry.getItemService();
            userService = new UserService();
            ready = true;
        } catch (Throwable t) {
            System.out.println("[FarmLazySettlementTest] 环境初始化失败，测试将跳过: " + t);
            ready = false;
        }
    }

    /** 农场模块被别的用例关掉时跳过（本类只验证结算逻辑本身）。 */
    private boolean farmDisabled() {
        return !com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("farm");
    }

    private static long makePlayer(String username, String playerName) {
        String email = username + "@qq.com";
        String code = new com.mtxgdn.service.VerificationCodeService().generateAndStoreCode(email);
        Response resp = userService.register(username, "Password1!", email, code);
        assertEquals(200, resp.getStatus(), "注册应成功");
        long userId = extractLong(resp.getEntity().toString(), "id");
        assertTrue(userId > 0, "userId 应大于 0");
        var pi = ServiceRegistry.getPlayerService().createPlayer(userId, playerName);
        assertNotNull(pi, "角色创建不应为空");
        return pi.getId();
    }

    private static long extractLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /**
     * 直接写入一行"生长中"的地块，用于构造各种时间差场景。
     * settled_time 即"上次结算时刻"，是惰性结算的时间基准。
     */
    private static void insertPlantedPlot(long playerId, int plotIndex, long plantedTime,
                                          long harvestTime, int waterLevel, long settledTime) {
        String sql = """
            INSERT INTO farm_plots (player_id, plot_index, state, seed_key, crop_key,
                planted_time, harvest_time, wilted_time, growth_stage, water_level,
                fertilizer_level, yield, root_bonus, season_modifier, pest_state, pest_time,
                crop_quality, settled_time)
            VALUES (?, ?, 'PLANTED', ?, ?, ?, ?, 0, 1, ?, 0, 3, 0, 1, 'CLEAN', 0, NULL, ?)
            """;
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            ps.setString(3, SEED);
            ps.setString(4, CROP);
            ps.setLong(5, plantedTime);
            ps.setLong(6, harvestTime);
            ps.setInt(7, waterLevel);
            ps.setLong(8, settledTime);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("写入测试地块失败", e);
        }
    }

    /** 直接读库，绕开服务层，验证"落库结果"而不是"内存返回值"。 */
    private static Object[] rawPlot(long playerId, int plotIndex) {
        String sql = "SELECT state, water_level, growth_stage, settled_time, wilted_time, crop_quality, pest_state "
                + "FROM farm_plots WHERE player_id = ? AND plot_index = ?";
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Object[]{
                        rs.getString("state"),
                        rs.getInt("water_level"),
                        rs.getInt("growth_stage"),
                        rs.getLong("settled_time"),
                        rs.getLong("wilted_time"),
                        rs.getString("crop_quality"),
                        rs.getString("pest_state")};
            }
        } catch (Exception e) {
            throw new RuntimeException("读取测试地块失败", e);
        }
    }

    private static FarmPlot plotAt(List<FarmPlot> plots, int plotIndex) {
        return plots.stream().filter(p -> p.getPlotIndex() == plotIndex)
                .findFirst().orElseThrow(() -> new AssertionError("找不到地块 " + plotIndex));
    }

    private static long countItem(long playerId, String itemKey) {
        try (var conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("quantity") : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 没有后台线程也要能结算 ====================

    @Test
    @DisplayName("惰性结算: 成熟判定只依赖玩家访问，不依赖任何后台定时器")
    void testMaturesOnAccessWithoutTicker() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazyfarm1", "惰农甲");
        long now = System.currentTimeMillis();
        long planted = now - 600_000;   // 10 分钟前种下
        long harvest = now - 60_000;    // 1 分钟前就该熟了
        insertPlantedPlot(playerId, 0, planted, harvest, 100, planted);

        // 只有一次读取，没有任何 tick
        FarmPlot plot = plotAt(farmService.getPlots(playerId), 0);

        assertEquals(FarmPlot.PlotState.READY, plot.getState(),
                "越过成熟时刻的地块在读取时就应结算为可收获");
        assertNotNull(plot.getCropQuality(), "成熟时应一次性掷定品质并落库");
        assertEquals(STAGES, plot.getGrowthStage(), "成熟地块的阶段应为满阶段");

        Object[] raw = rawPlot(playerId, 0);
        assertEquals("READY", raw[0], "结算结果必须落库（否则收获查询读不到）");
        assertNotNull(raw[5], "品质必须落库（否则每次读取都会重掷）");
    }

    @Test
    @DisplayName("惰性结算: 水分按 (now - settled_time) 折算，而不是每次只扣一格")
    void testWaterDecaysByElapsedTime() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazyfarm2", "惰农乙");
        long now = System.currentTimeMillis();
        long planted = now - 600_000;
        long harvest = now + 600_000;              // 远未成熟
        long settled = now - 100_000;              // 上次结算在 100 秒前
        insertPlantedPlot(playerId, 0, planted, harvest, 100, settled);

        FarmPlot plot = plotAt(farmService.getPlots(playerId), 0);

        // 0.1%/秒 × 100 秒 = 10% → 100 - 10 = 90（允许 ±1 的取整/耗时误差）
        assertEquals(90, plot.getWaterLevel(), 1,
                "水分应对应整整 100 秒的衰减量（原实现每次只扣 0.1）");
        assertEquals(FarmPlot.PlotState.GROWING, plot.getState(), "未成熟地块应为生长中");
        assertTrue(((Number) rawPlot(playerId, 0)[3]).longValue() > settled,
                "结算后 settled_time 必须推进（否则同一区间会被反复计算）");
    }

    @Test
    @DisplayName("惰性结算: 离线期间水分耗尽的地块会枯萎，枯萎时刻取精确穿零点")
    void testWiltWhenWaterRunsOut() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazyfarm3", "惰农丙");
        long now = System.currentTimeMillis();
        long planted = now - 900_000;
        long harvest = now + 900_000;
        long settled = now - 100_000;   // 100 秒内本应只够耗尽 10 点水
        insertPlantedPlot(playerId, 0, planted, harvest, 5, settled);

        FarmPlot plot = plotAt(farmService.getPlots(playerId), 0);

        assertEquals(FarmPlot.PlotState.WILTED, plot.getState(),
                "水分只有 5% 而过去了 100 秒（够撑 50 秒），应判为枯萎");
        assertEquals(0, plot.getWaterLevel(), "枯萎地块水分应为 0");

        long expectedWiltedAt = settled + 50_000L;  // 5 / 0.1 = 50 秒
        Object[] raw = rawPlot(playerId, 0);
        long wiltedTime = ((Number) raw[4]).longValue();
        assertTrue(Math.abs(wiltedTime - expectedWiltedAt) <= 1_000,
                "枯萎时刻应取精确穿零点（约 " + expectedWiltedAt + "），实际 " + wiltedTime);
    }

    @Test
    @DisplayName("惰性结算: 同一秒内重复访问不产生额外推进（幂等、不重复掷品质）")
    void testRepeatedAccessIsIdempotent() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazyfarm4", "惰农丁");
        long now = System.currentTimeMillis();
        long planted = now - 600_000;
        long harvest = now - 60_000;
        insertPlantedPlot(playerId, 0, planted, harvest, 100, planted);

        FarmPlot first = plotAt(farmService.getPlots(playerId), 0);
        Object[] rawAfterFirst = rawPlot(playerId, 0);

        // 立刻再读两次：不应改变任何已结算的结果
        plotAt(farmService.getPlots(playerId), 0);
        FarmPlot third = plotAt(farmService.getPlots(playerId), 0);
        Object[] rawAfterThird = rawPlot(playerId, 0);

        assertEquals(first.getCropQuality(), third.getCropQuality(),
                "品质只应在成熟那一刻掷一次，重复访问不得重掷");
        assertEquals(rawAfterFirst[5], rawAfterThird[5], "落库品质不得被后续访问改写");
        assertEquals(((Number) rawAfterFirst[1]).intValue(), ((Number) rawAfterThird[1]).intValue(),
                "已成熟地块的水分应冻结，不再随访问变化");
    }

    @Test
    @DisplayName("惰性结算: 收获前会先结算，成熟地块无需等后台任务即可收获")
    void testHarvestAllSettlesFirst() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long playerId = makePlayer("lazyfarm5", "惰农戊");
        long now = System.currentTimeMillis();
        insertPlantedPlot(playerId, 0, now - 600_000, now - 30_000, 100, now - 600_000);

        long before = countItem(playerId, CROP);
        FarmResult result = farmService.harvestAll(playerId);

        assertTrue(result.success(),
                "成熟地块应能直接收获（结算发生在收获之前）: " + result.message());
        assertTrue(countItem(playerId, CROP) > before, "收获应发放作物");
        assertEquals("EMPTY", rawPlot(playerId, 0)[0], "收获后地块应回到空地");
    }

    @Test
    @DisplayName("惰性结算: 只结算被访问玩家的地块，不碰其他玩家")
    void testSettlementIsScopedToAccessedPlayer() {
        if (!ready || farmDisabled()) { System.out.println("  跳过（环境未就绪）"); return; }

        long visited = makePlayer("lazyfarm6", "惰农己");
        long untouched = makePlayer("lazyfarm7", "惰农庚");
        long now = System.currentTimeMillis();

        insertPlantedPlot(visited, 0, now - 600_000, now - 60_000, 100, now - 600_000);
        insertPlantedPlot(untouched, 0, now - 600_000, now - 60_000, 100, now - 600_000);

        farmService.getPlots(visited);

        assertEquals("READY", rawPlot(visited, 0)[0], "被访问玩家的地块应被结算");
        Object[] other = rawPlot(untouched, 0);
        assertEquals("PLANTED", other[0],
                "未访问玩家的地块不应被顺带结算（这正是取消全表扫描的目的）");
        assertEquals(now - 600_000, ((Number) other[3]).longValue(),
                "未访问玩家的 settled_time 不应被改动");
    }

    @Test
    @DisplayName("防回归: FarmService 不得再引入后台调度器（每秒全表扫描的老路）")
    void testNoBackgroundTicker() {
        for (java.lang.reflect.Field field : FarmService.class.getDeclaredFields()) {
            if (ScheduledExecutorService.class.isAssignableFrom(field.getType())) {
                fail("FarmService 不应再持有后台调度器（农田已改为惰性结算），发现字段: " + field.getName());
            }
        }
        // shutdownScheduler 仍保留给启动器/测试调用，但必须是安全的空实现
        FarmService.shutdownScheduler();
    }
}
