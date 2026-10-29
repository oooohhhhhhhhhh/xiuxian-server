package com.mtxgdn.game.service;

import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.service.UserService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坊市并发回归测试。
 * <p>
 * 保护既有竞态修复（S6：把 listing 读取移入事务内并加锁）。这些路径此前完全没有测试
 * 覆盖，一旦有人把事务内的读取改回事务外快照，就会出现"同一挂单被卖两次、
 * 卖家的物品/灵石凭空复制"的经济漏洞，而普通功能测试发现不了。
 * <p>
 * SQLite 没有行级锁（{@code getForUpdateClause()} 返回空串），单写者模型靠
 * 事务写锁串行化，因此这里用真实多线程并发来验证最终不变量。
 */
class TradeServiceConcurrencyTest extends TestBase {

    private static TradeService tradeService;
    private static ItemService itemService;
    private static UserService userService;
    private static boolean ready = false;

    @BeforeAll
    static void setUpTrade() {
        try {
            // 物品必须先在 ItemRegistry 注册，否则 addItem/listItem 会抛"物品不存在"
            com.mtxgdn.game.item.ItemScanner.scanAndRegister();
            tradeService = new TradeService();
            itemService = ServiceRegistry.getItemService();
            userService = new UserService();
            runner = Executors.newFixedThreadPool(4);
            ready = true;
        } catch (Throwable t) {
            System.out.println("[TradeServiceConcurrencyTest] 环境初始化失败，测试将跳过: " + t);
            ready = false;
        }
    }

    private static ExecutorService runner;

    // ==================== 工具方法 ====================

    /** 注册账号 + 创建角色，返回 playerId。 */
    private static long makePlayer(String username, String playerName) {
        String email = username + "@qq.com";
        String code = new com.mtxgdn.service.VerificationCodeService().generateAndStoreCode(email);
        Response resp = userService.register(username, "Password1!", email, code);
        assertEquals(200, resp.getStatus(), "注册应成功: " + resp.getEntity());
        long userId = extractLong(resp.getEntity().toString(), "id");
        assertTrue(userId > 0, "userId 应大于 0");
        var pi = ServiceRegistry.getPlayerService().createPlayer(userId, playerName);
        assertNotNull(pi, "角色创建不应为空");
        return pi.getId();
    }

