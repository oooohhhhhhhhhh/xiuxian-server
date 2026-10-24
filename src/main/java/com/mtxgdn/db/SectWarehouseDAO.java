package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门仓库（{@code sect_warehouse} 表，以及它对 {@code sect_members} / {@code sects}
 * 的两个副作用）的读写入口。
 *
 * <p>从 {@code SectWarehouseDomain} 抽出来的 DAO。每个方法一对重载：
 * {@code xxx(...)} 自己开一条短连接并把 {@link SQLException} 包成
 * {@link RuntimeException}；{@code xxx(Connection conn, ...)} 只用调用方的连接、
 * 直接抛 {@link SQLException}。</p>
 *
 * <p><b>为什么这里必须有 Connection 重载</b>：捐献与取出<b>原本就是各一个
 * {@code runTransaction}</b>，块内四步（扣背包 → upsert 仓库 → 加贡献 → 加声望 /
 * 扣贡献 → 减仓库 → 发背包）必须共用同一条 {@code conn}：
 * 一是不能读到别的连接还没提交的数据，二是 SQLite 下
 * {@code BEGIN DEFERRED} 已持有写锁，再开一条连接就是
 * {@code SQLITE_BUSY_SNAPSHOT}（这个错误不受 {@code busy_timeout} 约束），
 * 三是扣了背包却没入仓库必须整体回滚。所以事务内的四步各有一个
 * {@code conn} 重载，事务块本身留在服务层 —— <b>DAO 不碰事务</b>。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>upsert 的双方言分支</b>：SQLite 的 {@code ON CONFLICT(sect_id, item_key) DO UPDATE}
 *       与 MySQL 的 {@code ON DUPLICATE KEY UPDATE} 都不通用，逐字保留在
 *       {@link #upsertSql()} 里。</li>
 *   <li><b>取出时的两处条件更新</b>：{@code WHERE ... AND quantity >= ?} 与
 *       {@code WHERE id = ? AND contribution >= ?} 让"够不够"与"扣减"在同一条
 *       UPDATE 里完成，且返回 {@code false} 时调用方抛 {@link SQLException}
 *       触发整体回滚。这是原实现的 {@code if (ps.executeUpdate() == 0) throw ...}。</li>
 *   <li><b>贡献值只在普通弟子捐献时才发</b>：管理职位（宗主/副宗主/长老）可以免费
 *       取物，若捐献仍发贡献，就会"取出→捐献→取出"无限刷。这条判定在服务层
 *       （读 {@code member.canManage()}），DAO 只提供"加多少"的方法。</li>
 *   <li><b>列头 {@code sw.*} + {@code LEFT JOIN players}</b>：仓库列表要显示
 *       "谁捐的"，LEFT JOIN 保证捐赠者被删号时这一行还在。原实现的
 *       {@code ORDER BY sw.created_at} 也保留。</li>
 *   <li><b>查不到行返回 0</b>：{@code getWarehouseItemCount} 原来 {@code return 0}
 *       作为兜底，不是返回 -1 或抛异常。</li>
 *   <li><b>行 → 对象的映射留在服务层</b>：DAO 只交
 *       {@link WarehouseItemRow} 这种扁平记录（列头 {@code sw.*} 加 JOIN 出来的
 *       {@code donated_by_name}，以及每列"老库缺列就跳过"的容错）。
 *       {@code SectSupport.mapWarehouseItem} 带的那套容错是域映射规则，
 *       放在 DAO 里会让 DAO 认识宗门实体。</li>
 * </ul>
 */
public class SectWarehouseDAO {

    /**
     * {@code sect_warehouse} 的一行（含 JOIN 出来的捐献者名）。
     *
     * <p>缺列时的取值规则与原实现一致：缺 {@code donated_by_player_id} /
     * {@code donated_by_name} 时是 {@code 0} / {@code null}，其余列缺列就让查询失败
     * —— 它们是仓库的主数据，缺了说明库是坏的。</p>
     */
    public record WarehouseItemRow(long id, long sectId, String itemKey, int quantity,
                                   String createdAt, long donatedByPlayerId, String donatedByName) {
    }

    /** 仓库列表（数量 > 0，按入库时间排序）。 */
    public java.util.List<WarehouseItemRow> getWarehouse(long sectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getWarehouse(conn, sectId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门仓库失败", e);
        }
    }

    public java.util.List<WarehouseItemRow> getWarehouse(Connection conn, long sectId) throws SQLException {
        String sql = "SELECT sw.*, p.name AS donated_by_name FROM sect_warehouse sw " +
                "LEFT JOIN players p ON sw.donated_by_player_id = p.id " +
                "WHERE sw.sect_id = ? AND sw.quantity > 0 ORDER BY sw.created_at";
        List<WarehouseItemRow> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readRow(rs));
            }
        }
        return result;
    }

    /**
     * 读一行，列缺失的容错规则与原实现的 {@code mapWarehouseItem} 逐字对应。
     */
    private static WarehouseItemRow readRow(ResultSet rs) throws SQLException {
        long donatedByPlayerId = 0;
        try { donatedByPlayerId = rs.getLong("donated_by_player_id"); } catch (SQLException ignored) {}
        String donatedByName = null;
        try { donatedByName = rs.getString("donated_by_name"); } catch (SQLException ignored) {}
        return new WarehouseItemRow(
                rs.getLong("id"),
                rs.getLong("sect_id"),
                rs.getString("item_key"),
                rs.getInt("quantity"),
                rs.getString("created_at"),
                donatedByPlayerId,
                donatedByName);
    }

    /** 仓库里某个物品的存量；没有这一行返回 0。 */
    public int getWarehouseItemCount(long sectId, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getWarehouseItemCount(conn, sectId, itemKey);
        } catch (SQLException e) {
            throw new RuntimeException("查询仓库物品失败", e);
        }
    }

    public int getWarehouseItemCount(Connection conn, long sectId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM sect_warehouse WHERE sect_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("quantity");
            }
        }
        return 0;
    }

    // ==================== 捐献 / 取出（事务内使用） ====================

    /**
     * 入仓库：没有这一行则插入，有这一行则累加数量并记下最后的捐赠者。
     *
     * <p>双方言分支逐字保留：SQLite 用 {@code ON CONFLICT(sect_id, item_key) DO UPDATE}，
     * MySQL 用 {@code ON DUPLICATE KEY UPDATE}。</p>
     */
    public void upsertWarehouse(Connection conn, long sectId, String itemKey, int quantity, long playerId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(upsertSql())) {
            ps.setLong(1, sectId);
            ps.setString(2, itemKey);
            ps.setInt(3, quantity);
            ps.setLong(4, playerId);
            ps.setInt(5, quantity);
            ps.setLong(6, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 给捐献者加贡献值（仅普通弟子；管理职位不调这个方法）。
     */
    public void addContribution(Connection conn, long sectId, long playerId, long amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 任何成员捐献都小幅增加宗门声望（声望是宗门升级/宗门战的资源）。
     *
     * <p>这条原来没有下限保护（就是 {@code prestige + ?}），且是正数累加，
     * 保持原样 —— 没有把金库域的 {@code MAX(0, ...)} 口径套过来。</p>
     */
    public void addPrestige(Connection conn, long sectId, long amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sects SET prestige = prestige + ? WHERE id = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    /**
     * 条件扣除贡献值（取出时用）。
     *
     * @return true 扣减成功；false 贡献不足（调用方应抛异常让整个事务回滚）
     */
    public boolean deductContributionIfEnough(Connection conn, long memberId, long amount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sect_members SET contribution = contribution - ? WHERE id = ? AND contribution >= ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, memberId);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 条件扣除仓库存量（取出时用）。
     *
     * <p>{@code AND quantity >= ?} 让"够不够"与"扣减"在同一条 UPDATE 里完成：
     * 并发下两个成员同时取同一件物品，只有一个能扣成功，另一个拿到 {@code false}
     * 后由调用方抛异常整体回滚，仓库不会被扣成负数、也不会重复发货。</p>
     *
     * @return true 扣减成功；false 存量不足
     */
    public boolean deductWarehouseIfEnough(Connection conn, long sectId, String itemKey, int quantity)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sect_warehouse SET quantity = quantity - ? WHERE sect_id = ? AND item_key = ? AND quantity >= ?")) {
            ps.setInt(1, quantity);
            ps.setLong(2, sectId);
            ps.setString(3, itemKey);
            ps.setInt(4, quantity);
            return ps.executeUpdate() > 0;
        }
    }

    // ==================== 方言 ====================

    private String upsertSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(sect_id, item_key) DO UPDATE SET quantity = quantity + ?, donated_by_player_id = ?";
        }
        return "INSERT INTO sect_warehouse (sect_id, item_key, quantity, donated_by_player_id) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE quantity = quantity + ?, donated_by_player_id = ?";
    }
}
