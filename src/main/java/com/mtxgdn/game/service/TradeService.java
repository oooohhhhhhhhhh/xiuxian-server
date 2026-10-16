package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
                new TradeService().sweepExpiredListings();
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
                int deleted = new TradeService().cleanupPriceHistory();
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

    public TradeService() {
        this.itemService = ServiceRegistry.getItemService();
        this.playerService = ServiceRegistry.getPlayerService();
    }

    public Map<String, Object> listItem(long playerId, String itemKey, int quantity, long priceSpiritStones) {
        // 严格验证输入参数
        if (quantity <= 0) {
            return createResult(false, "数量必须大于 0");
        }
        if (quantity > 999_999) {
            return createResult(false, "数量不能超过 999,999");
        }
        if (priceSpiritStones <= 0) {
            return createResult(false, "价格必须大于 0");
        }
        if (priceSpiritStones > 999_999_999L) {
            return createResult(false, "价格不能超过 999,999,999 灵石");
        }

        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            return createResult(false, "物品不存在，请使用 /物品列表 查看可用物品");
        }

        String fullKey = item.getFullKey();

        long fee = calculateFee(playerId, priceSpiritStones);

        try {
            DatabaseManager.runTransaction(conn -> {
                // 在事务内使用锁检查物品数量
                String checkSql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? " +
                    DatabaseManager.getDialect().getForUpdateClause();
                try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                    ps.setLong(1, playerId);
                    ps.setString(2, fullKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("背包中没有【" + item.getName() + "】");
                        }
                        long currentQuantity = rs.getLong("quantity");
                        if (currentQuantity < quantity) {
                            throw new SQLException("背包中没有足够的【" + item.getName() + "】，当前: " + currentQuantity + "，需要: " + quantity);
                        }
                    }
                }
                
                if (!itemService.removeItem(conn, playerId, fullKey, quantity)) {
                    throw new SQLException("物品扣除失败: " + fullKey);
                }
                createListing(conn, playerId, fullKey, quantity, priceSpiritStones, fee);
                return null;
            });

            Map<String, Object> result = createResult(true,
                    "已将【" + item.getName() + " (" + fullKey + ")】×" + quantity +
                    " 挂入坊市，售价 " + priceSpiritStones + " 灵石（坊市抽成 " + fee + " 灵石，" +
                    LISTING_DURATION_HOURS + " 小时后自动下架）");
            result.put("fee", fee);
            result.put("expiresInHours", LISTING_DURATION_HOURS);
            return result;
        } catch (Exception e) {
            return createResult(false, "上架失败: " + rootMessage(e));
        }
    }

    public Map<String, Object> buyItem(long buyerPlayerId, long listingId) {
        // S6 修复：把 listing 读取移入事务内并加锁，避免事务外快照 + 竞态覆盖
        final TradeListing[] purchased = new TradeListing[1];
        try {
            DatabaseManager.runTransaction(conn -> {
                TradeListing listing = getListingForUpdate(conn, listingId);
                if (listing == null) {
                    throw new SQLException("该挂单不存在或已被购买");
                }
                if (listing.sellerPlayerId == buyerPlayerId) {
                    throw new SQLException("不能购买自己的物品");
                }
                long totalCost = listing.priceSpiritStones;
                long buyerSpiritStones = itemService.getSpiritStoneCount(conn, buyerPlayerId);
                if (buyerSpiritStones < totalCost) {
                    throw new SQLException("灵石不足，需要 " + totalCost + " 灵石，当前仅有 " + buyerSpiritStones + " 灵石");
                }
                int removed = markListingAsSold(conn, listingId);
                if (removed == 0) {
                    throw new SQLException("挂单已失效");
                }
                executePurchase(conn, buyerPlayerId, listing);
                purchased[0] = listing;
                return null;
            });

            TradeListing listing = purchased[0];
            Item item = ItemRegistry.get(listing.itemKey);
            Map<String, Object> result = createResult(true,
                    "在坊市中购得【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity +
                    "，花费 " + listing.priceSpiritStones + " 灵石（坊主抽成 " + listing.fee + " 灵石）");
            result.put("cost", listing.priceSpiritStones);
            result.put("fee", listing.fee);
            return result;
        } catch (Exception e) {
            return createResult(false, "购买失败: " + rootMessage(e));
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
        String sql = "SELECT * FROM trade_listings WHERE id = ? AND status = 'active' "
                + "AND (expires_at IS NULL OR expires_at > ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapListing(rs);
                }
            }
        }
        return null;
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
                    throw new SQLException("该挂单不存在或已被购买");
                }
                if (listing.sellerPlayerId != playerId) {
                    throw new SQLException("这不是你的挂单");
                }
                int removed = markListingAsSold(conn, listingId);
                if (removed == 0) {
                    throw new SQLException("挂单已失效");
                }
                itemService.addItem(conn, playerId, listing.itemKey, listing.quantity);
                cancelled[0] = listing;
                return null;
            });

            TradeListing listing = cancelled[0];
            Item item = ItemRegistry.get(listing.itemKey);
            return createResult(true,
                    "撤回了坊市挂单【" + (item != null ? item.getName() : listing.itemKey) + "】×" + listing.quantity +
                    "（坊市抽成不退还）");
        } catch (Exception e) {
            return createResult(false, "撤回失败: " + rootMessage(e));
        }
    }

    public List<TradeListing> getActiveListings() {
        // 到期挂单不再展示（即使回收任务还没跑到）
        String sql = "SELECT * FROM trade_listings WHERE status = 'active' "
                + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC";
        return queryListings(sql, ps -> ps.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis())));
    }

    public List<TradeListing> getPlayerListings(long playerId) {
        String sql = "SELECT * FROM trade_listings WHERE seller_player_id = ? AND status = 'active' "
                + "AND (expires_at IS NULL OR expires_at > ?)";
        return queryListings(sql, ps -> {
            ps.setLong(1, playerId);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
        });
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
        String selectSql = "SELECT id, created_at FROM trade_listings "
                + "WHERE status = 'active' AND expires_at IS NULL";
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                java.sql.Timestamp created = rs.getTimestamp("created_at");
                rows.add(new Object[]{rs.getLong("id"), created});
            }
        } catch (SQLException e) {
            LOG.warn("回填历史挂单到期时间失败: " + e.getMessage());
            return false;
        }
        if (rows.isEmpty()) return true;

        String updateSql = "UPDATE trade_listings SET expires_at = ? WHERE id = ? AND expires_at IS NULL";
        long durationMs = LISTING_DURATION_HOURS * 3600_000L;
        int filled = 0;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            for (Object[] row : rows) {
                long id = (Long) row[0];
                java.sql.Timestamp created = (java.sql.Timestamp) row[1];
                // created_at 缺失时以当前时间为基准，避免这些挂单永远不过期
                long base = created != null ? created.getTime() : System.currentTimeMillis();
                ps.setTimestamp(1, new java.sql.Timestamp(base + durationMs));
                ps.setLong(2, id);
                ps.addBatch();
                filled++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.warn("回填历史挂单到期时间失败: " + e.getMessage());
            return false;
        }
        LOG.info("已为 " + filled + " 个历史坊市挂单补齐到期时间（" + LISTING_DURATION_HOURS + " 小时）");
        return true;
    }

    /** 回收所有已到期挂单，返回成功回收数量。 */
    private int expireDueListings() {
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        String selectSql = "SELECT id, seller_player_id, item_key, quantity FROM trade_listings "
                + "WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at <= ?";
        List<Object[]> due = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setTimestamp(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new Object[]{
                            rs.getLong("id"),
                            rs.getLong("seller_player_id"),
                            rs.getString("item_key"),
                            rs.getInt("quantity")
                    });
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询到期挂单失败: " + e.getMessage());
            return 0;
        }

        int expired = 0;
        for (Object[] row : due) {
            final long listingId = (Long) row[0];
            final long sellerId = (Long) row[1];
            final String itemKey = (String) row[2];
            final int qty = (Integer) row[3];
            try {
                Boolean claimed = DatabaseManager.runTransaction(conn -> {
                    // 先抢占：只有把 active 改成 expired 成功的一方负责退回物品。
                    // 否则会与买家并发购买竞争，导致物品被退回两次（复制物品）。
                    try (PreparedStatement claim = conn.prepareStatement(
                            "UPDATE trade_listings SET status = 'expired' WHERE id = ? AND status = 'active'")) {
                        claim.setLong(1, listingId);
                        if (claim.executeUpdate() == 0) return Boolean.FALSE;
                    }
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
            out.put("message", "物品不存在，请使用 /物品列表 查看可用物品");
            return out;
        }
        String fullKey = item.getFullKey();
        int window = days <= 0 ? DEFAULT_QUOTE_DAYS : Math.min(days, MAX_QUOTE_DAYS);
        java.sql.Timestamp since =
                new java.sql.Timestamp(System.currentTimeMillis() - window * DAY_MS);

        long count = 0, min = 0, max = 0, avg = 0, totalQty = 0;
        String statSql = "SELECT COUNT(*) AS cnt, MIN(unit_price) AS minp, MAX(unit_price) AS maxp, "
                + "AVG(unit_price) AS avgp, SUM(quantity) AS qty "
                + "FROM trade_price_history WHERE item_key = ? AND sold_at >= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(statSql)) {
            ps.setString(1, fullKey);
            ps.setTimestamp(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getLong("cnt");
                    if (count > 0) {
                        min = rs.getLong("minp");
                        max = rs.getLong("maxp");
                        avg = Math.round(rs.getDouble("avgp"));
                        totalQty = rs.getLong("qty");
                    }
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询价格历史失败: " + e.getMessage());
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
            out.put("message", item.getName() + " 近 " + window + " 天成交 " + count + " 笔，"
                    + "均价 " + avg + " 灵石/个（最低 " + min + "，最高 " + max + "）");
        } else {
            out.put("message", item.getName() + " 近 " + window + " 天没有成交记录，"
                    + "可参考当前在售最低价定价");
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
        String sql = "SELECT MIN(CAST(price_spirit_stones AS DOUBLE) / quantity) AS lowest "
                + "FROM trade_listings WHERE item_key = ? AND status = 'active' AND quantity > 0 "
                + "AND (expires_at IS NULL OR expires_at > ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullKey);
            ps.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.round(rs.getDouble("lowest"));
                }
            }
        } catch (SQLException e) {
            LOG.warn("查询在售最低价失败: " + e.getMessage());
        }
        return 0;
    }

    /** 清理过期的价格历史记录，返回删除行数。 */
    public int cleanupPriceHistory() {
        String sql = "DELETE FROM trade_price_history WHERE sold_at < ?";
        java.sql.Timestamp cutoff =
                new java.sql.Timestamp(System.currentTimeMillis() - PRICE_HISTORY_KEEP_DAYS * DAY_MS);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("清理价格历史失败: " + e.getMessage());
            return 0;
        }
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
        String sql = "INSERT INTO trade_listings "
                + "(seller_player_id, item_key, quantity, price_spirit_stones, fee, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.setInt(3, quantity);
            ps.setLong(4, priceSpiritStones);
            ps.setLong(5, fee);
            ps.setTimestamp(6, new java.sql.Timestamp(
                    System.currentTimeMillis() + LISTING_DURATION_HOURS * 3600_000L));
            ps.executeUpdate();
        }
    }

    /**
     * 记录一笔成交到价格历史（供行情查询）。
     * <p>
     * 单价 = 总价 ÷ 数量：同一物品挂单数量不同，只有单价可比。
     * 记录失败不影响成交本身（成交已完成，历史只是辅助信息），因此单独 try/catch。
     */
    private void recordPriceHistory(Connection conn, String itemKey, int quantity, long totalPrice) {
        if (quantity <= 0 || totalPrice <= 0) return;
        long unitPrice = Math.max(1, totalPrice / quantity);
        String sql = "INSERT INTO trade_price_history (item_key, unit_price, quantity, total_price) "
                + "VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setLong(2, unitPrice);
            ps.setInt(3, quantity);
            ps.setLong(4, totalPrice);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("写入坊市价格历史失败（不影响成交）: " + e.getMessage());
        }
    }

    private void executePurchase(Connection conn, long buyerPlayerId, TradeListing listing) throws SQLException {
        // 坊市购买：跨等级扣除灵石（自动用玩家持有的任意等级灵石支付并找零）
        if (!itemService.removeSpiritStones(conn, buyerPlayerId, listing.priceSpiritStones)) {
            throw new SQLException("灵石扣除失败");
        }
        itemService.addItem(conn, buyerPlayerId, listing.itemKey, listing.quantity);

        long sellerReceive = listing.priceSpiritStones - listing.fee;
        if (!itemService.addSpiritStones(conn, listing.sellerPlayerId, sellerReceive)) {
            throw new SQLException("发放灵石失败");
        }
        // 成交后记录价格历史，供玩家查询行情（失败不影响成交本身）
        recordPriceHistory(conn, listing.itemKey, listing.quantity, listing.priceSpiritStones);
    }

    private int markListingAsSold(Connection conn, long listingId) throws SQLException {
        String sql = "UPDATE trade_listings SET status = 'sold' WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return ps.executeUpdate();
        }
    }

    private List<TradeListing> queryListings(String sql, SqlParameterSetter setter) {
        List<TradeListing> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (setter != null) {
                setter.set(ps);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapListing(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取挂单列表失败", e);
        }
        return result;
    }

    private TradeListing mapListing(ResultSet rs) throws SQLException {
        TradeListing l = new TradeListing();
        l.id = rs.getLong("id");
        l.sellerPlayerId = rs.getLong("seller_player_id");
        l.itemKey = rs.getString("item_key");
        l.quantity = rs.getInt("quantity");
        l.priceSpiritStones = rs.getLong("price_spirit_stones");
        l.fee = rs.getLong("fee");
        l.createdAt = rs.getString("created_at");
        java.sql.Timestamp exp = rs.getTimestamp("expires_at");
        l.expiresAt = exp != null ? exp.getTime() : 0L; // 0 = 永不过期（历史挂单）
        return l;
    }

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    @FunctionalInterface
    private interface SqlParameterSetter {
        void set(PreparedStatement ps) throws SQLException;
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