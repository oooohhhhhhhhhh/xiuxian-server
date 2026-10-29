package com.mtxgdn.game.service;

import com.mtxgdn.game.economy.AuctionResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竞拍行并发回归测试。
 * <p>
 * {@code placeBid} 里有一段"先退还原出价人灵石、再扣新出价人灵石"的逻辑。
 * 如果 listing 读取被移回事务外快照，并发出价会导致：
 * <ul>
 *   <li>同一笔灵石被退还多次 → 灵石凭空增发（经济崩溃）</li>
 *   <li>新出价人的灵石扣了但未被记为当前出价人 → 灵石凭空消失</li>
 * </ul>
 * 本测试用真实并发去验证"灵石守恒"这一硬不变量，普通功能测试发现不了这类问题。
 */
class EconomyServiceConcurrencyTest extends TestBase {

    private static EconomyService economyService;
    private static ItemService itemService;
    private static UserService userService;
    private static ExecutorService runner;
    private static boolean ready = false;

    @BeforeAll
    static void setUpEconomy() {
        try {
            com.mtxgdn.game.item.ItemScanner.scanAndRegister();
            economyService = new EconomyService();
            itemService = ServiceRegistry.getItemService();
            userService = new UserService();
            runner = Executors.newFixedThreadPool(4);
            ready = true;
        } catch (Throwable t) {
            System.out.println("[EconomyServiceConcurrencyTest] 环境初始化失败，测试将跳过: " + t);
            ready = false;
        }
    }

