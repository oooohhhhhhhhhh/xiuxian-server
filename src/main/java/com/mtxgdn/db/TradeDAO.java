package com.mtxgdn.db;

import com.mtxgdn.game.service.TradeService;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 坊市（玩家对玩家交易）在 {@code trade_listings} / {@code trade_price_history}
 * 两张表上的读写入口。
 *
 * <p>从 {@code TradeService} 抽出来的 DAO。查询类成对重载；写操作与
 * "读一批 → 逐条事务"的扫描只有 Connection 版。</p>
 *
 * <p><b>这个域的坑几乎全在"抢占条件"上</b>：坊市的每一笔写都要防止
 * 并发下同一件物品被处理两次（复制物品 / 重复退款 / 重复扣款）。
 * 原实现的做法是清一色 {@code WHERE id = ? AND status = 'active'}，
 * 靠 {@code executeUpdate()} 的命中行数判成败，<b>没有一处用
 * SELECT-then-UPDATE</b>。抽 DAO 时这些 WHERE 一个字符都不能动。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>回填历史挂单刻意分两条连接、且 UPDATE 带
 *       {@code AND expires_at IS NULL}</b>：{@link #backfillLegacyExpiry}
 *       先 SELECT 出 {@code id, created_at}，再在<b>另一条连接</b>上
 *       {@code executeBatch}。第二次连接的 WHERE 仍带
 *       {@code expires_at IS NULL} —— 与 SELECT 的条件相同，所以这个操作
 *       是幂等的：两个线程同时跑，UPDATE 都命中 0 行，不会把已回填的值
 *       覆盖成别的。原实现就是两条连接（不是一条），这里保持原样：
 *       合成一条连接+流式读取反而会在流还没读完时同连接执行批更新，
 *       SQLite 下会撞 {@code SQLITE_BUSY}。</li>
 *   <li><b>回填用 Java 算时间而不是 SQL 日期函数</b>：
 *       {@code base + LISTING_DURATION_HOURS * 3600_000L}。原注释说明这是
 *       为了避开 SQLite/MySQL 的方言差异。{@code created_at} 为 NULL 时
 *       以当前时间为基准 —— 否则这些挂单永远不过期。</li>
 *   <li><b>回填失败只 warn 不抛异常</b>：老库兼容是尽力而为，回填失败不该
 *       让坊市打不开。所以 {@link #backfillLegacyExpiry} 返回
 *       {@code boolean} 而不是抛。</li>
 *   <li><b>到期回收的 SELECT 带 {@code expires_at IS NOT NULL}</b>：
 *       历史挂单的 {@code expires_at} 是 NULL（永不过期），少了这个条件
 *       会把老挂单一并回收。同时 {@code expires_at <= ?} 用调用方给的
 *       "现在"而不是 {@code CURRENT_TIMESTAMP} —— 一批记录共用一个基准。</li>
 *   <li><b>到期回收的抢占与坊市购买的抢占是同一条 UPDATE 的不同状态</b>：
 *       回收改成 {@code 'expired'}，购买改成 {@code 'sold'}，两者的 WHERE
 *       都是 {@code id = ? AND status = 'active'}。原注释写明："否则会与
 *       买家并发购买竞争，导致物品被退回两次（复制物品）"。</li>
 *   <li><b>价格历史的统计用别名而不是位置下标</b>：
 *       {@code COUNT(*) AS cnt, MIN(unit_price) AS minp, ...}，
 *       读取时按别名 {@code rs.getLong("cnt")}。加一列统计不会让这里错位。</li>
 *   <li><b>统计在 {@code count > 0} 之后才读 min/max/avg/qty</b>：
 *       没有成交记录时这些列全是 NULL，直接读会得到 0 但语义不明；
 *       原实现用 {@code if (count > 0)} 包住，这里保留同样的结构。</li>
 *   <li><b>{@code AVG} 在服务层 {@code Math.round}</b>：SQLite 的 AVG 返回
 *       DOUBLE，MySQL 可能返回 DECIMAL，四舍五入放在 Java 里两边言一致。</li>
 *   <li><b>在售最低价是 {@code MIN(CAST(price_spirit_stones AS DOUBLE) / quantity)}</b>：
 *       整数相除会先把单价截断成 0。{@code quantity > 0} 防除零；
 *       {@code (expires_at IS NULL OR expires_at > ?)} 让历史挂单也参与
 *       比价（它们仍然有效），而"已过期但还没被回收"的不参与。</li>
 *   <li><b>清理价格历史的返回值是删除行数</b>：调用方（管理台）直接展示
 *       "清理了 N 条"。失败返回 0 而不是抛 —— 清理是维护操作。</li>
 *   <li><b>挂单查询走回调设参数</b>：{@link #queryListings} 的签名是
 *       {@code (String sql, SqlParameterSetter setter)}，DAO 不认识
 *       "我的挂单 / 全部在售"这些业务语义，SQL 由调用方给。这是原实现的
 *       形状，保留它：把每条查询都在 DAO 里写一遍会让 DAO 变成业务词典。</li>
 *   <li><b>{@code TradeListing.expiresAt} 的 0 表示永不过期</b>：
 *       映射时 {@code rs.getTimestamp("expires_at")} 为 NULL 就写 0，
 *       与 {@code remainingMs()} 的 {@code <= 0 → -1} 对应。不能改成
 *       {@code Long} 装箱 —— 那是公开 API。</li>
 *   <li><b>{@code TradeListing} 是 {@link TradeService} 的公开类型</b>：
 *       搬不动（全项目的管理台与 WebSocket 都在用
 *       {@code TradeService.TradeListing}），所以 {@link #mapListing}
 *       与查询方法仍返回它。这是 DAO 依赖服务层类型的唯一一处，
 *       理由是公开 API 不能变；其余地方一律不碰服务层。</li>
 *   <li><b>写入价格失败不影响成交</b>：{@link #recordPriceHistory} 自己
 *       try/catch 并 warn，原实现如此（成交已完成，历史只是辅助信息）。
 *       所以它<b>不</b>抛 {@link SQLException}。</li>
 *   <li><b>单价 {@code Math.max(1, totalPrice / quantity)}</b>：零元成交
 *       也要记 1，否则 {@code MIN(unit_price)} 会被 0 污染。</li>
 * </ul>
 */
public class TradeDAO {

    // ==================== 老库兼容 ====================

    /**
     * 历史挂单（{@code expires_at IS NULL}）的 {@code id} 与 {@code created_at}。
     *
     * <p>只取两列：回填只需要它们。{@code created_at} 可能为 NULL，
     * 用 {@link LegacyExpiryRow} 的 {@code createdAt} 承载。</p>
     */
    public List<LegacyExpiryRow> findLegacyActiveListings() {
        String sql = "SELECT id, created_at FROM trade_listings "
                + "WHERE status = 'active' AND expires_at IS NULL";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<LegacyExpiryRow> rows = new ArrayList<>();
            while (rs.next()) {
                LegacyExpiryRow row = new LegacyExpiryRow();
                row.id = rs.getLong("id");
                row.createdAt = rs.getTimestamp("created_at");
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("查询历史挂单失败", e);
        }
    }

    /**
     * 按批回填 {@code expires_at}（自取连接版）。
     *
     * <p>内部开一条短连接委托给 {@link #backfillLegacyExpiry(Connection, List, List)}，
     * SQL 只写一份。WHERE 带 {@code expires_at IS NULL} 保证幂等。</p>
     */
    public int backfillLegacyExpiry(List<Timestamp> expiresAtList, List<Long> ids) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return backfillLegacyExpiry(conn, expiresAtList, ids);
        } catch (SQLException e) {
            throw new RuntimeException("回填历史挂单到期时间失败", e);
        }
    }

    /**
     * 按批回填 {@code expires_at}。
     *
     * <p><b>刻意有两条独立连接</b>：调用方先 {@link #findLegacyActiveListings()}
     * 拿到 {@code id, created_at}，再用这一条连接 {@code executeBatch}。
     * 原实现就是两条连接（不是一条），这里保持原样：合成一条连接 + 流式
     * 读取反而会在流还没读完时同连接执行批更新，SQLite 下会撞
     * {@code SQLITE_BUSY}。WHERE 仍带 {@code expires_at IS NULL} 与 SELECT
     * 的条件相同，所以整个操作幂等。</p>
     *
     * @param expiresAtList 与 {@code ids} 等长，逐行给出算好的到期时间
     * @param ids           要回填的挂单 id
     * @return 实际更新行数
     */
    public int backfillLegacyExpiry(Connection conn, List<Timestamp> expiresAtList, List<Long> ids)
            throws SQLException {
        String sql = "UPDATE trade_listings SET expires_at = ? WHERE id = ? AND expires_at IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setTimestamp(1, expiresAtList.get(i));
                ps.setLong(2, ids.get(i));
                ps.addBatch();
            }
            int[] batch = ps.executeBatch();
            int filled = 0;
            for (int n : batch) {
                if (n > 0) filled += n;
            }
            return filled;
        }
    }

    /** 一行待回填的历史挂单。 */
    public static class LegacyExpiryRow {
        public long id;
        /** 可能为 {@code null}：调用方据此改用当前时间做基准。 */
        public Timestamp createdAt;
    }

    // ==================== 到期回收 ====================

    /**
     * 取所有已到期且在售的挂单（只取退回物品需要的四列）。
     *
     * <p><b>刻意带 {@code expires_at IS NOT NULL}</b>：历史挂单永不过期。
     * {@code now} 由调用方给，一批记录共用一个基准。</p>
     */
    public List<DueListingRow> findDueListings(Timestamp now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findDueListings(conn, now);
        } catch (SQLException e) {
            throw new RuntimeException("查询到期挂单失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<DueListingRow> findDueListings(Connection conn, Timestamp now) throws SQLException {
        String sql = "SELECT id, seller_player_id, item_key, quantity FROM trade_listings "
                + "WHERE status = 'active' AND expires_at IS NOT NULL AND expires_at <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<DueListingRow> rows = new ArrayList<>();
                while (rs.next()) {
                    DueListingRow row = new DueListingRow();
                    row.id = rs.getLong("id");
                    row.sellerPlayerId = rs.getLong("seller_player_id");
                    row.itemKey = rs.getString("item_key");
                    row.quantity = rs.getInt("quantity");
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询到期挂单失败", e);
        }
    }

    /** 一行待回收的挂单。 */
    public static class DueListingRow {
        public long id;
        public long sellerPlayerId;
        public String itemKey;
        public int quantity;
    }

    /**
     * 把挂单标记为已过期（抢占）。
     *
     * <p><b>只有 Connection 版</b>：必须在调用方的事务里和"退回物品"
     * 同生共死。WHERE 的 {@code status = 'active'} 是抢占条件 ——
     * 命中 0 行说明买家已经并发买走，这一方不该再退一次物品。</p>
     */
    public boolean markListingExpired(Connection conn, long listingId) throws SQLException {
        String sql = "UPDATE trade_listings SET status = 'expired' WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 事务内读玩家某物品的数量（带行锁）。
     *
     * <p><b>只有 Connection 版</b>：必须在调用方的事务里跑才有锁的意义。
     * {@code FOR UPDATE} 由 {@code DatabaseManager.getDialect()
     * .getForUpdateClause()} 给出，DAO 不自己拼方言。</p>
     *
     * <p>读不到行返回 {@code -1} 而不是 0：调用方要区分"没有这个物品"
     * （给 {@code trade.list.no_item} 文案）与"有 0 个"。</p>
     */
    public long findPlayerItemQuantityForUpdate(Connection conn, long playerId, String itemKey)
            throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? "
                + DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("quantity") : -1L;
            }
        }
    }

    /**
     * 把挂单标记为已售出（抢占）。
     *
     * <p>与 {@link #markListingExpired} 是同一条 UPDATE 的不同目标状态，
     * 两者的 WHERE 完全一致。返回受影响行数（而不是 boolean）：
     * 调用方要区分"没抢到"与其它情况。</p>
     */
    public int markListingSold(Connection conn, long listingId) throws SQLException {
        String sql = "UPDATE trade_listings SET status = 'sold' WHERE id = ? AND status = 'active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            return ps.executeUpdate();
        }
    }

    /**
     * 事务内按 id 读一条在售挂单（带行锁，且未到期）。
     *
     * <p><b>只有 Connection 版</b>：S6 修复把 listing 读取移入事务内并加锁，
     * 避免"事务外快照 + 竞态覆盖"。自取连接版会让锁失去意义。</p>
     *
     * <p><b>{@code (expires_at IS NULL OR expires_at > ?)} 不能少</b>：
     * 只靠定时回收任务不够（最长 60s 才跑一次），少了这个条件，到期后仍可
     * 被购买 —— 买家买到"僵尸挂单"，而卖家的物品已被回收退回。
     * 历史挂单的 {@code expires_at} 是 NULL，仍然可以买。</p>
     *
     * <p>读不到返回 {@code null}：调用方据此给"挂单不存在或已售出"的文案。</p>
     */
    public TradeService.TradeListing findActiveListingForUpdate(Connection conn, long listingId,
                                                                Timestamp now) throws SQLException {
        String sql = "SELECT * FROM trade_listings WHERE id = ? AND status = 'active' "
                + "AND (expires_at IS NULL OR expires_at > ?) "
                + DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            ps.setTimestamp(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapListing(rs) : null;
            }
        }
    }

    /**
     * 全部在售挂单的 SQL（按创建时间倒序，新挂单在前）。
     *
     * <p><b>{@code (expires_at IS NULL OR expires_at > ?)} 不能少</b>：
     * 到期挂单不再展示，即使回收任务还没跑到。历史挂单的
     * {@code expires_at} 是 NULL，仍然展示。</p>
     */
    public static final String ACTIVE_LISTINGS_SQL =
            "SELECT * FROM trade_listings WHERE status = 'active' "
                    + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC";

    /**
     * 某玩家的在售挂单的 SQL（无 ORDER BY）。
     *
     * <p>同样带 {@code (expires_at IS NULL OR expires_at > ?)}。
     * 原实现这里没有 ORDER BY，保留原样 —— 个人挂单列表由调用方决定顺序。</p>
     */
    public static final String MY_ACTIVE_LISTINGS_SQL =
            "SELECT * FROM trade_listings WHERE seller_player_id = ? AND status = 'active' "
                    + "AND (expires_at IS NULL OR expires_at > ?)";

    /**
     * 插入一条挂单。
     *
     * <p><b>只有 Connection 版</b>：上架是"扣物品 → 插挂单"两步，
     * 扣物品失败要整单回滚。{@code expiresAt} 由调用方算好传入
     * （{@code now + LISTING_DURATION_HOURS * 3600_000L}）。</p>
     *
     * <p><b>列清单与参数个数逐字保留</b>：{@code fee} 是调用方算好的实收
     * 手续费（可能因灵根减半），DAO 不自己算。</p>
     */
    public void insertListing(Connection conn, long playerId, String itemKey, int quantity,
                              long priceSpiritStones, long fee, Timestamp expiresAt)
            throws SQLException {
        String sql = "INSERT INTO trade_listings "
                + "(seller_player_id, item_key, quantity, price_spirit_stones, fee, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.setInt(3, quantity);
            ps.setLong(4, priceSpiritStones);
            ps.setLong(5, fee);
            ps.setTimestamp(6, expiresAt);
            ps.executeUpdate();
        }
    }

    /**
     * 记录一笔成交到价格历史（供行情查询）。
     *
     * <p><b>只有 Connection 版</b>：必须和"扣买家灵石 / 发卖家灵石 /
     * 标记已售"落在同一条连接上，否则会出现"成交了但没记历史"。</p>
     *
     * <p><b>不抛 {@link SQLException}</b>：原实现在这里单独 try/catch 并
     * warn —— 成交已完成，历史只是辅助信息，写失败不该让整笔交易回滚
     * （那会把买家的钱和卖家的货一起退回去，而物品可能已经不在原处）。</p>
     *
     * <p><b>单价 {@code Math.max(1, totalPrice / quantity)}</b>：零元成交
     * 也要记 1，否则 {@code MIN(unit_price)} 会被 0 污染。{@code quantity <= 0}
     * 或 {@code totalPrice <= 0} 时直接返回不记。</p>
     */
    public void recordPriceHistory(Connection conn, String itemKey, int quantity, long totalPrice) {
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
            GameLogger.getLogger(TradeDAO.class)
                    .warn("写入坊市价格历史失败（不影响成交）: " + e.getMessage());
        }
    }

    // ==================== 成交价格历史 / 行情 ====================

    /** 某物品近期成交的五项统计。 */
    public PriceHistoryStats getPriceHistoryStats(String itemKey, Timestamp since) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getPriceHistoryStats(conn, itemKey, since);
        } catch (SQLException e) {
            throw new RuntimeException("查询价格历史失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public PriceHistoryStats getPriceHistoryStats(Connection conn, String itemKey, Timestamp since)
            throws SQLException {
        String sql = "SELECT COUNT(*) AS cnt, MIN(unit_price) AS minp, MAX(unit_price) AS maxp, "
                + "AVG(unit_price) AS avgp, SUM(quantity) AS qty "
                + "FROM trade_price_history WHERE item_key = ? AND sold_at >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setTimestamp(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PriceHistoryStats s = new PriceHistoryStats();
                    s.count = rs.getLong("cnt");
                    // 没有成交记录时 min/max/avg/qty 全是 NULL：原实现在 count > 0
                    // 之后才读它们，这里保持同样的顺序
                    if (s.count > 0) {
                        s.minUnitPrice = rs.getLong("minp");
                        s.maxUnitPrice = rs.getLong("maxp");
                        // AVG 在服务层 Math.round：SQLite 返 DOUBLE、
                        // MySQL 可能返 DECIMAL，四舍五入放 Java 里两边言一致
                        s.avgUnitPriceRaw = rs.getDouble("avgp");
                        s.totalQuantity = rs.getLong("qty");
                    }
                    return s;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询价格历史失败", e);
        }
        return new PriceHistoryStats();
    }

    /** 价格历史的五项统计；{@code count == 0} 时其余字段无意义。 */
    public static class PriceHistoryStats {
        public long count;
        public long minUnitPrice;
        public long maxUnitPrice;
        /** 未四舍五入的 AVG：调用方自己 {@code Math.round}。 */
        public double avgUnitPriceRaw;
        public long totalQuantity;
    }

    /**
     * 当前在售挂单中的最低单价（总价 ÷ 数量），无在售返回 0。
     *
     * <p>{@code CAST(price_spirit_stones AS DOUBLE)} 不能省：整数相除会先把
     * 单价截断成 0。{@code (expires_at IS NULL OR expires_at > ?)} 让历史
     * 挂单也参与比价，而"已过期还没被回收"的不参与。</p>
     */
    public long lowestActiveUnitPrice(String itemKey, Timestamp now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return lowestActiveUnitPrice(conn, itemKey, now);
        } catch (SQLException e) {
            throw new RuntimeException("查询在售最低价失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public long lowestActiveUnitPrice(Connection conn, String itemKey, Timestamp now)
            throws SQLException {
        String sql = "SELECT MIN(CAST(price_spirit_stones AS DOUBLE) / quantity) AS lowest "
                + "FROM trade_listings WHERE item_key = ? AND status = 'active' AND quantity > 0 "
                + "AND (expires_at IS NULL OR expires_at > ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setTimestamp(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.round(rs.getDouble("lowest"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询在售最低价失败", e);
        }
        return 0;
    }

    /**
     * 清理过期的价格历史，返回删除行数。
     *
     * <p>失败返回 0 而不是抛：清理是维护操作，调用方（管理台）直接展示
     * "清理了 N 条"。</p>
     */
    public int cleanupPriceHistory(Timestamp cutoff) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return cleanupPriceHistory(conn, cutoff);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public int cleanupPriceHistory(Connection conn, Timestamp cutoff) throws SQLException {
        String sql = "DELETE FROM trade_price_history WHERE sold_at < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, cutoff);
            return ps.executeUpdate();
        }
    }

    // ==================== 挂单查询 ====================

    /**
     * 全部在售挂单（按创建时间倒序，新挂单在前）。
     *
     * <p>给调用方一个具名方法而不是让它拼 SQL：原实现是
     * {@code queryListings(sql, ps -> ...)}，回调里要 {@code setTimestamp}，
     * 于是服务层不得不 import {@code PreparedStatement}。
     * 写成具名方法后服务层一行 JDBC 都不剩。</p>
     *
     * <p>{@code now} 由调用方给：DAO 不自己读时钟。</p>
     */
    public List<TradeService.TradeListing> findActiveListings(Timestamp now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveListings(conn, now);
        } catch (SQLException e) {
            throw new RuntimeException("获取挂单列表失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<TradeService.TradeListing> findActiveListings(Connection conn, Timestamp now)
            throws SQLException {
        return queryListings(conn, ACTIVE_LISTINGS_SQL, ps -> ps.setTimestamp(1, now));
    }

    /**
     * 某玩家的在售挂单。
     *
     * <p>同样是为了让服务层不碰 {@code PreparedStatement}。</p>
     */
    public List<TradeService.TradeListing> findPlayerListings(long playerId, Timestamp now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPlayerListings(conn, playerId, now);
        } catch (SQLException e) {
            throw new RuntimeException("获取挂单列表失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。 */
    public List<TradeService.TradeListing> findPlayerListings(Connection conn, long playerId,
                                                              Timestamp now) throws SQLException {
        return queryListings(conn, MY_ACTIVE_LISTINGS_SQL, ps -> {
            ps.setLong(1, playerId);
            ps.setTimestamp(2, now);
        });
    }

    /**
     * 通用的挂单查询（db 包私有）。
     *
     * <p><b>刻意 private</b>：坊市只有 {@link #findActiveListings()} 与
     * {@link #findPlayerListings(long)} 两条查询。做成 public 就得给它配一个
     * Connection 首参重载（否则事务内调用只能另开连接），而"调用方自己拼
     * 一条 trade_listings 的 SQL"正是抽取想消除的东西。真有新查询需求时，
     * 在这里加一个具名的 private 重载，再开一个 public 入口。</p>
     */
    private List<TradeService.TradeListing> queryListings(Connection conn, String sql,
                                                          SqlParameterSetter setter)
            throws SQLException {
        List<TradeService.TradeListing> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
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

    /** 挂单查询的参数设置回调（db 包自己的，服务层因此不必 import JDBC 类型）。 */
    @FunctionalInterface
    public interface SqlParameterSetter {
        void set(PreparedStatement ps) throws SQLException;
    }

    /**
     * {@code trade_listings} 行 → {@link TradeService.TradeListing}。
     *
     * <p><b>返回服务层的公开类型</b>：{@code TradeListing} 被管理台与
     * WebSocket 直接用，搬不动。这是本 DAO 唯一依赖服务层类型的地方。</p>
     *
     * <p>{@code expiresAt} 的 0 表示永不过期：{@code getTimestamp} 返回
     * NULL 时写 0，与 {@code remainingMs()} 的 {@code <= 0 → -1} 对应。
     * 不能改成 {@code Long} 装箱 —— 那是公开 API。</p>
     */
    private static TradeService.TradeListing mapListing(ResultSet rs) throws SQLException {
        TradeService.TradeListing l = new TradeService.TradeListing();
        l.id = rs.getLong("id");
        l.sellerPlayerId = rs.getLong("seller_player_id");
        l.itemKey = rs.getString("item_key");
        l.quantity = rs.getInt("quantity");
        l.priceSpiritStones = rs.getLong("price_spirit_stones");
        l.fee = rs.getLong("fee");
        l.createdAt = rs.getString("created_at");
        Timestamp exp = rs.getTimestamp("expires_at");
        l.expiresAt = exp != null ? exp.getTime() : 0L; // 0 = 永不过期（历史挂单）
        return l;
    }
}
