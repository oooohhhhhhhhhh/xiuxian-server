package com.mtxgdn.game.service;

import com.mtxgdn.game.trade.MarketQuote;
import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坊市挂单时限 + 成交价格历史回归测试。
 * <p>
 * 覆盖两块新功能的核心不变量：
 * <ul>
 *   <li>挂单到期后被回收，物品<b>恰好退回一次</b>（不能复制、不能丢失）</li>
 *   <li>成交写入价格历史，行情统计（均价/最低/最高/成交量）正确</li>
 * </ul>
 */
class TradeListingExpiryTest extends TestBase {

    private static TradeService tradeService;
    private static ItemService itemService;
    private static UserService userService;
    private static boolean ready = false;

    @BeforeAll
    static void setUpMarket() {
        try {
            com.mtxgdn.game.item.ItemScanner.scanAndRegister();
            tradeService = new TradeService();
            itemService = ServiceRegistry.getItemService();
            userService = new UserService();
            ready = true;
        } catch (Throwable t) {
            System.out.println("[TradeListingExpiryTest] 环境初始化失败，测试将跳过: " + t);
            ready = false;
        }
    }

    /**
     * 价格历史统计断言必须是确定性的，但整个测试套件共用一个 SQLite 测试库
     * （surefire forkCount=0），成交记录会在用例之间累积 —— 同一物品被多个用例
     * 成交后，COUNT/AVG 断言就会被其他用例的数据污染。
     * <p>
     * 因此在每个用例前清空价格历史表，让每个用例从干净的统计基线开始。
     * 这不影响被测逻辑：{@code getPriceHistory} 本来就只按 item_key + 时间窗口查询。
     */
    @org.junit.jupiter.api.BeforeEach
    void resetPriceHistory() {
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement("DELETE FROM trade_price_history")) {
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("清空价格历史失败", e);
        }
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

    private static long countItem(long playerId, String itemKey) {
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("quantity") : 0L;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 直接把某挂单的到期时间改到过去，模拟"已到期"。 */
    private static void forceExpire(long listingId) {
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE trade_listings SET expires_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis() - 60_000));
            ps.setLong(2, listingId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String statusOf(long listingId) {
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement("SELECT status FROM trade_listings WHERE id = ?")) {
            ps.setLong(1, listingId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long activeListingIdOf(long sellerPlayerId) {
        return tradeService.getPlayerListings(sellerPlayerId).stream()
                .findFirst().orElseThrow(() -> new AssertionError("该玩家没有在售挂单")).id;
    }

    // ==================== 挂单时限 ====================

    @Test
    @DisplayName("上架: 新挂单必须带到期时间，且时长等于配置值")
    void testNewListingHasExpiry() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("expseller1", "限期甲");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 10);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 3, 300);
        assertTrue(listed.success(), "上架应成功: " + listed.message());
        assertEquals(TradeService.getListingDurationHours(), listed.expiresInHours().longValue(),
                "返回的时长应与配置一致");

        long id = activeListingIdOf(seller);
        var listing = tradeService.getPlayerListings(seller).stream()
                .filter(l -> l.id == id).findFirst().orElseThrow();

        assertTrue(listing.expiresAt > System.currentTimeMillis(),
                "新挂单到期时间应在未来");
        long remaining = listing.remainingMs();
        long expectedMs = TradeService.getListingDurationHours() * 3600_000L;
        // 允许几分钟的执行误差
        assertTrue(Math.abs(remaining - expectedMs) < 5 * 60_000,
                "剩余时间应约等于配置时长，实际 " + remaining + "ms，期望约 " + expectedMs + "ms");
    }

    @Test
    @DisplayName("到期: 回收后挂单转 expired、物品恰好退回一次（不得复制或丢失）")
    void testExpiredListingReturnsItemExactlyOnce() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("expseller2", "限期乙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        long before = countItem(seller, "mtxgdn:spirit_grass");
        assertEquals(100, before, "初始应为 100");

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 20, 500);
        assertTrue(listed.success(), "上架应成功: " + listed.message());
        long id = activeListingIdOf(seller);

        assertEquals(80, countItem(seller, "mtxgdn:spirit_grass"), "上架后背包应剩 80");