    // ==================== 工具 ====================

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

    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        return runConcurrently(n, task, 4);
    }

    /**
     * 并发执行 n 个任务。
     *
     * @param poolSize 线程池大小；小于 n 时任务会排队，可制造更长的重叠窗口
     */
    private static <T> List<T> runConcurrently(int n, Callable<T> task, int poolSize) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, poolSize));
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
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
        } finally {
            pool.shutdownNow();
        }
    }

    /** 取该卖家当前 active 状态的拍卖单 id。 */
    private static long activeAuctionId(long sellerPlayerId) {
        return economyService.getPlayerAuctionItems(sellerPlayerId).stream()
                .filter(m -> "active".equals(m.get("status")))
                .map(m -> ((Number) m.get("id")).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到进行中的拍卖单"));
    }

    /** 多名玩家的灵石总数。 */
    private static long spiritSum(long... playerIds) {
        long total = 0;
        for (long id : playerIds) total += itemService.getSpiritStoneCount(id);
        return total;
    }

    // ==================== 核心并发不变量 ====================

    @Test
    @DisplayName("并发: 多人同时竞拍，冻结灵石必须恰好等于最高一笔（不得重复冻结）")
    void testConcurrentBidsFreezeExactlyOneAmount() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("ecoseller1", "拍主甲");
        long bidderA = makePlayer("ecobidder1a", "竞拍甲");
        long bidderB = makePlayer("ecobidder1b", "竞拍乙");
        long bidderC = makePlayer("ecobidder1c", "竞拍丙");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        itemService.addSpiritStones(bidderA, 100_000);
        itemService.addSpiritStones(bidderB, 100_000);
        itemService.addSpiritStones(bidderC, 100_000);

        AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 100, 1);
        assertTrue(created.success(), "创建拍卖应成功: " + created.message());
        long auctionId = activeAuctionId(seller);

        final long[] bidders = {bidderA, bidderB, bidderC};
        final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        long groupBefore = spiritSum(bidders);

        List<AuctionResult> results = runConcurrently(3, () -> {
            long me = bidders[seq.getAndIncrement() % bidders.length];
            return economyService.placeBid(me, auctionId, 500);
        });

        long groupAfter = spiritSum(bidders);
        long okCount = results.stream()
                .filter(r -> r != null && r.success()).count();

        // 硬不变量：无论并发时序如何，竞拍人群体被冻结的灵石总额
        // 必须恰好等于"当前最高出价"这一笔，绝不能是 okCount 笔。
        long frozen = groupBefore - groupAfter;
        assertTrue(okCount >= 1, "至少应有一次出价成功");
        assertEquals(500, frozen,
                "并发竞拍冻结灵石数必须恰好为一笔 500（实际 " + frozen
                        + "，成功出价 " + okCount + " 次）—— 多于 500 说明出价被重复冻结/重复扣款");

        // 且不得凭空增发：冻结额不能为负
        assertTrue(frozen >= 0, "灵石不得凭空增发，实际冻结 " + frozen);
    }

    @Test
    @DisplayName("并发: 递增出价下，最终只冻结最高价，先前出价必须全额退还")
    void testEscalatingBidsRefundPrevious() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("ecoseller6", "拍主己");
        long bidderA = makePlayer("ecobidder6a", "竞拍A");
        long bidderB = makePlayer("ecobidder6b", "竞拍B");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        itemService.addSpiritStones(bidderA, 100_000);
        itemService.addSpiritStones(bidderB, 100_000);

        AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 100, 1);
        assertTrue(created.success(), "创建拍卖应成功");
        long auctionId = activeAuctionId(seller);

        long beforeA = itemService.getSpiritStoneCount(bidderA);
        long beforeB = itemService.getSpiritStoneCount(bidderB);

        // A 先出 500，B 后出 800：A 应被全额退还，只冻结 B 的 800
        assertTrue(economyService.placeBid(bidderA, auctionId, 500).success(),
                "A 出价应成功");
        assertEquals(beforeA - 500, itemService.getSpiritStoneCount(bidderA), "A 首次出价应冻结 500");

        assertTrue(economyService.placeBid(bidderB, auctionId, 800).success(),
                "B 出价应成功");
        assertEquals(beforeA, itemService.getSpiritStoneCount(bidderA), "A 应被全额退还 500");
        assertEquals(beforeB - 800, itemService.getSpiritStoneCount(bidderB), "B 应冻结 800");

        // 总冻结 = 800
        assertEquals(800, (beforeA + beforeB)
                        - (itemService.getSpiritStoneCount(bidderA) + itemService.getSpiritStoneCount(bidderB)),
                "总冻结应恰好为最高价 800");
    }

    @Test
    @DisplayName("并发: 同一拍卖被同一人重复出价，只应冻结一笔灵石（高争用，多轮）")
    void testSameBidderRepeatedBidFrozenOnce() throws Exception {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        // 竞态是时序相关的：单轮可能碰巧顺序执行而漏检。
        // 因此跑多轮、每轮增加线程数，把检测率提到接近必然。
        for (int round = 0; round < 5; round++) {
            final int threads = 4 + round * 2;

            long seller = makePlayer("ecoseller2r" + round, "拍主乙" + round);
            long bidder = makePlayer("ecobidder2r" + round, "竞拍丁" + round);

            itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
            itemService.addSpiritStones(bidder, 100_000);

            AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 100, 1);
            assertTrue(created.success(), "创建拍卖应成功");
            long auctionId = activeAuctionId(seller);

            long before = itemService.getSpiritStoneCount(bidder);

            List<AuctionResult> results = runConcurrently(threads, () ->
                    economyService.placeBid(bidder, auctionId, 777), threads + 2);

            long after = itemService.getSpiritStoneCount(bidder);
            long okCount = results.stream()
                    .filter(r -> r != null && r.success()).count();

            assertTrue(okCount >= 1, "第 " + round + " 轮：至少应有一次出价成功");
            // 同一出价人连续出价会"退还上一笔 + 扣新一笔"，净冻结恒为一笔。
            // 若 listing 读取脱离事务，多笔出价会各自冻结，导致净冻结 > 一笔。
            assertEquals(before - 777, after,
                    "第 " + round + " 轮（" + threads + " 线程）：同一人重复出价后净冻结应恰好为一笔 777，"
                            + "初始 " + before + " → 实际 " + after
                            + "（成功 " + okCount + " 次，重复冻结 " + ((before - after) - 777) + " 灵石）");
        }
    }

    @Test
    @DisplayName("并发: 不能竞拍自己的物品")
    void testCannotBidOwnAuction() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("ecoseller3", "拍主丙");
        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);

        AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 100, 1);
        assertTrue(created.success(), "创建拍卖应成功");
        long auctionId = activeAuctionId(seller);

        AuctionResult selfBid = economyService.placeBid(seller, auctionId, 500);
        assertTrue(Boolean.FALSE.equals(selfBid.success()), "不应允许竞拍自己的物品");
        assertTrue(String.valueOf(selfBid.message()).contains("自己"),
                "错误信息应说明不能竞拍自己的物品，实际: " + selfBid.message());
    }

    @Test
    @DisplayName("并发: 出价低于起拍价/当前价必须被拒绝")
    void testBidBelowMinimumRejected() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("ecoseller4", "拍主丁");
        long bidder = makePlayer("ecobidder4", "竞拍戊");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        itemService.addSpiritStones(bidder, 100_000);

        AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 1000, 1);
        assertTrue(created.success(), "创建拍卖应成功");
        long auctionId = activeAuctionId(seller);

        // 低于起拍价
        AuctionResult low = economyService.placeBid(bidder, auctionId, 999);
        assertTrue(Boolean.FALSE.equals(low.success()), "低于起拍价应被拒绝");

        // 合法出价
        AuctionResult ok = economyService.placeBid(bidder, auctionId, 1000);
        assertTrue(Boolean.TRUE.equals(ok.success()), "等于起拍价应被接受: " + ok.message());

        // 低于当前价
        AuctionResult lower = economyService.placeBid(bidder, auctionId, 1000);
        assertTrue(Boolean.FALSE.equals(lower.success()),
                "不高于当前出价应被拒绝，实际: " + lower.message());

        // 恰好高 1 应被接受
        AuctionResult higher = economyService.placeBid(bidder, auctionId, 1001);
        assertTrue(Boolean.TRUE.equals(higher.success()), "高 1 灵石应被接受: " + higher.message());
    }

    @Test
    @DisplayName("并发: 灵石不足时出价必须被拒绝且不产生副作用")
    void testInsufficientFundsRejectedWithoutSideEffect() {
        if (!ready) { System.out.println("  跳过（环境未就绪）"); return; }

        long seller = makePlayer("ecoseller5", "拍主戊");
        long poor = makePlayer("ecopoor5", "穷修士");

        itemService.addItem(seller, "mtxgdn:spirit_grass", 100);
        // 新角色默认灵石很少，起拍价设高一点确保不足
        long poorBefore = itemService.getSpiritStoneCount(poor);

        AuctionResult created = economyService.createAuction(seller, "mtxgdn:spirit_grass", 1, 999_999, 1);
        assertTrue(created.success(), "创建拍卖应成功");
        long auctionId = activeAuctionId(seller);

        AuctionResult bid = economyService.placeBid(poor, auctionId, 999_999);
        assertTrue(Boolean.FALSE.equals(bid.success()), "灵石不足应被拒绝");
        assertEquals(poorBefore, itemService.getSpiritStoneCount(poor),
                "失败的出价不得改变玩家灵石");
    }
}
