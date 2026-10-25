package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经济域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code EconomyService} 原来 7 处
 * {@code DatabaseManager.getConnection()}，全是查询；另外还有 6 处写在
 * {@code runTransaction} lambda 里的 SQL。经济系统的 SQL 语义一旦变化，
 * 后果是刷灵石、重复领奖、拍卖重复结算 —— 全都直接破坏游戏平衡。</p>
 *
 * <p>这个域的抽取风险集中在三处：<b>灵石面值折算比例</b>（写死在一条
 * {@code CASE WHEN} 里）、<b>签到的幂等判定</b>（靠"读日期 + 字符串相等"，
 * 不靠 SQL 的 WHERE）、<b>拍卖结算的抢占条件</b>
 * （{@code WHERE ... status = 'active'} 命中 0 行即放弃）。</p>
 */
class EconomyDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return EconomyDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/EconomyService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/EconomyDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 利息估算：依赖「现在」，必须留在服务层
                "Math.pow(1 + rate, elapsedDays)",
                "estimatedInterest",
                // 竞拍剩余时间文案
                "private String estimateRemaining(",
                "已结束",
                // 拍卖行结算的编排：取一批 → 逐条事务
                "settleExpiredAuctions()",
                // 手续费与系统销毁
                "Math.max(1, (long)(currentBid * feeRate))",
                // 国际化
                "I18n.t(",
                // 调其它服务
                "itemService.addSpiritStones(",
                "itemService.addItem(",
                // 提纯的境界需求与成功率（caves 表的灵气扣除已进 DAO）
                "PURIFY_BASE_RATE",
                "Math.min(0.98, baseRate + spiritBonus)");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "I18n", "PlayerService", "ItemService", "GameConfigLoader",
                "EconomyService", "ServiceRegistry",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("查询类方法成对，自取连接版整体委托")
    void queriesHaveBothOverloadsAndDelegate() throws IOException {
        String code = stripComments(read(daoPath()));
        for (String intent : new String[]{
                "findActiveBankDeposits", "findBankDepositRow",
                "sumSpiritStones", "sumGold", "countPlayers", "countSoldTradeListings",
                "findActiveAuctionListings", "findPlayerAuctionListings",
                "findActiveAuctionsForSettlement", "findSignIn"}) {
            // 3 次 = 两个签名 + 自取连接版里那一行委托调用
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // 自取连接版只做「开连接 + 委托 + 包异常」，不碰 SQL
        for (String sig : new String[]{
                "public List<Map<String, Object>> findActiveBankDeposits(long playerId)",
                "public Map<String, Object> findBankDepositRow(long depositId)",
                "public long sumSpiritStones()",
                "public long sumGold()",
                "public int countPlayers()",
                "public long countSoldTradeListings()",
                "public List<Map<String, Object>> findActiveAuctionListings()",
                "public List<Map<String, Object>> findPlayerAuctionListings(long playerId)",
                "public List<AuctionSettlementRow> findActiveAuctionsForSettlement()",
                "public SignInRecord findSignIn(long playerId)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"), sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("prepareStatement("),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("签到查询读失败返回空记录而不是抛异常")
    void signInReadFailureIsSwallowed() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public SignInRecord findSignIn(long playerId)");
        assertTrue(body.contains("catch (SQLException ignored)"),
                "签到是低频非关键路径，读失败不该让玩家连日常面板都打不开");
        assertTrue(body.contains("return new SignInRecord();"),
                "读不到行 / 读失败都返回空记录：lastSignIn = null 即「从没签到过」");
    }

    @Test
    @DisplayName("灵石面值折算比例逐字保留")
    void spiritStoneFaceValueRatio() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public long sumSpiritStones(Connection conn)");
        assertTrue(body.contains("WHEN item_key = 'mtxgdn:spirit_stone_low' THEN quantity"),
                "下品 = 1");
        assertTrue(body.contains("WHEN item_key = 'mtxgdn:spirit_stone_mid' THEN quantity * 1000"),
                "中品 = ×1000");
        assertTrue(body.contains("WHEN item_key = 'mtxgdn:spirit_stone_high' THEN quantity * 1000000"),
                "上品 = ×10^6");
        assertTrue(body.contains("WHEN item_key = 'mtxgdn:spirit_stone_supreme' THEN quantity * 1000000000"),
                "极品 = ×10^9");
        assertTrue(body.contains("WHEN item_key = 'mtxgdn:spirit_stone' THEN quantity"),
                "无档位的 spirit_stone 按 1 计");
        assertTrue(body.contains("ELSE 0 END), 0)"),
                "ELSE 0 让未知档位不计入总量（而不是让整个 SUM 变 NULL），"
                        + "外层 COALESCE 再兜底空表");
        assertTrue(body.contains("FROM players_items WHERE item_key LIKE 'mtxgdn:spirit_stone%'"),
                "扫的是 players_items 而不是 players：灵石是物品");
    }

    @Test
    @DisplayName("灵石、金币、玩家数分三条语句，没有合并成 JOIN")
    void statsStayThreeStatements() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("JOIN"),
                "合并成一条 JOIN 会因为「没有物品的玩家」被丢掉而少算人数");
        assertEquals(1, countOccurrences(code, "SELECT COALESCE(SUM(gold), 0) FROM players"),
                "金币总量扫 players 表");
        assertEquals(1, countOccurrences(code, "SELECT COUNT(*) FROM players"), "玩家数");
    }

    @Test
    @DisplayName("last24hTradeVolume 没有时间条件：如实命名")
    void soldTradeCountHasNoTimeFilter() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public long countSoldTradeListings(Connection conn)");
        assertTrue(body.contains("SELECT COUNT(*) FROM trade_listings WHERE status = 'sold'"),
                "原实现就是全量统计，没有时间条件。改成 24h 是改口径，会让面板数字一夜变小");
        assertFalse(body.contains("last24h"), "方法名如实反映它数的是全部成交单");
    }

    @Test
    @DisplayName("灵庄只列 active，按创建时间倒序")
    void bankDepositsFilterActiveAndSortDesc() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public List<Map<String, Object>> findActiveBankDeposits(Connection conn, long playerId)");
        assertTrue(body.contains("WHERE player_id = ? AND status = 'active'"),
                "已取款的不出现在列表里，但记录仍在表上 —— 那是审计线索");
        assertTrue(body.contains("ORDER BY created_at DESC"), "最近存的一笔在最前面");
    }

    @Test
    @DisplayName("灵庄按 id 查不带 player_id：归属校验在服务层")
    void bankDepositByIdHasNoPlayerFilter() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public Map<String, Object> findBankDepositRow(Connection conn, long depositId)");
        assertTrue(body.contains("SELECT * FROM player_bank WHERE id = ?"),
                "刻意不带 player_id：加了会让「跨玩家取款」的校验位置悄悄从服务层挪到 SQL 里");
        assertFalse(body.contains("player_id"), "同上");
    }

    @Test
    @DisplayName("个人拍卖带 LIMIT 20，全部竞拍不带")
    void auctionLimitAsymmetry() throws IOException {
        String code = stripComments(read(daoPath()));
        String all = bodyOf(code, "public List<Map<String, Object>> findActiveAuctionListings(Connection conn)");
        assertFalse(all.contains("LIMIT"), "市场总览要的是全量");
        assertTrue(all.contains("WHERE status = 'active'"), "只看在拍");
        assertTrue(all.contains("ORDER BY created_at DESC"), "新挂单在前");

        String mine = bodyOf(code,
                "public List<Map<String, Object>> findPlayerAuctionListings(Connection conn, long playerId)");
        assertTrue(mine.contains("WHERE seller_player_id = ?"), "按卖家过滤");
        assertTrue(mine.contains("ORDER BY created_at DESC LIMIT 20"),
                "玩家自己的挂单可能很多，限制 20 条");
    }

    @Test
    @DisplayName("到期结算的 SELECT 不带时间条件，「现在」在服务层只取一次")
    void settlementSelectHasNoTimeFilter() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public List<AuctionSettlementRow> findActiveAuctionsForSettlement(Connection conn)");
        assertTrue(body.contains("SELECT * FROM auction_listings WHERE status = 'active'"),
                "只取在拍的");
        assertFalse(body.contains("end_time <"), "时间判断在服务层做，一批记录共用一个「现在」");
        assertFalse(body.contains("end_time >"), "同上");
    }

    @Test
    @DisplayName("结算行用 Timestamp 而不是「ts:」字符串")
    void settlementRowKeepsTimestamp() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public List<AuctionSettlementRow> findActiveAuctionsForSettlement(Connection conn)");
        assertTrue(body.contains("row.endTime = rs.getTimestamp(\"end_time\")"),
                "结算要靠 getTime() 比较，通用行映射会把 TIMESTAMP 压成 \"ts:<毫秒>\" 字符串");
        assertTrue(body.contains("rs.getObject(\"current_bidder_id\") != null"),
                "「无人出价」与「出价 0」必须能区分（后者会走成交分支）");
        assertFalse(body.contains("rowToMap(rs)"), "这个查询不能走通用行映射");
    }

    @Test
    @DisplayName("拍卖结算与取款都靠 status = 'active' 抢占，返回 boolean")
    void claimsAreConditionalUpdates() throws IOException {
        String code = stripComments(read(daoPath()));
        String ended = bodyOf(code,
                "public boolean markAuctionListingEnded(Connection conn, long listingId)");
        assertTrue(ended.contains("UPDATE auction_listings SET status = 'ended'"),
                "命中 0 行说明另一个请求（定时任务或另一次惰性结算）已经结过这一条");
        assertTrue(ended.contains("WHERE id = ? AND status = 'active'"),
                "抢占条件：只有一个请求能改成功");
        assertTrue(ended.contains("return ps.executeUpdate() > 0"),
                "返回 false 让调用方整轮放弃，否则会发两次灵石/物品");

        String withdrawn = bodyOf(code,
                "public boolean markBankDepositWithdrawn(Connection conn, long depositId, long interest)");
        assertTrue(withdrawn.contains("UPDATE player_bank SET status = 'withdrawn'"),
                "取款要把状态改掉");
        assertTrue(withdrawn.contains("interest_earned = ?"), "并记下实发利息");
        assertTrue(withdrawn.contains("WHERE id = ? AND status = 'active'"),
                "并发双取时只有一个能命中");
        assertTrue(withdrawn.contains("return ps.executeUpdate() > 0"),
                "命中 0 行即已被取过，整个事务要失败");
    }

    @Test
    @DisplayName("出价是两条语句一个原子：UPDATE 不命中则整体失败")
    void bidIsTwoStatementsOneAtomic() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public boolean insertBidAndUpdateListing(Connection conn, long listingId, long bidderPlayerId,");
        assertTrue(body.contains("INSERT INTO auction_bids (listing_id, bidder_player_id, amount) VALUES (?, ?, ?)"),
                "先记出价：三个占位符，一处都不能少");
        assertTrue(body.contains("UPDATE auction_listings SET current_bid = ?, current_bidder_id = ? "),
                "再把挂单的当前价/出价人抬到这一笔");
        assertTrue(body.contains("+ \"WHERE id = ? AND status = 'active'\""),
                "已结束的挂单更新不到，据此判失败");
        assertTrue(body.contains("return ps.executeUpdate() > 0"),
                "挂单已结束时不静默成功：调用方据此抛「拍卖已结束」，文案不变");
    }

    @Test
    @DisplayName("签到 upsert 的双方言分支与参数顺序逐字保留")
    void signInUpsertDialectsKept() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public void upsertSignIn(Connection conn, long playerId, String today, int streak)");
        assertTrue(body.contains("INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) "),
                "列清单与三个占位符");
        assertTrue(body.contains("ON CONFLICT(player_id) DO UPDATE SET"),
                "SQLite 分支");
        assertTrue(body.contains("ON DUPLICATE KEY UPDATE"), "MySQL 分支");
        assertTrue(body.contains("last_sign_in = excluded.last_sign_in"),
                "SQLite 用 excluded. 前缀");
        assertTrue(body.contains("streak = excluded.streak"), "同上");
        assertTrue(body.contains("last_sign_in = VALUES(last_sign_in)"),
                "MySQL 用 VALUES() 前缀，两者不能互换");
        assertTrue(body.contains("streak = VALUES(streak)"), "同上");
        assertTrue(body.contains("ps.setLong(1, playerId)"), "第 1 个参数是玩家 id");
        assertTrue(body.contains("ps.setString(2, today)"), "第 2 个参数是日期字符串");
        assertTrue(body.contains("ps.setInt(3, streak)"), "第 3 个参数是连签天数");
    }

    @Test
    @DisplayName("行锁复查的方言分支：MySQL 加 FOR UPDATE")
    void signInRowLockHasDialectBranch() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public String findLastSignInForUpdate(Connection conn, long playerId)");
        assertTrue(body.contains("SELECT last_sign_in FROM player_economy WHERE player_id = ?"),
                "读的是签到日期，服务层与 LocalDate.now().toString() 逐字比较");
        assertTrue(body.contains("(DatabaseManager.isSqlite() ? \"\" : \" FOR UPDATE\")"),
                "SQLite 不识别 FOR UPDATE，靠事务内 UPDATE 的隐式写锁");
        assertTrue(body.contains("return rs.next() ? rs.getString(\"last_sign_in\") : null"),
                "读不到行返回 null = 从没签到过");
    }

    @Test
    @DisplayName("加行锁读挂单的方言分支")
    void auctionForUpdateHasDialectBranch() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public AuctionListingRow findAuctionListingForUpdate(Connection conn, long listingId)");
        assertTrue(body.contains("WHERE id = ? AND status = 'active'"),
                "已结束的挂单读不出来，调用方判 null 给「拍卖已结束」文案");
        assertTrue(body.contains("DatabaseManager.isSqlite()"), "方言分支");
        assertTrue(body.contains("rs.getObject(\"current_bid\") != null"),
                "「无人出价」与「出价 0」必须能区分");
    }

    @Test
    @DisplayName("灵庄插入保留 RETURN_GENERATED_KEYS 与「读不到返回 0」")
    void bankInsertKeepsGeneratedKeys() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public long insertBankDeposit(Connection conn,");
        assertTrue(body.contains("java.sql.Statement.RETURN_GENERATED_KEYS"),
                "存款 id 要回给玩家看（存入成功的文案里带单号）");
        assertTrue(body.contains("if (rs.next()) return rs.getLong(1);"),
                "取到自增 id");
        assertTrue(body.contains("return 0L;"),
                "读不到返回 0：调用方把 0 当失败处理，不能改成抛异常");
        assertTrue(body.contains("INSERT INTO player_bank (player_id, deposit_type, principal, rate, "),
                "列清单逐字保留（deposited_at / matures_at 是两个长整型毫秒）");
        assertTrue(body.contains("+ \"deposited_at, matures_at) VALUES (?, ?, ?, ?, ?, ?)\""),
                "六个占位符对应六个参数");
    }

    @Test
    @DisplayName("拍卖插入的 fee_rate 是字面量 0.07，不是参数")
    void auctionInsertFeeRateIsLiteral() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void insertAuctionListing(Connection conn,");
        assertTrue(body.contains("start_price, fee_rate, end_time) VALUES (?, ?, ?, ?, 0.07, ?)"),
                "0.07 写死在 SQL 里：抽成参数会让「每单手续费可变」看起来像一个已支持的能力");
        assertTrue(body.contains("ps.setTimestamp(5, endTime)"),
                "endTime 由调用方算好传入（= now + hours * 3600000，hours 已夹到 [1,72]）");
    }

    @Test
    @DisplayName("DAO 不自己取时间戳")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("System.currentTimeMillis()"),
                "「现在」归调用方：DAO 自己取会让一次重试里的时间悄悄变化");
        assertFalse(code.contains("LocalDate.now()"), "同上");
        assertFalse(code.contains("Instant.now()"), "同上");
        // CURRENT_DATE 是数据库侧取值，原实现如此（两边言一致），不算 DAO 自己取
        assertTrue(code.contains("last_daily_reset = CURRENT_DATE") == false,
                "EconomyDAO 不该碰 player_daily 的跨日复位");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询灵庄失败\", e)"),
                "灵庄列表的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询存款失败\", e)"),
                "单笔存款的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询竞拍列表失败\", e)"),
                "竞拍列表的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询个人拍卖失败\", e)"),
                "个人拍卖的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询待结算拍卖失败\", e)"),
                "结算扫描的文案");
    }
}
