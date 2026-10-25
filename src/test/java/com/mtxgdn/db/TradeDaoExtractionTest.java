package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坊市（玩家对玩家交易）域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code TradeService} 原来 7 处
 * {@code DatabaseManager.getConnection()}，另有 6 处写在
 * {@code runTransaction} lambda 里的 SQL。</p>
 *
 * <p>这个域的坑几乎全在<b>抢占条件</b>上：坊市的每一笔写都要防止并发下
 * 同一件物品被处理两次（复制物品 / 重复退款 / 重复扣款）。原实现清一色
 * {@code WHERE id = ? AND status = 'active'}，靠 {@code executeUpdate()}
 * 的命中行数判成败，没有一处用 SELECT-then-UPDATE。这些 WHERE 一个字符
 * 都不能动。</p>
 */
class TradeDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return TradeDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/TradeService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/TradeDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 手续费：灵根减半
                "calculateFee(",
                "TRADE_FEE_HALF",
                // 并发安全路径的编排：读 → 抢占 → 扣款/发货
                "markListingAsSold(conn, listingId)",
                "executePurchase(conn, buyerPlayerId, listing)",
                // 国际化和错误文案
                "I18n.t(\"trade.buy.listing_gone\")",
                "I18n.t(\"trade.cancel.not_owner\")",
                // 行情窗口的夹取
                "DEFAULT_QUOTE_DAYS",
                "MAX_QUOTE_DAYS",
                // 回填的编排：读一批 → 算时间 → 批更新
                "backfillLegacyExpiry()",
                "LISTING_DURATION_HOURS * 3600_000L");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "I18n", "PlayerService", "ItemService", "ItemRegistry",
                "SpiritualRoot", "TradeService.class",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("查询类方法都有入口，自取连接版整体委托")
    void queriesHaveBothOverloadsAndDelegate() throws IOException {
        String code = stripComments(read(daoPath()));
        // backfillLegacyExpiry 是 3 次：两个签名 + 自取连接版里那一行委托
        assertEquals(3, countOccurrences(code, "backfillLegacyExpiry("),
                "自取连接版必须整体委托，否则 SQL 会被复制两份");
        // 其余查询只有自取连接版（坊市没有事务内查询）
        for (String intent : new String[]{
                "findLegacyActiveListings", "findDueListings", "getPriceHistoryStats",
                "lowestActiveUnitPrice", "cleanupPriceHistory", "queryListings",
                "findActiveListings", "findPlayerListings"}) {
            assertTrue(countOccurrences(code, intent + "(") >= 1,
                    intent + " 应该存在");
        }
    }

    @Test
    @DisplayName("回填刻意分两条连接，且 UPDATE 带 expires_at IS NULL")
    void backfillKeepsTwoConnectionsAndIdempotentWhere() throws IOException {
        String code = stripComments(read(daoPath()));
        String select = bodyOf(code, "public List<LegacyExpiryRow> findLegacyActiveListings()");
        assertTrue(select.contains("SELECT id, created_at FROM trade_listings "),
                "只取两列：回填只需要 id 与 created_at");
        assertTrue(select.contains("WHERE status = 'active' AND expires_at IS NULL"),
                "只回填历史挂单（expires_at 为 NULL）");
        assertTrue(select.contains("row.createdAt = rs.getTimestamp(\"created_at\")"),
                "created_at 可能是 NULL，原样交给调用方判断");

        String update = bodyOf(code,
                "public int backfillLegacyExpiry(Connection conn, List<Timestamp> expiresAtList, List<Long> ids)");
        assertTrue(update.contains("UPDATE trade_listings SET expires_at = ? WHERE id = ? AND expires_at IS NULL"),
                "UPDATE 的 WHERE 与 SELECT 的条件相同，所以整个操作幂等："
                        + "两个线程同时跑，UPDATE 都命中 0 行，不会覆盖已回填的值");
        assertTrue(update.contains("ps.addBatch()"), "批更新");
        assertTrue(update.contains("int[] batch = ps.executeBatch()"),
                "批执行：返回值从 executeBatch 的数组里累加，反映实际更新行数");
        assertTrue(update.contains("if (n > 0) filled += n;"),
                "SQLite 的 executeBatch 对未命中行返回 0，累加时只算正的");
    }

    @Test
    @DisplayName("回填的时间在 Java 里算，不用 SQL 日期函数")
    void backfillComputesTimeInJava() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("DATE_ADD"), "避开 SQLite/MySQL 方言差异");
        assertFalse(code.contains("datetime("), "同上");
        assertFalse(code.contains("INTERVAL"), "同上");
        // created_at 缺失时由调用方改用当前时间
        String body = bodyOf(code, "public List<LegacyExpiryRow> findLegacyActiveListings()");
        assertFalse(body.contains("COALESCE(created_at"),
                "created_at 为 NULL 时以当前时间为基准，这是调用方的决定");
    }

    @Test
    @DisplayName("到期回收的 SELECT 带 expires_at IS NOT NULL")
    void dueListingsExcludeLegacyNullExpiry() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<DueListingRow> findDueListings(Connection conn, Timestamp now)");
        assertTrue(body.contains("SELECT id, seller_player_id, item_key, quantity FROM trade_listings "),
                "只取退回物品需要的四列");
        assertTrue(body.contains("WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at <= ?"),
                "expires_at IS NOT NULL 不能少：历史挂单永不过期，少了会把老挂单一并回收");
        assertTrue(body.contains("ps.setTimestamp(1, now)"),
                "「现在」由调用方给，一批记录共用一个基准");
    }

    @Test
    @DisplayName("回收与购买的抢占是同一形态：WHERE status = 'active'")
    void claimConditionsAreIdenticalShape() throws IOException {
        String code = stripComments(read(daoPath()));
        String expired = bodyOf(code, "public boolean markListingExpired(Connection conn, long listingId)");
        assertTrue(expired.contains("UPDATE trade_listings SET status = 'expired' "
                        + "WHERE id = ? AND status = 'active'"),
                "命中 0 行说明买家已经并发买走，这一方不该再退一次物品");
        assertTrue(expired.contains("return ps.executeUpdate() > 0"),
                "返回 boolean：false 让调用方整轮放弃");

        String sold = bodyOf(code, "public int markListingSold(Connection conn, long listingId)");
        assertTrue(sold.contains("UPDATE trade_listings SET status = 'sold' "
                        + "WHERE id = ? AND status = 'active'"),
                "同一条 UPDATE 的不同目标状态");
        assertTrue(sold.contains("return ps.executeUpdate()"),
                "返回行数而不是 boolean：调用方要区分「没抢到」与其它情况");
    }

    @Test
    @DisplayName("行情统计用别名，且 count > 0 之后才读 min/max/avg/qty")
    void priceHistoryUsesAliasesAndGuardsNulls() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public PriceHistoryStats getPriceHistoryStats(Connection conn, String itemKey, Timestamp since)");
        assertTrue(body.contains("SELECT COUNT(*) AS cnt, MIN(unit_price) AS minp, MAX(unit_price) AS maxp, "),
                "五个统计各起别名：加一列统计不会让读取错位");
        assertTrue(body.contains("+ \"AVG(unit_price) AS avgp, SUM(quantity) AS qty \""),
                "AVG 与 SUM 各起别名");
        assertTrue(body.contains("+ \"FROM trade_price_history WHERE item_key = ? AND sold_at >= ?\""),
                "按物品与时间窗口过滤");
        assertTrue(body.contains("s.count = rs.getLong(\"cnt\")"), "按别名读");
        assertTrue(body.contains("if (s.count > 0) {"),
                "没有成交记录时 min/max/avg/qty 全是 NULL：原实现在 count > 0 之后才读");
        assertTrue(body.contains("s.avgUnitPriceRaw = rs.getDouble(\"avgp\")"),
                "AVG 原样交出，四舍五入留给调用方：SQLite 返 DOUBLE、"
                        + "MySQL 可能返 DECIMAL，放 Java 里两边言一致");
    }

    @Test
    @DisplayName("在售最低价带 CAST 与除零保护")
    void lowestActiveUnitPriceCastsAndGuards() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public long lowestActiveUnitPrice(Connection conn, String itemKey, Timestamp now)");
        assertTrue(body.contains("SELECT MIN(CAST(price_spirit_stones AS DOUBLE) / quantity) AS lowest "),
                "CAST 不能省：整数相除会先把单价截断成 0");
        assertTrue(body.contains("WHERE item_key = ? AND status = 'active' AND quantity > 0 "),
                "quantity > 0 防除零");
        assertTrue(body.contains("AND (expires_at IS NULL OR expires_at > ?)"),
                "历史挂单仍参与比价（它们仍然有效），"
                        + "而「已过期但还没被回收」的不参与");
        assertTrue(body.contains("return Math.round(rs.getDouble(\"lowest\"))"),
                "四舍五入在 Java 里做");
    }

    @Test
    @DisplayName("清理价格历史失败返回 0 而不是抛异常")
    void cleanupSwallowsFailure() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int cleanupPriceHistory(Connection conn, Timestamp cutoff)");
        assertTrue(body.contains("DELETE FROM trade_price_history WHERE sold_at < ?"),
                "按售出时间清理");
        assertTrue(body.contains("return ps.executeUpdate()"), "返回删除行数");
        // catch 在自取连接版里：conn 版直接抛给调用方的事务
        String self = bodyOf(code, "public int cleanupPriceHistory(Timestamp cutoff)");
        assertTrue(self.contains("catch (SQLException e) {"),
                "失败返回 0：清理是维护操作，调用方（管理台）直接展示「清理了 N 条」");
        assertTrue(self.contains("return 0;"), "同上");
    }

    @Test
    @DisplayName("通用的 queryListings 刻意是 private")
    void genericQueryIsPrivate() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("private List<TradeService.TradeListing> queryListings(Connection conn, String sql,"),
                "做成 public 就得配 Connection 首参重载，而「调用方自己拼一条 "
                        + "trade_listings 的 SQL」正是抽取想消除的东西");
        assertTrue(code.contains("private List<TradeService.TradeListing> queryListings("),
                "坊市只有两条查询，都走具名 public 入口");
    }

    @Test
    @DisplayName("两条列表 SQL 都带 expires_at 过滤")
    void listingSqlConstantsFilterExpiry() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("\"AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC\""),
                "全部在售：到期挂单不再展示，即使回收任务还没跑到");
        assertTrue(code.contains("SELECT * FROM trade_listings WHERE seller_player_id = ? "
                        + "AND status = 'active' "),
                "个人在售：按卖家过滤");
        assertTrue(code.contains("\"AND (expires_at IS NULL OR expires_at > ?)\""),
                "个人在售同样带 expires_at 过滤");
    }

    @Test
    @DisplayName("事务内读挂单：加锁 + 未到期 + 读不到返回 null")
    void activeListingForUpdateLocksAndFilters() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public TradeService.TradeListing findActiveListingForUpdate(Connection conn, long listingId,");
        assertTrue(body.contains("SELECT * FROM trade_listings WHERE id = ? AND status = 'active' "),
                "按 id 与在售状态");
        assertTrue(body.contains("AND (expires_at IS NULL OR expires_at > ?)"),
                "S6 修复：只靠定时回收不够（最长 60s 才跑一次），少了这个条件，"
                        + "到期后仍可被购买 —— 买家买到僵尸挂单，而卖家的物品已被回收退回");
        assertTrue(body.contains("DatabaseManager.getDialect().getForUpdateClause()"),
                "行锁从句由 dialect 给，DAO 不自己拼方言");
        assertTrue(body.contains("ps.setTimestamp(2, now)"), "「现在」由调用方给");
        assertTrue(body.contains("return rs.next() ? mapListing(rs) : null"),
                "读不到返回 null，调用方给「挂单不存在或已售出」的文案");
    }

    @Test
    @DisplayName("玩家物品数量：读不到返回 -1 而不是 0")
    void playerItemQuantityUsesMinusOne() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public long findPlayerItemQuantityForUpdate(Connection conn, long playerId, String itemKey)");
        assertTrue(body.contains("SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? "),
                "只读数量一列");
        assertTrue(body.contains("DatabaseManager.getDialect().getForUpdateClause()"),
                "事务内行锁");
        assertTrue(body.contains("return rs.next() ? rs.getLong(\"quantity\") : -1L"),
                "读不到行返回 -1：调用方要区分「没有这个物品」与「有 0 个」");
    }

    @Test
    @DisplayName("挂单插入的列清单与 expiresAt 参数")
    void insertListingShape() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void insertListing(Connection conn,");
        assertTrue(body.contains("INSERT INTO trade_listings "), "插入挂单表");
        assertTrue(body.contains("(seller_player_id, item_key, quantity, price_spirit_stones, fee, expires_at) "),
                "六列");
        assertTrue(body.contains("+ \"VALUES (?, ?, ?, ?, ?, ?)\""), "六个占位符");
        assertTrue(body.contains("ps.setTimestamp(6, expiresAt)"),
                "到期时间由调用方算好传入（now + LISTING_DURATION_HOURS * 3600_000L）");
        assertTrue(body.contains("ps.setLong(5, fee)"),
                "fee 是调用方算好的实收手续费（可能因灵根减半），DAO 不自己算");
    }

    @Test
    @DisplayName("写入价格失败不影响成交")
    void priceHistoryWriteIsBestEffort() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public void recordPriceHistory(Connection conn, String itemKey, int quantity, long totalPrice)");
        assertFalse(body.contains("throws SQLException"),
                "原实现在这里单独 try/catch：成交已完成，历史只是辅助信息，"
                        + "写失败不该让整笔交易回滚（那会把买家的钱和卖家的货一起退回去）");
        assertTrue(body.contains("if (quantity <= 0 || totalPrice <= 0) return;"),
                "零数量或零金额不记");
        assertTrue(body.contains("long unitPrice = Math.max(1, totalPrice / quantity)"),
                "零元成交也要记 1，否则 MIN(unit_price) 会被 0 污染");
        assertTrue(body.contains("INSERT INTO trade_price_history (item_key, unit_price, quantity, total_price) "),
                "四列");
    }

    @Test
    @DisplayName("expiresAt 的 0 表示永不过期")
    void mappingKeepsZeroMeansNeverExpires() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "private static TradeService.TradeListing mapListing(ResultSet rs)");
        assertTrue(body.contains("l.expiresAt = exp != null ? exp.getTime() : 0L"),
                "getTimestamp 返回 NULL 时写 0，与 remainingMs() 的 <= 0 → -1 对应。"
                        + "不能改成 Long 装箱 —— 那是公开 API");
        assertTrue(body.contains("l.createdAt = rs.getString(\"created_at\")"),
                "createdAt 是字符串不是 Timestamp：原实现如此");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询历史挂单失败\", e)"),
                "回填扫描的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"回填历史挂单到期时间失败\", e)"),
                "回填批更新的文案");
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询到期挂单失败\", e)"),
                "到期扫描的文案：两个重载各一次");
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询价格历史失败\", e)"),
                "行情统计的文案");
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询在售最低价失败\", e)"),
                "最低价的文案");
        assertEquals(3, countOccurrences(code, "throw new RuntimeException(\"获取挂单列表失败\", e)"),
                "挂单列表的文案");
    }

    @Test
    @DisplayName("DAO 不自己取「现在」")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("System.currentTimeMillis()"),
                "「现在」归调用方：DAO 自己取会让一次重试里的时间悄悄变化");
        assertFalse(code.contains("CURRENT_TIMESTAMP"), "同上");
    }
}