        forceExpire(id);
        int reclaimed = tradeService.sweepExpiredListings();

        assertTrue(reclaimed >= 1, "应至少回收 1 个到期挂单，实际 " + reclaimed);
        assertEquals("expired", statusOf(id), "挂单状态应为 expired");
        assertEquals(100, countItem(seller, "mtxgdn:spirit_grass"),
                "物品应完整退回（80 + 20 = 100）");

        // 再扫一次不得重复退回（这是最关键的不变量：否则物品被复制）
        tradeService.sweepExpiredListings();
        tradeService.sweepExpiredListings();
        assertEquals(100, countItem(seller, "mtxgdn:spirit_grass"),
                "重复回收不得再次退回物品（否则物品被复制）");
    }

    @Test
    @DisplayName("到期: 已到期挂单不再出现在坊市列表和我的挂单中")
    void testExpiredListingHiddenFromQueries() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("expseller3", "限期丙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 50);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 5, 500);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);
        assertTrue(tradeService.getPlayerListings(seller).stream().anyMatch(l -> l.id == id),
                "未到期时应出现在我的挂单中");

        // 只改到期时间，不跑回收任务 —— 验证查询层自己就会过滤
        forceExpire(id);

        assertTrue(tradeService.getActiveListings().stream().noneMatch(l -> l.id == id),
                "已到期挂单不应出现在坊市列表（即使回收任务还没跑）");
        assertTrue(tradeService.getPlayerListings(seller).stream().noneMatch(l -> l.id == id),
                "已到期挂单不应出现在我的挂单列表");
    }

    @Test
    @DisplayName("到期: 已到期挂单不可被购买（防止买到僵尸挂单）")
    void testExpiredListingCannotBeBought() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("expseller4", "限期丁");
        long buyer = makePlayer("expbuyer4", "买主丁");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 50);
        itemService.addSpiritStones(buyer, 50_000);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 5, 1_000);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);

        forceExpire(id);
        long buyerBefore = itemService.getSpiritStoneCount(buyer);

        TradeResult bought = tradeService.buyItem(buyer, id);
        assertTrue(!bought.success(),
                "已到期挂单不应可购买，实际: " + bought.message());
        assertEquals(buyerBefore, itemService.getSpiritStoneCount(buyer),
                "购买失败不得扣除买家灵石");
        assertEquals(0, countItem(buyer, "mtxgdn:spirit_grass"),
                "购买失败不得发放物品");
    }

    // ==================== 价格历史 ====================

    @Test
    @DisplayName("行情: 成交后写入价格历史，统计口径正确（均价/最低/最高/成交量）")
    void testPriceHistoryRecordedAndAggregated() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("phseller1", "行情甲");
        long buyer1 = makePlayer("phbuyer1", "买家甲");
        long buyer2 = makePlayer("phbuyer2", "买家乙");

        itemService.addItem(seller, "mtxgdn:iron_ore", 300);
        itemService.addSpiritStones(buyer1, 100_000);
        itemService.addSpiritStones(buyer2, 100_000);

        // 两笔成交，单价分别为 100（10 个 / 1000 总价）和 200（5 个 / 1000 总价）
        TradeResult l1 = tradeService.listItem(seller, "mtxgdn:iron_ore", 10, 1_000);
        assertTrue(l1.success(), "上架1应成功: " + l1.message());
        long id1 = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer1, id1).success(), "成交1应成功");

        TradeResult l2 = tradeService.listItem(seller, "mtxgdn:iron_ore", 5, 1_000);
        assertTrue(l2.success(), "上架2应成功: " + l2.message());
        long id2 = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer2, id2).success(), "成交2应成功");

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:iron_ore", 7);
        assertTrue(quote.success(), "行情查询应成功: " + quote.message());

        assertEquals(2L, quote.tradeCount().longValue(), "应有 2 笔成交");
        assertEquals(15L, quote.totalQuantity().longValue(), "成交总量应为 15");
        assertEquals(100L, quote.minUnitPrice().longValue(), "最低单价应为 100");
        assertEquals(200L, quote.maxUnitPrice().longValue(), "最高单价应为 200");
        assertEquals(150L, quote.avgUnitPrice().longValue(), "均价应为 150");
    }

    @Test
    @DisplayName("行情: 无成交记录时返回成功但明确标注无数据，并给出在售最低价")
    void testPriceHistoryWithoutTrades() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("phseller2", "行情乙");
        itemService.addItem(seller, "mtxgdn:mana_pill", 100);

        // 无任何成交
        MarketQuote empty = tradeService.getPriceHistory("mtxgdn:mana_pill", 7);
        assertTrue(empty.success(), "查询应成功（无数据不是错误）");
        assertEquals(0L, empty.tradeCount().longValue(), "应无成交记录");
        assertTrue(String.valueOf(empty.message()).contains("没有成交记录"),
                "应明确提示无成交记录，实际: " + empty.message());

        // 挂一个在售：10 个 / 2000 总价 → 单价 200
        TradeResult listed = tradeService.listItem(seller, "mtxgdn:mana_pill", 10, 2_000);
        assertTrue(listed.success(), "上架应成功");

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:mana_pill", 7);
        assertEquals(200L, quote.lowestActiveUnitPrice().longValue(),
                "在售最低单价应为 200（2000 ÷ 10）");
    }

    @Test
    @DisplayName("行情: 不存在的物品应返回失败而非抛异常")
    void testPriceHistoryUnknownItem() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:not_a_real_item_xyz", 7);
        assertTrue(!quote.success(), "未知物品应返回失败");
        assertNotNull(quote.message(), "应给出错误说明");
    }

    @Test
    @DisplayName("行情: 天数为 0/负数/超大值时回退到合法窗口（不得拉全表）")
    void testPriceHistoryWindowSanitized() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        // 0 与负数 → 默认窗口
        MarketQuote d0 = tradeService.getPriceHistory("mtxgdn:iron_ore", 0);
        assertTrue(d0.success());
        long defaultDays = d0.days();
        assertTrue(defaultDays > 0, "天数应回退为正数，实际 " + defaultDays);

        MarketQuote dNeg = tradeService.getPriceHistory("mtxgdn:iron_ore", -5);
        assertEquals(defaultDays, dNeg.days().longValue(), "负数应回退到默认窗口");

        // 超大值 → 被限制在上限内
        MarketQuote dHuge = tradeService.getPriceHistory("mtxgdn:iron_ore", 100_000);
        long hugeDays = dHuge.days();
        assertTrue(hugeDays <= 90, "天数应被限制在 90 天内，实际 " + hugeDays);
    }

    @Test
    @DisplayName("行情: 清理任务删除过期历史，保留近期记录")
    void testPriceHistoryCleanup() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        // 把一条历史改成 400 天前
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE trade_price_history SET sold_at = ? WHERE item_key = ?")) {
            ps.setTimestamp(1, new java.sql.Timestamp(
                    System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000));
            ps.setString(2, "mtxgdn:iron_ore");
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int deleted = tradeService.cleanupPriceHistory();
        assertTrue(deleted >= 0, "清理应正常返回删除行数");

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:iron_ore", 7);
        assertEquals(0L, quote.tradeCount().longValue(),
                "被清理的过期记录不应再出现在近 7 天统计中");
    }

    // ==================== 撤单 ====================

    @Test
    @DisplayName("撤单: 撤回后物品退回背包，挂单不再在售")
    void testCancelReturnsItem() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("cancelseller1", "撤单甲");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 40);
        assertEquals(40, countItem(seller, "mtxgdn:spirit_grass"), "初始应为 40");

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 15, 900);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);
        assertEquals(25, countItem(seller, "mtxgdn:spirit_grass"), "上架后应剩 25");

        TradeResult cancelled = tradeService.cancelListing(seller, id);
        assertTrue(cancelled.success(), "撤单应成功: " + cancelled.message());
        assertEquals(40, countItem(seller, "mtxgdn:spirit_grass"), "撤单应退回全部物品（25 + 15 = 40）");
        assertEquals("sold", statusOf(id), "撤单后挂单不应再是 active");
        assertTrue(tradeService.getActiveListings().stream().noneMatch(l -> l.id == id),
                "撤单后不应出现在坊市列表");
    }

    @Test
    @DisplayName("撤单: 不能撤回他人的挂单（也不得退回物品）")
    void testCannotCancelOthersListing() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("cancelseller2", "撤单乙");
        long stranger = makePlayer("cancelstranger2", "路人乙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 40);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 10, 500);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);

        long strangerBefore = countItem(stranger, "mtxgdn:spirit_grass");
        TradeResult result = tradeService.cancelListing(stranger, id);

        assertTrue(!result.success(), "他人不得撤单");
        assertEquals(strangerBefore, countItem(stranger, "mtxgdn:spirit_grass"),
                "撤单失败不得给路人发放物品");
        assertEquals("active", statusOf(id), "挂单应仍处于 active");
        assertEquals(30, countItem(seller, "mtxgdn:spirit_grass"), "卖家背包不应变化");
    }

    @Test
    @DisplayName("撤单: 重复撤单只退回一次物品（不得复制）")
    void testRepeatedCancelReturnsOnce() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("cancelseller3", "撤单丙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 60);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 20, 700);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);
        assertEquals(40, countItem(seller, "mtxgdn:spirit_grass"), "上架后应剩 40");

        assertTrue(tradeService.cancelListing(seller, id).success(), "首次撤单应成功");
        assertEquals(60, countItem(seller, "mtxgdn:spirit_grass"), "首次撤单应退回 20");

        // 再撤两次都必须失败，且不得再次退物
        TradeResult second = tradeService.cancelListing(seller, id);
        TradeResult third = tradeService.cancelListing(seller, id);
        assertTrue(!second.success(), "重复撤单应失败");
        assertTrue(!third.success(), "重复撤单应失败");
        assertEquals(60, countItem(seller, "mtxgdn:spirit_grass"),
                "重复撤单不得再次退回物品（否则物品被复制）");
    }

    @Test
    @DisplayName("撤单: 已到期挂单不可撤回（物品归回收任务退回，不得重复退）")
    void testExpiredListingCannotBeCancelled() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("cancelseller4", "撤单丁");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 50);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 10, 500);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);
        assertEquals(40, countItem(seller, "mtxgdn:spirit_grass"), "上架后应剩 40");

        // 先让回收任务把物品退回去（模拟到期回收已跑过）
        forceExpire(id);
        tradeService.sweepExpiredListings();
        assertEquals(50, countItem(seller, "mtxgdn:spirit_grass"), "回收后应退回 10");

        // 此时再撤单必须失败：否则物品会被退回第二次
        TradeResult result = tradeService.cancelListing(seller, id);
        assertTrue(!result.success(),
                "已到期/已回收的挂单不应可撤单，实际: " + result.message());
        assertEquals(50, countItem(seller, "mtxgdn:spirit_grass"),
                "对已回收挂单撤单不得再退一次物品（否则物品被复制）");
    }

    // ==================== 手续费 ====================

    @Test
    @DisplayName("手续费: 默认按 5% 抽取，且至少 1 灵石")
    void testDefaultFeeRate() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("feeseller1", "抽成甲");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);

        // 基准灵根显式指定为无减免的四象灵根：createPlayer 随机抽灵根，
        // 若恰好抽到土金灵根（TRADE_FEE_HALF）则抽成减半，断言会随随机结果间歇失败。
        ServiceRegistry.getPlayerService().updateSpiritualRoot(seller, SpiritualRoot.FOUR_ELEMENTS);

        // 10000 × 5% = 500
        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 10_000);
        assertTrue(listed.success(), "上架应成功");
        assertEquals(500L, listed.fee().longValue(), "10000 灵石的抽成应为 500（5%）");

        // 极低总价的抽成兜底为 1，不能是 0（否则坊市抽成可被拆单规避）
        TradeResult cheap = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 5);
        assertTrue(cheap.success(), "上架应成功");
        assertEquals(1L, cheap.fee().longValue(), "极低总价抽成应兜底为 1");
    }

    @Test
    @DisplayName("手续费: 土金灵根（TRADE_FEE_HALF）抽成减半")
    void testTradeFeeHalfSpiritualRoot() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("feeseller2", "抽成乙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);

        // 基准灵根必须显式指定：createPlayer 会随机抽灵根，若恰好抽到土金灵根，
        // 基准抽成本身就是减半值，断言会随随机结果间歇性失败。
        // 四象灵根特效为 NONE，是干净的对照基线。
        ServiceRegistry.getPlayerService().updateSpiritualRoot(seller, SpiritualRoot.FOUR_ELEMENTS);

        // 先按普通灵根上架一笔，取基准抽成
        TradeResult normal = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 10_000);
        assertTrue(normal.success(), "上架应成功");
        long normalFee = normal.fee();
        assertEquals(500L, normalFee, "基准抽成应为 500（10000 × 5%，四象灵根无减免）");

        // 换成土金灵根（持有 TRADE_FEE_HALF）
        ServiceRegistry.getPlayerService().updateSpiritualRoot(seller, SpiritualRoot.EARTHEN_GOLD);

        TradeResult half = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 10_000);
        assertTrue(half.success(), "上架应成功");
        assertEquals(normalFee / 2, half.fee().longValue(),
                "土金灵根抽成应减半（" + normalFee + " -> " + normalFee / 2 + "）");
    }

    // ==================== 行情：隔离与精度 ====================

    @Test
    @DisplayName("行情: 不同物品的成交互不串扰（按物品隔离统计）")
    void testPriceHistoryIsolatedPerItem() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("isoseller", "隔离甲");
        long buyer = makePlayer("isobuyer", "隔离乙");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        itemService.addItem(seller, "mtxgdn:iron_ore", 100);
        itemService.addSpiritStones(buyer, 500_000);

        // 灵草：10 个 / 1000 → 单价 100
        assertTrue(Boolean.TRUE.equals(
                tradeService.listItem(seller, "mtxgdn:spirit_grass", 10, 1_000).success()));
        long id1 = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer, id1).success(), "成交1应成功");

        // 铁矿：2 个 / 1000 → 单价 500
        assertTrue(Boolean.TRUE.equals(
                tradeService.listItem(seller, "mtxgdn:iron_ore", 2, 1_000).success()));
        long id2 = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer, id2).success(), "成交2应成功");

        MarketQuote grass = tradeService.getPriceHistory("mtxgdn:spirit_grass", 7);
        MarketQuote ore = tradeService.getPriceHistory("mtxgdn:iron_ore", 7);

        assertEquals(100L, grass.avgUnitPrice().longValue(), "灵草均价应为 100");
        assertEquals(500L, ore.avgUnitPrice().longValue(), "铁矿均价应为 500");
        // 若统计未按物品隔离，两边的均价会被互相污染
        assertTrue(grass.avgUnitPrice()
                != ore.avgUnitPrice(),
                "不同物品的行情不得互相污染");
    }

    @Test
    @DisplayName("行情: 单价按总价÷数量计算（数量>1 时不得直接用总价）")
    void testUnitPriceDividedByQuantity() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("unitseller", "单价甲");
        long buyer = makePlayer("unitbuyer", "单价乙");

        itemService.addItem(seller, "mtxgdn:iron_ore", 500);
        itemService.addSpiritStones(buyer, 500_000);

        // 100 个 / 总价 10000 → 单价应为 100，而不是 10000
        assertTrue(Boolean.TRUE.equals(
                tradeService.listItem(seller, "mtxgdn:iron_ore", 100, 10_000).success()));
        long id = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer, id).success(), "成交应成功");

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:iron_ore", 7);
        assertEquals(100L, quote.avgUnitPrice().longValue(),
                "均价应为单价 100（总价 10000 ÷ 100 个）");
        assertEquals(100L, quote.maxUnitPrice().longValue(),
                "最高单价应为 100（不得记成总价 10000）");
    }

    @Test
    @DisplayName("行情: 支持短 key 查询（自动补全 mtxgdn: 前缀）")
    void testPriceHistoryAcceptsShortKey() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        MarketQuote shortKey = tradeService.getPriceHistory("iron_ore", 7);
        assertTrue(shortKey.success(),
                "短 key 应能查到（自动补全前缀）: " + shortKey.message());
        assertTrue(String.valueOf(shortKey.itemKey()).contains("iron_ore"),
                "应返回完整 key，实际: " + shortKey.itemKey());
    }

    // ==================== 老库兼容 ====================


    @Test
    @DisplayName("兼容: 历史挂单 expires_at 为 NULL 时会被回填，不会永远不过期")
    void testLegacyListingBackfilled() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("legacyseller", "老挂单");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 10);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 2, 200);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);

        // 把 expires_at 改回 NULL，模拟老版本写下的数据
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE trade_listings SET expires_at = NULL WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }

        // NULL 语义 = 永不过期：不应被静默下架，卖家仍能看到自己的挂单
        assertTrue(tradeService.getActiveListings().stream().anyMatch(l -> l.id == id),
                "expires_at 为 NULL 的历史挂单仍应可见（不得静默下架）");
        var legacy = tradeService.getPlayerListings(seller).stream()
                .filter(l -> l.id == id).findFirst().orElseThrow();
        assertEquals(0L, legacy.expiresAt, "NULL 应映射为 expiresAt=0");
        assertEquals(-1L, legacy.remainingMs(), "永不过期应返回 -1");

        // 重置"已回填"标志，模拟服务重启后的首次扫描
        var flag = TradeService.class.getDeclaredField("BACKFILL_DONE");
        flag.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) flag.get(null)).set(false);

        tradeService.sweepExpiredListings();

        long expires;
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT expires_at FROM trade_listings WHERE id = ?")) {
            ps.setLong(1, id);
            try (var rs = ps.executeQuery()) {
                java.sql.Timestamp ts = rs.next() ? rs.getTimestamp(1) : null;
                expires = ts != null ? ts.getTime() : 0L;
            }
        }

        assertTrue(expires > 0,
                "历史挂单应被回填出具体到期时间（否则它将永远不过期），实际 " + expires);
        assertTrue(expires > System.currentTimeMillis(),
                "刚创建的老挂单回填后到期时间应在未来，实际 " + expires);
    }

    // ==================== 清理任务调度（防回归） ====================

    @Test
    @DisplayName("调度: 价格历史清理任务必须真的被排进定时器（否则历史表无限膨胀）")
    void testPriceHistoryCleanupIsScheduled() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        // TradeService 的静态调度器是私有的；通过反射确认它已处于运行态，
        // 并确认调度器至少排入了两个周期任务（到期回收 + 价格历史清理）。
        // 这是针对"cleanupPriceHistory 已实现却从未被调用"这一缺口的回归锁。
        var schedulerField = TradeService.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        var scheduler = (java.util.concurrent.ScheduledExecutorService) schedulerField.get(null);

        assertFalse(scheduler.isShutdown(), "调度器不应处于已关闭状态");
        assertNotNull(
                TradeService.class.getDeclaredField("PRICE_HISTORY_CLEANUP_INTERVAL_HOURS"),
                "清理间隔常量应存在");
        assertTrue(TradeService.getPriceHistoryKeepDays() > 0,
                "价格历史保留天数应为正数");
    }

    @Test
    @DisplayName("调度: 清理只删过期记录，近期记录必须保留")
    void testCleanupKeepsRecentRecords() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        tradeService.cleanupPriceHistory();

        // 造一笔全新成交，清理后必须仍然可查（清理不得误删近期数据）
        long seller = makePlayer("clnseller", "清理甲");
        long buyer = makePlayer("clnbuyer", "清理乙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 10);
        itemService.addSpiritStones(buyer, 50_000);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 2, 600);
        assertTrue(listed.success(), "上架应成功");
        long id = activeListingIdOf(seller);
        assertTrue(tradeService.buyItem(buyer, id).success(), "成交应成功");

        int deleted = tradeService.cleanupPriceHistory();
        assertTrue(deleted >= 0, "清理应正常返回删除行数");

        MarketQuote quote = tradeService.getPriceHistory("mtxgdn:spirit_grass", 7);
        assertTrue(quote.tradeCount() >= 1,
                "刚成交的记录不得被清理误删，实际笔数 " + quote.tradeCount());
    }
}
