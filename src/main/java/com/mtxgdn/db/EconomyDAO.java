package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 经济系统（灵庄存款、拍卖行、灵石总量统计、签到记录）在 {@code player_bank} /
 * {@code auction_listings} / {@code auction_bids} / {@code trade_listings} /
 * {@code players_items} / {@code players} / {@code player_economy} 七张表上的
 * 读写入口。
 *
 * <p>从 {@code EconomyService} 抽出来的 DAO。成对的情况分两种：</p>
 *
 * <ul>
 *   <li><b>查询类两个重载都给</b>：{@code EconomyService} 原来的 7 处
 *       {@code getConnection()} 全是查询，没有一处在事务块内。这些查询还被
 *       结算流程与面板共用，将来任何一处被放进事务（比如"取款扣存款 + 发利息"
 *       合成一个事务）时，事务里只能传 {@code conn} —— 自己开连接在 SQLite 下
 *       会撞 {@code SQLITE_BUSY_SNAPSHOT}。先给足重载比等第一次死锁再补要便宜。</li>
 *   <li><b>写操作只有 Connection 版</b>：{@link #insertBankDeposit}、
 *       {@link #insertAuctionListing}、{@link #insertBidAndUpdateListing}、
 *       {@link #markBankDepositWithdrawn}、{@link #markAuctionListingEnded}、
 *       {@link #upsertSignIn}、{@link #findLastSignInForUpdate}、
 *       {@link #findAuctionListingForUpdate} 这八个原本就在
 *       {@code runTransaction} 里，必须和扣款/发奖共用同一条连接。
 *       给自取连接版等于给"事务外单独落一笔库"开门。</li>
 * </ul>
 *
 * <p><b>DAO 只交原始行，不做任何业务计算</b>。原实现里那些计算
 * （活期/定期的利息估算、剩余天数、竞拍剩余时间的"小时/分钟"文案、
 * 手续费）全部留在服务层 —— 它们的输入是"现在"这个时刻，DAO 不该知道。
 * 这也是为什么这里用 {@code Map<String, Object>} 而不是强类型实体：
 * 每个调用方要的列子集与键名都不一样（灵庄要算利息、拍卖列表要拼物品名、
 * 统计面板只要一个标量），提前定死实体只会逼着调用方再转一次。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>灵庄只列 {@code status = 'active'} 的存款，按 {@code created_at} 倒序</b>：
 *       已取款的不出现在列表里，但记录仍在表上（{@code status = 'withdrawn'}）——
 *       这是审计线索，不能顺手删行。倒序让"最近存的一笔"在最前面。</li>
 *   <li><b>灵庄的两条查询都只按 id 过滤，不带 {@code player_id}</b>：
 *       {@link #findBankDepositRow} 是私有取款流程用的，调用方已经先校验过
 *       归属。原实现如此，DAO 不擅自加条件 —— 加了会让"跨玩家取款"的校验
 *       位置悄悄从服务层挪到 SQL 里，出错时的文案也会变。</li>
 *   <li><b>灵石总量是一条 {@code COALESCE(SUM(CASE WHEN ...))}</b>：
 *       五个档位的灵石按面值折算（{@code low}=1、{@code mid}=×1000、
 *       {@code high}=×10^6、{@code supreme}=×10^9，其余{item_key} 为 0），
 *       再 {@code COALESCE(..., 0)} 兜底空表。折算比例是经济数值的核心假设，
 *       写死在 SQL 里而不是服务层 —— 逐字保留，包括 {@code ELSE 0} 分支
 *       （未知档位的灵石不计入总量，而不是让整个 SUM 变 NULL）。</li>
 *   <li><b>灵石总量、金币总量、玩家数分三条语句</b>：一个扫
 *       {@code players_items}，一个 {@code SUM(gold)} 扫 {@code players}，
 *       一个 {@code COUNT(*)}。合并成一条 JOIN 会因为"没有物品的玩家"
 *       被丢掉而少算人数。</li>
 *   <li><b>竞拍列表与个人拍卖都先结算再查</b>：{@code settleExpiredAuctions()}
 *       在两个查询方法里被调用，这是惰性结算的兜底。DAO 只负责"取哪些行"，
 *       "取之前先结算"这个顺序留在服务层 —— DAO 里出现结算就等于
 *       一次 SELECT 附带写库，调用方无法预判。</li>
 *   <li><b>个人拍卖带 {@code LIMIT 20}</b>，全部竞拍不带</b>：玩家自己的
 *       挂单可能很多，而市场总览要的就是全量。两条查询的列清单、排序完全
 *       相同，只有 WHERE 与 LIMIT 不同。</li>
 *   <li><b>到期结算的 SELECT 只取 {@code status = 'active'}</b>，
 *       <b>不带 {@code end_time < now}</b>：时间判断在 Java 里做
 *       （{@code endTime.getTime() > now → continue}）。这样"现在"只被取一次，
 *       一批记录用同一个基准，不会因为逐条查库让前面的先结算、后面的晚结算。
 *       真正防重复结算靠 {@link #markAuctionListingEnded} 的
 *       {@code WHERE ... status = 'active'} 抢占。</li>
 *   <li><b>签到查询读不到行时返回空记录而不是 null</b>：
 *       {@code lastSignIn = null}、{@code streak = 0}。调用方据此判
 *       "从没签到过"，与"签到记录被清空"是同一处理。异常也一并吞掉
 *       （原实现 {@code catch (SQLException ignored)}）—— 签到是低频非关键
 *       路径，读失败不该让玩家连日常面板都打不开。</li>
 *   <li><b>{@code current_bidder_id} 可能是 NULL</b>：取的时候用
 *       {@code rs.getObject(...) != null} 判过再 {@code getLong}，
 *       因为"无人出价"与"出价 0"必须能区分（后者会走成交分支）。</li>
 *   <li><b>拍卖手续费的 {@code fee_rate} 是字面量 0.07</b>：写死在
 *       {@link #insertAuctionListing} 的 VALUES 里，不是参数。原实现如此。</li>
 *   <li><b>{@code last24hTradeVolume} 这个名字有误导性</b>：
 *       {@link #countSoldTradeListings} 的 SQL 里没有任何时间条件，
 *       实际统计的是"有史以来全部成交单"。方法名如实反映这一点，
 *       而服务层仍把它放进 {@code last24hTradeVolume} 键里以保持对外输出不变。</li>
 * </ul>
 */
public class EconomyDAO {

    // ==================== 灵庄 ====================

    /**
     * 插入一笔存款，返回新行 id。
     *
     * <p><b>只有 Connection 版</b>：{@code bankDeposit} 是"校验余额 → 扣金币 →
     * 插存款"三步，必须共用同一条连接。自取连接版会让扣款与插入之间出现
     * 半提交窗口。</p>
     *
     * <p><b>时间戳由调用方给</b>：{@code depositedAt} 是参数而不是 DAO 自己取。
     * 原实现传的是 {@code System.currentTimeMillis()}，但调用方取的这一刻
     * 要和事务开始对齐，DAO 再取一次会让两处时间不一致。</p>
     *
     * <p><b>{@code RETURN_GENERATED_KEYS} 与"读不到返回 0"逐字保留</b>：
     * 调用方把 0 当失败处理（存入失败文案），不能改成抛异常。</p>
     */
    public long insertBankDeposit(Connection conn, long playerId, String type, long principal,
                                   double rate, long depositedAt, long maturesAt) throws SQLException {
        String sql = "INSERT INTO player_bank (player_id, deposit_type, principal, rate, "
                + "deposited_at, matures_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, playerId);
            ps.setString(2, type);
            ps.setLong(3, principal);
            ps.setDouble(4, rate);
            ps.setLong(5, depositedAt);
            ps.setLong(6, maturesAt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    /**
     * 某玩家全部有效存款（{@code status = 'active'}，按创建时间倒序）。
     *
     * <p>返回原始行（{@code LinkedHashMap}，列名为键）。利息估算与
     * "剩余 N 天"文案在服务层算 —— 它们依赖"现在"。</p>
     */
    public List<Map<String, Object>> findActiveBankDeposits(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveBankDeposits(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询灵庄失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Map<String, Object>> findActiveBankDeposits(Connection conn, long playerId)
            throws SQLException {
        String sql = "SELECT * FROM player_bank WHERE player_id = ? AND status = 'active' ORDER BY created_at DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        }
        return rows;
    }

    /**
     * 按 id 取一笔存款。
     *
     * <p><b>刻意不带 {@code player_id} 条件</b>：归属校验在服务层
     * （见类注释）。</p>
     */
    public Map<String, Object> findBankDepositRow(long depositId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findBankDepositRow(conn, depositId);
        } catch (SQLException e) {
            throw new RuntimeException("查询存款失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public Map<String, Object> findBankDepositRow(Connection conn, long depositId) throws SQLException {
        String sql = "SELECT * FROM player_bank WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, depositId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToMap(rs);
                }
            }
        }
        return null;
    }

    // ==================== 经济数据面板 ====================

    /** 全服灵石总量（按面值折算后的等值下品灵石数）。 */
    public long sumSpiritStones() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return sumSpiritStones(conn);
        } catch (SQLException e) {
            throw new RuntimeException("统计灵石总量失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public long sumSpiritStones(Connection conn) throws SQLException {
        String sql = "SELECT COALESCE(SUM(CASE WHEN item_key = 'mtxgdn:spirit_stone_low' THEN quantity "
                + "WHEN item_key = 'mtxgdn:spirit_stone_mid' THEN quantity * 1000 "
                + "WHEN item_key = 'mtxgdn:spirit_stone_high' THEN quantity * 1000000 "
                + "WHEN item_key = 'mtxgdn:spirit_stone_supreme' THEN quantity * 1000000000 "
                + "WHEN item_key = 'mtxgdn:spirit_stone' THEN quantity ELSE 0 END), 0) "
                + "FROM players_items WHERE item_key LIKE 'mtxgdn:spirit_stone%'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 全服金币总量。 */
    public long sumGold() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return sumGold(conn);
        } catch (SQLException e) {
            throw new RuntimeException("统计金币总量失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public long sumGold(Connection conn) throws SQLException {
        String sql = "SELECT COALESCE(SUM(gold), 0) FROM players";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** 全服玩家数。 */
    public int countPlayers() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return countPlayers(conn);
        } catch (SQLException e) {
            throw new RuntimeException("统计玩家数失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int countPlayers(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 已成交的交易单数（面板上的"近 24h 成交量"）。
     *
     * <p><b>名字叫 24h 但 SQL 里没有时间条件</b>：原实现的
     * {@code estimate24hVolume} 就是一条 {@code COUNT(*) WHERE status='sold'}，
     * 实际统计的是"有史以来全部成交单"。这里不改写它 —— 那是改口径，
     * 会让面板数字一夜之间变小。方法名如实叫 {@code countSoldTradeListings}，
     * 而服务层仍把它放进 {@code last24hTradeVolume} 键里，保持对外输出不变。</p>
     *
     * <p>读失败返回 0 而不是抛异常：这是面板上的一个非关键指标，原实现
     * {@code catch (Exception ignored)}。</p>
     */
    public long countSoldTradeListings() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return countSoldTradeListings(conn);
        } catch (SQLException e) {
            return 0L;
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public long countSoldTradeListings(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM trade_listings WHERE status = 'sold'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ==================== 拍卖行 ====================

    /** 全部在拍挂单（按创建时间倒序，不带 LIMIT）。 */
    public List<Map<String, Object>> findActiveAuctionListings() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveAuctionListings(conn);
        } catch (SQLException e) {
            throw new RuntimeException("查询竞拍列表失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Map<String, Object>> findActiveAuctionListings(Connection conn) throws SQLException {
        String sql = "SELECT * FROM auction_listings WHERE status = 'active' ORDER BY created_at DESC";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rowToMap(rs));
            }
        }
        return rows;
    }

    /**
     * 插入一笔拍卖挂单。
     *
     * <p><b>只有 Connection 版</b>：{@code createAuction} 是"扣物品 → 插挂单"
     * 两步，扣物品失败要整单回滚。{@code fee_rate} 是字面量 {@code 0.07}
     * 而不是参数 —— 原实现如此，抽成参数会让"每单手续费可变"看起来像是
     * 一个已支持的能力。</p>
     *
     * <p>{@code endTime} 由调用方算好传入：它等于 {@code now + hours * 3600000}，
     * 且 {@code hours} 已被服务层夹到 {@code [1, 72]}。</p>
     */
    public void insertAuctionListing(Connection conn, long playerId, String fullKey, int quantity,
                                     long startPrice, java.sql.Timestamp endTime) throws SQLException {
        String sql = "INSERT INTO auction_listings (seller_player_id, item_key, quantity, "
                + "start_price, fee_rate, end_time) VALUES (?, ?, ?, ?, 0.07, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, fullKey);
            ps.setInt(3, quantity);
            ps.setLong(4, startPrice);
            ps.setTimestamp(5, endTime);
            ps.executeUpdate();
        }
    }

    /**
     * 记一笔出价，并把挂单的当前价/当前出价人抬到这一笔。
     *
     * <p><b>只有 Connection 版</b>：两条语句必须原子 —— 只记了出价却没能
     * 更新挂单，这一笔就成了"幽灵出价"。</p>
     *
     * <p><b>UPDATE 的 {@code WHERE id = ? AND status = 'active'} 是抢占条件</b>：
     * 命中 0 行说明挂单已结束（另一个请求刚结算掉），此时必须让整个事务失败
     * 而不是静默成功。所以这里<b>返回 boolean</b> 而不是 void，服务层据此抛
     * {@code "拍卖已结束"}。原实现是 {@code if (executeUpdate() == 0)
     * throw new SQLException("拍卖已结束")}，异常挪到服务层后文案不变。</p>
     */
    public boolean insertBidAndUpdateListing(Connection conn, long listingId, long bidderPlayerId,
                                              long amount) throws SQLException {
        String bidSql = "INSERT INTO auction_bids (listing_id, bidder_player_id, amount) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(bidSql)) {
            ps.setLong(1, listingId);
            ps.setLong(2, bidderPlayerId);
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
        String updSql = "UPDATE auction_listings SET current_bid = ?, current_bidder_id = ? "
                + "WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(updSql)) {
            ps.setLong(1, amount);
            ps.setLong(2, bidderPlayerId);
            ps.setLong(3, listingId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 事务内读取一条在拍挂单（MySQL 加 {@code FOR UPDATE} 行锁）。
     *
     * <p><b>只有 Connection 版</b>：必须在调用方的事务里跑才有锁的意义，
     * 自取连接版会给人"锁住了"的错觉。</p>
     *
     * <p><b>方言分支</b>：SQLite 下不加 {@code FOR UPDATE}（不识别），
     * 靠事务内 UPDATE 的隐式写锁；MySQL 下加。两边 WHERE 完全一致：
     * {@code id = ? AND status = 'active'} —— 已结束的挂单读不出来，
     * 由调用方判 {@code null} 给"拍卖已结束"文案。</p>
     *
     * <p>{@code currentBid} / {@code currentBidderId} 读成 {@code Long}：
     * 用 {@code getObject()} 判空后再取，"无人出价"与"出价 0"必须能区分
     * （后者会走成交分支）。</p>
     */
    public AuctionListingRow findAuctionListingForUpdate(Connection conn, long listingId)
            throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "SELECT * FROM auction_listings WHERE id = ? AND status = 'active'"
                : "SELECT * FROM auction_listings WHERE id = ? AND status = 'active' FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    AuctionListingRow l = new AuctionListingRow();
                    l.id = rs.getLong("id");
                    l.sellerPlayerId = rs.getLong("seller_player_id");
                    l.itemKey = rs.getString("item_key");
                    l.quantity = rs.getInt("quantity");
                    l.startPrice = rs.getLong("start_price");
                    l.currentBid = rs.getObject("current_bid") != null ? rs.getLong("current_bid") : null;
                    l.currentBidderId = rs.getObject("current_bidder_id") != null
                            ? rs.getLong("current_bidder_id") : null;
                    l.feeRate = rs.getDouble("fee_rate");
                    return l;
                }
            }
        }
        return null;
    }

    /**
     * 一笔在拍挂单（出价流程用）。
     *
     * <p>字段包级可见：使用者只有 {@code EconomyService}，且每行只被读一次。</p>
     */
    public static class AuctionListingRow {
        public long id;
        public long sellerPlayerId;
        public String itemKey;
        public int quantity;
        public long startPrice;
        /** {@code null} 表示无人出价。 */
        public Long currentBid;
        /** {@code null} 表示无人出价。 */
        public Long currentBidderId;
        public double feeRate;
    }

    /**
     * 把一笔存款标为已取款（只在仍是 {@code active} 时）。
     *
     * <p><b>只有 Connection 版</b>：取款是"校验归属 → 发本息 → 标已取"三步，
     * 必须共用一条连接。{@code WHERE id = ? AND status = 'active'} 是抢占条件，
     * 命中 0 行说明这张单已经被取过（并发双取），此时整个事务要失败。</p>
     */
    public boolean markBankDepositWithdrawn(Connection conn, long depositId, long interest)
            throws SQLException {        String sql = "UPDATE player_bank SET status = 'withdrawn', interest_earned = ? "
                + "WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, interest);
            ps.setLong(2, depositId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 把一条挂单标为已结束（只在仍是 {@code active} 时）。
     *
     * <p><b>只有 Connection 版</b>：到期结算靠这条的返回值抢占 ——
     * 定时任务与惰性结算可能同时处理同一条挂单，只有一个能拿到非 0 行，
     * 另一个整轮放弃（否则会发两次灵石/物品）。</p>
     */
    public boolean markAuctionListingEnded(Connection conn, long listingId) throws SQLException {
        String sql = "UPDATE auction_listings SET status = 'ended' WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 扣除洞府灵气（灵石提纯用）。
     *
     * <p><b>表归属说明</b>：这条动的是 {@code caves} 表，而 {@code caves}
     * 的主要使用者是 {@code CaveService}。放在这里而不是 CaveDAO，是因为
     * 唯一的调用方是 {@code EconomyService.purifySpiritStone} ——
     * "提纯要耗灵气"是提纯这条业务规则的组成部分，抽到 CaveDAO 会让
     * 经济域为了一个条件扣减去依赖洞府域。等 CaveService 抽 DAO 时
     * 如果出现第二个调用方，再把这条搬过去并保留同样的 WHERE。</p>
     *
     * <p><b>只有 Connection 版</b>：提纯是"扣灵石 → 扣灵气 → 发高阶灵石"
     * 三步，必须共用一条连接。</p>
     *
     * <p><b>{@code WHERE ... spirit_energy >= ?} 是条件扣减</b>：
     * 灵气不足时 {@code executeUpdate()} 命中 0 行，此时整个事务要失败
     * （服务层抛 {@code "洞府灵气不足"}）。SQLite 没有
     * {@code GREATEST(0, ...)}，也不能写成
     * {@code SET spirit_energy = spirit_energy - ?} 再回查 ——
     * 那会把灵气扣成负数。</p>
     */
    public boolean consumeCaveAura(Connection conn, long caveId, long auraCost) throws SQLException {
        String sql = "UPDATE caves SET spirit_energy = spirit_energy - ? "
                + "WHERE id = ? AND spirit_energy >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, auraCost);
            ps.setLong(2, caveId);
            ps.setLong(3, auraCost);
            return ps.executeUpdate() > 0;
        }
    }

    /** 某玩家的挂单（按创建时间倒序，最多 20 条）。 */    public List<Map<String, Object>> findPlayerAuctionListings(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPlayerAuctionListings(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询个人拍卖失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<Map<String, Object>> findPlayerAuctionListings(Connection conn, long playerId)
            throws SQLException {
        String sql = "SELECT * FROM auction_listings WHERE seller_player_id = ? "
                + "ORDER BY created_at DESC LIMIT 20";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(rowToMap(rs));
                }
            }
        }
        return rows;
    }

    /**
     * 全部在拍挂单的原始行（到期结算用）。
     *
     * <p><b>刻意不带 {@code end_time < now}</b>：时间判断在服务层做，
     * 一批记录共用一个"现在"。见类注释。</p>
     *
     * <p><b>返回的是结算视图而不是通用行</b>：{@code end_time} 以
     * {@code java.sql.Timestamp} 原样给出（结算要靠 {@code getTime()} 比较），
     * 而 {@code current_bidder_id} 用 {@code getObject()} 判空后再取
     * —— "无人出价"与"出价 0"必须能区分（后者会走成交分支）。
     * 通用的 {@link #rowToMap} 会把 TIMESTAMP 压成 {@code "ts:<毫秒>"} 字符串，
     * 那是给 JSON 序列化用的，这里不能用。</p>
     */
    public List<AuctionSettlementRow> findActiveAuctionsForSettlement() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveAuctionsForSettlement(conn);
        } catch (SQLException e) {
            throw new RuntimeException("查询待结算拍卖失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<AuctionSettlementRow> findActiveAuctionsForSettlement(Connection conn)
            throws SQLException {
        String sql = "SELECT * FROM auction_listings WHERE status = 'active'";
        List<AuctionSettlementRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                AuctionSettlementRow row = new AuctionSettlementRow();
                row.listingId = rs.getLong("id");
                row.sellerPlayerId = rs.getLong("seller_player_id");
                row.itemKey = rs.getString("item_key");
                row.quantity = rs.getInt("quantity");
                row.currentBid = rs.getLong("current_bid");
                row.currentBidderId = rs.getObject("current_bidder_id") != null
                        ? rs.getLong("current_bidder_id") : null;
                row.feeRate = rs.getDouble("fee_rate");
                row.endTime = rs.getTimestamp("end_time");
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * 到期结算需要的一行。
     *
     * <p>字段是包级可见而不是 private + getter：这个类的使用者只有
     * {@code EconomyService}，且字段只在结算流程里被读一次。</p>
     */
    public static class AuctionSettlementRow {
        public long listingId;
        public long sellerPlayerId;
        public String itemKey;
        public int quantity;
        public long currentBid;
        /** {@code null} 表示无人出价 —— 与"出价 0"不同。 */
        public Long currentBidderId;
        public double feeRate;
        /** 可能为 {@code null}（建单时未设截止时间），服务层据此跳过。 */
        public java.sql.Timestamp endTime;
    }

    // ==================== 签到 ====================

    /**
     * 读签到记录；没有记录时返回"从未签到"的空记录（{@code lastSignIn = null}、
     * {@code streak = 0}），读失败同样返回空记录。
     */
    public SignInRecord findSignIn(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findSignIn(conn, playerId);
        } catch (SQLException ignored) {
            return new SignInRecord();
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public SignInRecord findSignIn(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT last_sign_in, streak FROM player_economy WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    SignInRecord r = new SignInRecord();
                    r.lastSignIn = rs.getString("last_sign_in");
                    r.streak = rs.getInt("streak");
                    return r;
                }
            }
        }
        return new SignInRecord();
    }

    /**
     * 事务内的行锁复查：读今天的签到日期。
     *
     * <p><b>刻意带方言分支</b>：SQLite 下不加 {@code FOR UPDATE}（不识别），
     * MySQL 下加 —— 靠行锁/间隙锁防并发双签。这条必须在签到的
     * {@code runTransaction} 里跑，抽成独立方法只是为了让 SQL 有个唯一的家。</p>
     *
     * <p>读不到行返回 {@code null}（= 从没签到过），由调用方与 {@code today}
     * 比较判"是否已签"。</p>
     */
    public String findLastSignInForUpdate(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT last_sign_in FROM player_economy WHERE player_id = ?"
                + (DatabaseManager.isSqlite() ? "" : " FOR UPDATE");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("last_sign_in") : null;
            }
        }
    }

    /**
     * 写入/更新签到记录（upsert）。
     *
     * <p><b>只有 Connection 版</b>：它必须和上面的行锁复查、发奖落在同一条
     * 连接上，否则"复查通过"与"落库"之间会被别的请求插进来。</p>
     *
     * <p><b>方言分支与参数顺序逐字保留</b>：SQLite 用
     * {@code ON CONFLICT(player_id) DO UPDATE SET ... = excluded....}，
     * MySQL 用 {@code ON DUPLICATE KEY UPDATE ... = VALUES(...)}。
     * 两边的 {@code excluded.} / {@code VALUES()} 前缀不能互换。</p>
     */
    public void upsertSignIn(Connection conn, long playerId, String today, int streak)
            throws SQLException {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) "
                    + "ON CONFLICT(player_id) DO UPDATE SET last_sign_in = excluded.last_sign_in, "
                    + "streak = excluded.streak";
        } else {
            sql = "INSERT INTO player_economy (player_id, last_sign_in, streak) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_sign_in = VALUES(last_sign_in), "
                    + "streak = VALUES(streak)";
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setInt(3, streak);
            ps.executeUpdate();
        }
    }

    /** 签到记录：{@code lastSignIn} 是 {@code null} 表示从未签到。 */
    public static class SignInRecord {
        private String lastSignIn;
        private int streak;

        public String getLastSignIn() {
            return lastSignIn;
        }

        public int getStreak() {
            return streak;
        }
    }

    // ==================== 行映射 ====================

    /**
     * {@code SELECT *} → 行。
     *
     * <p>列名来自 {@code ResultSetMetaData} 而不是列序号：加列不会让这里
     * 静默错位。时间戳与 BLOB 的处理与 {@link PlayerDataCacheDAO} 一致
     * （{@code "ts:<毫秒>"} 与 Base64），因为这两类列都可能出现在
     * 备份/还原与 JSON 序列化的路径上。</p>
     */
    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String columnName = meta.getColumnName(i);
            Object value = rs.getObject(i);
            if (value instanceof java.util.Date) {
                row.put(columnName, "ts:" + ((java.util.Date) value).getTime());
            } else if (value instanceof byte[]) {
                row.put(columnName, java.util.Base64.getEncoder().encodeToString((byte[]) value));
            } else {
                row.put(columnName, value);
            }
        }
        return row;
    }
}