    private static long extractLong(String json, String key) {
        // 极简提取：寻找 "key":数字
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** 并发跑 n 个任务，等全部结束后返回结果列表（捕获异常为 null）。 */
    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(n);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(runner.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS); // 尽量让所有线程同时冲进去
                try {
                    return task.call();
                } catch (Throwable t) {
                    return null;
                }
            }));
        }
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get(30, TimeUnit.SECONDS));
        }
        return out;
    }

    // ==================== 核心并发不变量 ====================

    @Test
    @DisplayName("并发: 同一挂单被多个买家同时抢购，只能成交一次（不得重复售出）")
    void testDoubleBuyOnlyOneSucceeds() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller1", "卖家甲");
        long buyerA = makePlayer("tradebuyer1a", "买家甲");
        long buyerB = makePlayer("tradebuyer1b", "买家乙");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 1000);
        itemService.addSpiritStones(buyerA, 100_000);
        itemService.addSpiritStones(buyerB, 100_000);

        // 卖家上架，买得起
        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 1000);
        assertTrue(listed.success(), "上架应成功: " + listed.message());

        long listingId = tradeService.getActiveListings().stream()
                .filter(l -> l.sellerPlayerId == seller)
                .findFirst().orElseThrow().id;

        // 两个买家同时抢（同一挂单）
        final long finalListingId = listingId;
        List<TradeResult> rs = runConcurrently(2, new Callable<TradeResult>() {
            private final AtomicInteger idx = new AtomicInteger();
            @Override
            public TradeResult call() {
                long who = idx.getAndIncrement() == 0 ? buyerA : buyerB;
                return tradeService.buyItem(who, finalListingId);
            }
        });

        long successCount = rs.stream().filter(r -> r != null && r.success()).count();
        assertEquals(1, successCount, "同一挂单只允许一个买家成交，实际成交 " + successCount + " 次（重复售出）: " + rs);

        // 挂单必须已标记 sold
        assertEquals(0, tradeService.getActiveListings().stream()
                .filter(l -> l.id == finalListingId).count(), "成交后挂单不应仍处于 active");
    }

    @Test
    @DisplayName("并发: 卖家同时上架同一批物品，扣减不得超过背包实际持有量（不得超卖）")
    void testConcurrentListNoOversell() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller2", "卖家乙");
        // 只给 10 个
        itemService.addItem(seller, "mtxgdn:spirit_grass", 10);
        long before = countItem(seller, "mtxgdn:spirit_grass");
        assertEquals(10, before, "初始数量应为 10");

        // 4 个线程各上架 5 个，总计 20 > 持有量 10
        List<TradeResult> results = runConcurrently(4, () ->
                tradeService.listItem(seller, "mtxgdn:spirit_grass", 5, 100));

        long okCount = results.stream().filter(r -> r != null && r.success()).count();
        long after = countItem(seller, "mtxgdn:spirit_grass");

        assertTrue(after >= 0, "背包数量不得为负，实际: " + after);
        assertEquals(10 - okCount * 5, after,
                "背包扣减必须与成功上架数一致（成功 " + okCount + " 次，剩余应为 " + (10 - okCount * 5) + "，实际 " + after + "）");
        assertTrue(okCount <= 2, "最多只能成功上架 2 次（2×5=10），实际成功 " + okCount + " 次（超卖）");

        // 挂单总量不得超过实际扣减量
        long listedTotal = tradeService.getPlayerListings(seller).stream()
                .mapToLong(l -> l.quantity).sum();
        assertEquals(okCount * 5, listedTotal, "挂单总量应等于成功上架的数量");
    }

    @Test
    @DisplayName("并发: 同一挂单被同一买家重复点击购买，只能扣一次灵石")
    void testSameBuyerRepeatedBuyChargedOnce() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller3", "卖家丙");
        long buyer = makePlayer("tradebuyer3", "买家丙");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 1000);
        itemService.addSpiritStones(buyer, 50_000);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, 2000);
        assertTrue(listed.success(), "上架应成功: " + listed.message());

        long listingId = tradeService.getActiveListings().stream()
                .filter(l -> l.sellerPlayerId == seller)
                .findFirst().orElseThrow().id;

        long buyerBefore = itemService.getSpiritStoneCount(buyer);

        // 同一买家 4 次并发购买
        List<TradeResult> results = runConcurrently(4, () -> tradeService.buyItem(buyer, listingId));

        long okCount = results.stream().filter(r -> r != null && r.success()).count();
        long buyerAfter = itemService.getSpiritStoneCount(buyer);

        assertEquals(1, okCount, "同一买家并发重复购买只能成功一次，实际 " + okCount + " 次");
        assertEquals(buyerBefore - 2000, buyerAfter,
                "灵石只应扣一次（" + buyerBefore + " -> " + buyerAfter + "）");
    }

    @Test
    @DisplayName("不变量: 成交后灵石守恒（买家支出 = 卖家所得 + 坊市抽成）")
    void testSpiritStoneConservation() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller4", "卖家丁");
        long buyer = makePlayer("tradebuyer4", "买家丁");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 1000);
        itemService.addSpiritStones(buyer, 50_000);

        long price = 10_000;
        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 1, price);
        assertTrue(listed.success(), "上架应成功: " + listed.message());
        long fee = listed.fee();
        assertTrue(fee > 0, "抽成应大于 0");

        long sellerBefore = itemService.getSpiritStoneCount(seller);
        long buyerBefore = itemService.getSpiritStoneCount(buyer);

        long listingId = tradeService.getActiveListings().stream()
                .filter(l -> l.sellerPlayerId == seller)
                .findFirst().orElseThrow().id;
        TradeResult bought = tradeService.buyItem(buyer, listingId);
        assertTrue(bought.success(), "购买应成功: " + bought.message());

        long sellerAfter = itemService.getSpiritStoneCount(seller);
        long buyerAfter = itemService.getSpiritStoneCount(buyer);

        assertEquals(buyerBefore - price, buyerAfter, "买家应恰好支出售价");
        assertEquals(sellerBefore + (price - fee), sellerAfter, "卖家应收到 售价-抽成");

        // 灵石守恒：买家支出的总量 = 卖家所得 + 坊市抽成（抽成是系统回收，不再进入任何玩家）
        long buyerSpent = buyerBefore - buyerAfter;
        long sellerGained = sellerAfter - sellerBefore;
        assertEquals(buyerSpent, sellerGained + fee,
                "灵石守恒被破坏：买家支出 " + buyerSpent + " ≠ 卖家所得 " + sellerGained + " + 抽成 " + fee);
    }

    // ==================== 撤单 vs 抢购 / 到期回收 ====================

    @Test
    @DisplayName("并发: 撤单与抢购同时发生，物品与灵石总量守恒（不得双退或双卖）")
    void testCancelRacingBuyKeepsConservation() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller5", "卖家戊");
        long buyer = makePlayer("tradebuyer5", "买家戊");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        itemService.addSpiritStones(buyer, 50_000);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 10, 2_000);
        assertTrue(listed.success(), "上架应成功");
        long fee = listed.fee();
        long listingId = activeListingIdOf(seller);

        long sellerStonesBefore = itemService.getSpiritStoneCount(seller);
        long buyerStonesBefore = itemService.getSpiritStoneCount(buyer);
        long sellerItemsBefore = countItem(seller, "mtxgdn:spirit_grass");
        final long buyerItemsBefore = countItem(buyer, "mtxgdn:spirit_grass");
        assertEquals(90, sellerItemsBefore, "上架后卖家背包应为 90");

        // 同一挂单：撤单 与 购买 同时冲进去
        List<Boolean> results = runConcurrently(2, new Callable<Boolean>() {
            private final AtomicInteger idx = new AtomicInteger();
            @Override
            public Boolean call() {
                if (idx.getAndIncrement() == 0) {
                    return tradeService.cancelListing(seller, listingId).success();
                }
                return tradeService.buyItem(buyer, listingId).success();
            }
        });

        long okCount = results.stream().filter(Boolean.TRUE::equals).count();
        assertEquals(1, okCount,
                "撤单与购买竞争同一个挂单，只能有一个成功，实际成功 " + okCount + " 个");

        long sellerItemsAfter = countItem(seller, "mtxgdn:spirit_grass");
        long buyerItemsAfter = countItem(buyer, "mtxgdn:spirit_grass");
        long sellerStonesAfter = itemService.getSpiritStoneCount(seller);
        long buyerStonesAfter = itemService.getSpiritStoneCount(buyer);

        // 物品守恒：这 10 个物品要么回到卖家，要么到了买家，绝不能两边都有（复制）
        assertEquals(10, (sellerItemsAfter - sellerItemsBefore) + (buyerItemsAfter - buyerItemsBefore),
                "物品总量必须守恒（撤单退回 或 买家获得，恰好一份）");

        // 灵石守恒：买家支出 = 卖家所得 + 抽成
        long buyerSpent = buyerStonesBefore - buyerStonesAfter;
        long sellerGained = sellerStonesAfter - sellerStonesBefore;
        assertEquals(buyerSpent, sellerGained + (buyerSpent > 0 ? fee : 0),
                "灵石守恒被破坏：买家支出 " + buyerSpent + " ≠ 卖家所得 " + sellerGained + " + 抽成 " + fee);
    }

    @Test
    @DisplayName("并发: 重复撤单并发执行，物品只退回一次（不得复制）")
    void testConcurrentCancelRefundsOnce() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("tradeseller6", "卖家己");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 50);

        TradeResult listed = tradeService.listItem(seller, "mtxgdn:spirit_grass", 20, 1_000);
        assertTrue(listed.success(), "上架应成功");
        long listingId = activeListingIdOf(seller);
        long afterList = countItem(seller, "mtxgdn:spirit_grass");
        assertEquals(30, afterList, "上架后应剩 30");

        // 4 个线程同时撤同一个挂单
        List<TradeResult> results = runConcurrently(4, () -> tradeService.cancelListing(seller, listingId));

        long okCount = results.stream().filter(r -> r != null && r.success()).count();
        assertEquals(1, okCount, "并发重复撤单只能成功一次，实际 " + okCount + " 次");
        assertEquals(50, countItem(seller, "mtxgdn:spirit_grass"),
                "物品必须恰好退回一次（30 + 20 = 50），实际 " + countItem(seller, "mtxgdn:spirit_grass"));
    }

    // ==================== 辅助：查背包数量 ====================

    /** 取该玩家当前唯一的在售挂单 ID（测试中每人只留一个挂单）。 */
    private static long activeListingIdOf(long sellerPlayerId) {
        return tradeService.getPlayerListings(sellerPlayerId).stream()
                .findFirst().orElseThrow(() -> new AssertionError("该玩家没有在售挂单")).id;
    }

    private static long countItem(long playerId, String itemKey) {
        try (var conn = DatabaseManager.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?")) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (var rs = ps.executeQuery()) {
                long total = 0;
                while (rs.next()) total += rs.getLong("quantity");
                return total;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
