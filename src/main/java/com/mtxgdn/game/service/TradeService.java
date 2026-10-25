package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.TradeDAO;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TradeService {

    private static final GameLogger LOG = GameLogger.getLogger(TradeService.class);

    private static final double TRADE_FEE_RATE = 0.05;

    /** 挂单上架时长（小时）。此前挂单永不过期，玩家挂满后列表被永久占用。 */
    private static final int LISTING_DURATION_HOURS =
            Math.max(1, AppConfig.getInt("market.listing_duration_hours", 72));

    /** 价格历史保留天数（超过则清理）。 */
    private static final int PRICE_HISTORY_KEEP_DAYS =
            Math.max(1, AppConfig.getInt("market.price_history_keep_days", 30));

    /** 行情查询默认统计窗口（天）。 */
    private static final int DEFAULT_QUOTE_DAYS = 7;

    /** 行情查询允许的最大窗口（天），防止一次拉全表。 */
    private static final int MAX_QUOTE_DAYS = 90;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** 坊市后台任务调度器：到期挂单回收（每 60s）+ 价格历史清理（每数小时）。 */
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trade-market-sweeper");
                t.setDaemon(true); // 守护线程：不阻止 JVM 退出
                return t;
            });

    /** 老库回填只做一次。 */
    private static final AtomicBoolean BACKFILL_DONE = new AtomicBoolean(false);

    /**
     * 价格历史清理的执行间隔（小时）。
     * <p>
     * 历史表只增不减会无限膨胀，必须定期删除超过保留天数的记录。
     * 保留粒度是天级的（{@link #PRICE_HISTORY_KEEP_DAYS}），间隔取小时级即可。
     */
    private static final int PRICE_HISTORY_CLEANUP_INTERVAL_HOURS =
            Math.max(1, AppConfig.getInt("market.price_history_cleanup_interval_hours", 6));

    static {
        // 首个 tick 延迟 20s（等数据库初始化完成），之后每 60s 扫一次到期挂单。
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 惰性构造，避免静态初始化期就依赖 ServiceRegistry 造成循环初始化。
                ServiceRegistry.getTradeService().sweepExpiredListings();
            } catch (Throwable t) {
                // 绝不让异常冒泡：一次失败不应终止后续所有 tick
                LOG.warn("坊市到期挂单回收任务异常: " + t);
            }
        }, 20, 60, TimeUnit.SECONDS);

        // 价格历史清理：与回收任务共用同一个单线程调度器（两者都是低频 DB 操作，
        // 串行执行反而避免与回收任务争抢连接）。首个 tick 延迟 5 分钟，避开启动高峰。
        long cleanupPeriodHours = PRICE_HISTORY_CLEANUP_INTERVAL_HOURS;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int deleted = ServiceRegistry.getTradeService().cleanupPriceHistory();
                if (deleted > 0) {
                    LOG.info("坊市已清理过期价格历史 " + deleted + " 条（保留 " + PRICE_HISTORY_KEEP_DAYS + " 天）");
                }
            } catch (Throwable t) {
                LOG.warn("坊市价格历史清理任务异常: " + t);
            }
        }, 5, cleanupPeriodHours * 60, TimeUnit.MINUTES);
    }

    /** 停止到期回收任务（服务端关闭 / 测试清理时调用）。 */
    public static void shutdown() {
        scheduler.shutdown();
    }

    /** 配置的挂单时长（小时），供接口展示。 */
    public static int getListingDurationHours() {
        return LISTING_DURATION_HOURS;
    }

    /** 价格历史保留天数，供接口/日志展示。 */
    public static int getPriceHistoryKeepDays() {
        return PRICE_HISTORY_KEEP_DAYS;
    }

    private final ItemService itemService;
    private final PlayerService playerService;

    private static final TradeDAO TRADE_DAO = new TradeDAO();

    public TradeService() {
        this.itemService = ServiceRegistry.getItemService();
        this.playerService = ServiceRegistry.getPlayerService();
    }

    public Map<String, Object> listItem(long playerId, String itemKey, int quantity, long priceSpiritStones) {
        // 严格验证输入参数
        if (quantity <= 0) {
            return createResult(false, I18n.t("trade.list.quantity_invalid"));
        }
        if (quantity > 999_999) {
            return createResult(false, I18n.t("trade.list.quantity_too_large"));
        }
        if (priceSpiritStones <= 0) {
            return createResult(false, I18n.t("trade.list.price_invalid"));
        }
        if (priceSpiritStones > 999_999_999L) {
            return createResult(false, I18n.t("trade.list.price_too_large"));
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            return createResult(false, I18n.t("trade.list.item_not_found"));
        }

        String fullKey = item.getFullKey();

        long fee = calculateFee(playerId, priceSpiritStones);

        try {
            DatabaseManager.runTransaction(conn -> {
                // 在事务内使用锁检查物品数量
                long currentQuantity = TRADE_DAO.findPlayerItemQuantityForUpdate(conn, playerId, fullKey);
                if (currentQuantity < 0) {
                    throw new SQLException(I18n.t("trade.list.no_item", item.getName()));
                }
                if (currentQuantity < quantity) {
                    throw new SQLException(I18n.t("trade.list.not_enough",
                            item.getName(), currentQuantity, quantity));
                }
                
                if (!itemService.removeItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException(I18n.t("trade.list.deduct_failed", fullKey));
                }
                createListing(conn, playerId, fullKey, quantity, priceSpiritStones, fee);
                return null;
            });

            Map<String, Object> result = createResult(true,
                    I18n.t("trade.list.success", item.getName(), fullKey, quantity,
                            priceSpiritStones, fee, LISTING_DURATION_HOURS));
            result.put("fee", fee);
            result.put("expiresInHours", LISTING_DURATION_HOURS);
            return result;
        } catch (Exception e) {
            return createResult(false, I18n.t("trade.list.failed", rootMessage(e)));
        }
    }

    public Map<String, Object> buyItem(long buyerPlayerId, long listingId) {
        // S6 修复：把 listing 读取移入事务内并加锁，避免事务外快照 + 竞态覆盖
        final TradeListing[] purchased = new TradeListing[1];
        try {
            DatabaseManager.runTransaction(conn -> {
                TradeListing listing = getListingForUpdate(conn, listingId);
                if (listing == null) {
                    throw new SQLException(I18n.t("trade.buy.listing_gone"));
                }
                if (listing.sellerPlayerId == buyerPlayerId) {
                    throw new SQLException(I18n.t("trade.buy.self_listing"));
                }
                long totalCost = listing.priceSpiritStones;
                long buyerSpiritStones = itemService.getSpiritStoneCount(conn, buyerPlayerId);
                if (buyerSpiritStones < totalCost) {
                    throw new SQLException(I18n.t("trade.buy.insufficient_stones",
                            totalCost, buyerSpiritStones));
                }
                int removed = markListingAsSold(conn, listingId);
                if (removed == 0) {
                    throw new SQLException(I18n.t("trade.buy.expired"));
                }
                executePurchase(conn, buyerPlayerId, listing);
                purchased[0] = listing;
                return null;
            });

            TradeListing listing = purchased[0];
            Item item = ItemRegistry.get(listing.itemKey);
            Map<String, Object> result = createResult(true,
                    I18n.t("trade.buy.success",
                            item != null ? item.getName() : listing.itemKey,
                            listing.quantity, listing.priceSpiritStones, listing.fee));
            result.put("cost", listing.priceSpiritStones);
            result.put("fee", listing.fee);
            return result;
        } catch (Exception e) {
            return createResult(false, I18n.t("trade.buy.failed", rootMessage(e)));
        }
    }

    /** 解包 runTransaction 包装的 RuntimeException，返回业务异常的真实原因消息。 */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause instanceof RuntimeException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    /**
     * S6 修复：事务内读取 listing 并加锁（SQLite 在事务内 UPDATE 即隐式写锁）。
     * 必须在事务内调用。
     * <p>
     * 同时过滤已到期挂单：只靠定时回收任务是不够的（任务最长 60s 才跑一次），
     * 否则到期后仍可被购买，买家买到"僵尸挂单"、卖家物品却已被回收退回。
     */
    private TradeListing getListingForUpdate(Connection conn, long listingId) throws SQLException {
        return TRADE_DAO.findActiveListingForUpdate(conn, listingId,
                new java.sql.Timestamp(System.currentTimeMillis()));
    }

    /**
     * 撤回挂单。
     * <p>
     * 与购买走同一条并发安全路径：listing 必须在<b>事务内</b>读取并加锁，
     * 否则事务外快照 + 事务内抢占会让"撤回"与"到期回收"/"被买走"并发时重复退回物品。
     * 例如：撤回读到 active 快照 → 回收任务抢先置 expired 并退物 → 撤回再把 active 改成
     * sold 又退一次 → 玩家凭空多出一份物品。
     */
    public Map<String, Object> cancelListing(long playerId, long listingId) {
        final TradeListing[] cancelled = new TradeListing[1];
        try {
            DatabaseManager.runTransaction(conn -> {
                // 事务内读取：拿到的一定是当前有效（未到期、未成交）的挂单
                TradeListing listing = getListingForUpdate(conn, listingId);
                if (listing == null) {
                    throw new SQLException(I18n.t("trade.cancel.listing_gone"));
                }
                if (listing.sellerPlayerId != playerId) {
                    throw new SQLException(I18n.t("trade.cancel.not_owner"));
                }
                int removed = markListingAsSold(conn, listingId);
                if (removed == 0) {
                    throw new SQLException(I18n.t("trade.cancel.expired"));
                }
                itemService.addItem(conn, playerId, listing.itemKey, listing.quantity);
                cancelled[0] = listing;
                return null;
            });

            TradeListing listing = cancelled[0];
            Item item = ItemRegistry.get(listing.itemKey);
            return createResult(true,
                    I18n.t("trade.cancel.success",
                            item != null ? item.getName() : listing.itemKey, listing.quantity));
        } catch (Exception e) {
            return createResult(false, I18n.t("trade.cancel.failed", rootMessage(e)));
        }
    }

    public List<TradeListing> getActiveListings() {
        // 到期挂单不再展示（即使回收任务还没跑到）
        return TRADE_DAO.findActiveListings(new java.sql.Timestamp(System.currentTimeMillis()));
    }

    public List<TradeListing> getPlayerListings(long playerId) {
        return TRADE_DAO.findPlayerListings(playerId, new java.sql.Timestamp(System.currentTimeMillis()));
    }

    // ==================== 挂单到期回收 ====================

    /**
     * 扫描并回收到期挂单：把物品退回卖家，挂单标记为 expired。
     * <p>
     * 由定时任务调用，也可手动触发（测试/管理接口）。
     *
     * @return 本次回收的挂单数
     */
    public int sweepExpiredListings() {
        // 只有回填成功才置位：定时任务首个 tick 可能早于数据库就绪，
        // 若失败时也置位，历史挂单将永远拿不到到期时间（即永不过期）。
        if (!BACKFILL_DONE.get() && backfillLegacyExpiry()) {
            BACKFILL_DONE.set(true);
        }
        int expiredCount = expireDueListings();
        if (expiredCount > 0) {
            LOG.info("坊市已回收到期挂单 " + expiredCount + " 个，物品已退回卖家");
        }
        return expiredCount;
    }

    /**
     * 老库兼容：历史挂单的 expires_at 为 NULL（永不过期）。
     * 按 created_at + 配置时长回填，让挂单时限对老数据同样生效。
     * <p>
     * 用 Java 计算而非 SQL 日期函数，避免 SQLite/MySQL 方言差异。
     */
    private boolean backfillLegacyExpiry() {
        List<TradeDAO.LegacyExpiryRow> rows = TRADE_DAO.findLegacyActiveListings();
        if (rows.isEmpty()) return true;

        long durationMs = LISTING_DURATION_HOURS * 3600_000L;
        List<Long> ids = new ArrayList<>();
        List<java.sql.Timestamp> expiry = new ArrayList<>();
        for (TradeDAO.LegacyExpiryRow row : rows) {
            // created_at 缺失时以当前时间为基准，避免这些挂单永远不过期
            long base = row.createdAt != null ? row.createdAt.getTime() : System.currentTimeMillis();
            ids.add(row.id);
            expiry.add(new java.sql.Timestamp(base + durationMs));
        }
        int filled;
        try {
            filled = TRADE_DAO.backfillLegacyExpiry(expiry, ids);
        } catch (RuntimeException e) {
            LOG.warn("回填历史挂单到期时间失败: " + e.getMessage());
            return false;
        }
        LOG.info("已为 " + filled + " 个历史坊市挂单补齐到期时间（" + LISTING_DURATION_HOURS + " 小时）");
        return true;
    }

    /** 回收所有已到期挂单，返回成功回收数量。 */
    private int expireDueListings() {
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        List<TradeDAO.DueListingRow> due = TRADE_DAO.findDueListings(now);

        int expired = 0;
        for (TradeDAO.DueListingRow row : due) {
            final long listingId = row.id;
            final long sellerId = row.sellerPlayerId;
            final String itemKey = row.itemKey;
            final int qty = row.quantity;
            try {
                Boolean claimed = DatabaseManager.runTransaction(conn -> {
                    // 先抢占：只有把 active 改成 expired 成功的一方负责退回物品。
                    // 否则会与买家并发购买竞争，导致物品被退回两次（复制物品）。
                    if (!TRADE_DAO.markListingExpired(conn, listingId)) return Boolean.FALSE;
                    itemService.addItem(conn, sellerId, itemKey, qty);
                    return Boolean.TRUE;
                });
                if (Boolean.TRUE.equals(claimed)) expired++;
            } catch (Exception e) {
                LOG.warn("回收到期挂单 #" + listingId + " 失败: " + rootMessage(e));
            }
        }
        return expired;
    }

    // ==================== 成交价格历史 / 行情 ====================

    /**
     * 查询某物品的近期成交行情，并给出当前在售最低价作为参考。
     *
     * @param itemKey 物品 key（支持短 key，自动补 mtxgdn: 前缀）
     * @param days    统计窗口天数；<=0 用默认 7 天，上限 90 天
     */
    public Map<String, Object> getPriceHistory(String itemKey, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            out.put("success", false);
            out.put("message", I18n.t("trade.list.item_not_found"));
            return out;
        }
        String fullKey = item.getFullKey();
        int window = days <= 0 ? DEFAULT_QUOTE_DAYS : Math.min(days, MAX_QUOTE_DAYS);
        java.sql.Timestamp since =
                new java.sql.Timestamp(System.currentTimeMillis() - window * DAY_MS);

        TradeDAO.PriceHistoryStats stats = TRADE_DAO.getPriceHistoryStats(fullKey, since);
        long count = stats.count, min = 0, max = 0, avg = 0, totalQty = 0;
        if (count > 0) {
            min = stats.minUnitPrice;
            max = stats.maxUnitPrice;
            avg = Math.round(stats.avgUnitPriceRaw);
            totalQty = stats.totalQuantity;
        }

        out.put("success", true);
        out.put("itemKey", fullKey);
        out.put("itemName", item.getName());
        out.put("days", window);
        out.put("tradeCount", count);
        out.put("totalQuantity", totalQty);
        if (count > 0) {
            out.put("minUnitPrice", min);
            out.put("maxUnitPrice", max);
            out.put("avgUnitPrice", avg);
            out.put("message", I18n.t("trade.price.summary",
                    item.getName(), window, count, avg, min, max));
        } else {
            // 交易日数为 0 时给一句"没有记录"的提示：它同时承担着
            // "别以为系统坏了"的安抚作用，所以即使在 en_us 下也要有对应译文。
            out.put("message", I18n.t("trade.price.no_records",
                    item.getName(), window));
        }

        // 当前在售最低单价，给玩家一个"现在挂多少能卖掉"的锚点
        long lowest = lowestActiveUnitPrice(fullKey);
        if (lowest > 0) {
            out.put("lowestActiveUnitPrice", lowest);
        }
        return out;
    }

    /** 当前在售挂单中的最低单价（总价 ÷ 数量），无在售返回 0。 */
    private long lowestActiveUnitPrice(String fullKey) {
        return TRADE_DAO.lowestActiveUnitPrice(fullKey,
                new java.sql.Timestamp(System.currentTimeMillis()));
    }

    /** 清理过期的价格历史记录，返回删除行数。 */
    public int cleanupPriceHistory() {
        java.sql.Timestamp cutoff =
                new java.sql.Timestamp(System.currentTimeMillis() - PRICE_HISTORY_KEEP_DAYS * DAY_MS);
        return TRADE_DAO.cleanupPriceHistory(cutoff);
    }

    private long calculateFee(long playerId, long priceSpiritStones) {
        long baseRate = (long)(priceSpiritStones * TRADE_FEE_RATE);
        Player seller = playerService.getPlayerById(playerId);
        boolean halfFee = seller != null && seller.getSpiritualRoot() != null
                && seller.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.TRADE_FEE_HALF);
        return halfFee ? Math.max(1, baseRate / 2) : Math.max(1, baseRate);
    }

    private void createListing(Connection conn, long playerId, String itemKey, int quantity,
                               long priceSpiritStones, long fee) throws SQLException {
        TRADE_DAO.insertListing(conn, playerId, itemKey, quantity, priceSpiritStones, fee,
                new java.sql.Timestamp(System.currentTimeMillis() + LISTING_DURATION_HOURS * 3600_000L));
    }

    /**
     * 记录一笔成交到价格历史（供行情查询）。
     * <p>
     * 单价 = 总价 ÷ 数量：同一物品挂单数量不同，只有单价可比。
     * 记录失败不影响成交本身（成交已完成，历史只是辅助信息），因此单独 try/catch。
     */
    private void recordPriceHistory(Connection conn, String itemKey, int quantity, long totalPrice) {
        TRADE_DAO.recordPriceHistory(conn, itemKey, quantity, totalPrice);
    }

    private void executePurchase(Connection conn, long buyerPlayerId, TradeListing listing) throws SQLException {
        // 坊市购买：跨等级扣除灵石（自动用玩家持有的任意等级灵石支付并找零）
        if (!itemService.removeSpiritStones(conn, buyerPlayerId, listing.priceSpiritStones)) {
            throw new SQLException(I18n.t("trade.pay.stone_deduct_failed"));
        }
        itemService.addItem(conn, buyerPlayerId, listing.itemKey, listing.quantity);

        long sellerReceive = listing.priceSpiritStones - listing.fee;
        if (!itemService.addSpiritStones(conn, listing.sellerPlayerId, sellerReceive)) {
            throw new SQLException(I18n.t("trade.pay.stone_grant_failed"));
        }
        // 成交后记录价格历史，供玩家查询行情（失败不影响成交本身）
        recordPriceHistory(conn, listing.itemKey, listing.quantity, listing.priceSpiritStones);
    }

    private int markListingAsSold(Connection conn, long listingId) throws SQLException {
        return TRADE_DAO.markListingSold(conn, listingId);
    }

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    public static class TradeListing {
        public long id;
        public long sellerPlayerId;
        public String itemKey;
        public int quantity;
        public long priceSpiritStones;
        public long fee;
        public String createdAt;
        /** 到期时间戳（毫秒）；0 表示永不过期（历史遗留挂单）。 */
        public long expiresAt;

        /** 剩余有效毫秒数；永不过期返回 -1。 */
        public long remainingMs() {
            return expiresAt <= 0 ? -1L : Math.max(0L, expiresAt - System.currentTimeMillis());
        }

        /** 单价（总价 ÷ 数量）。 */
        public long unitPrice() {
            return quantity > 0 ? Math.max(1L, priceSpiritStones / quantity) : priceSpiritStones;
        }
    }
}