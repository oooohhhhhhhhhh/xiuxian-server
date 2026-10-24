package com.mtxgdn.db;

import com.mtxgdn.game.item.CurrencyEffect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 背包与装备（{@code players_items} / {@code players_equipment}）的读写入口。
 *
 * <p>从 {@code ItemService} 抽出来的第 9 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询背包使用槽位数失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p><b>这里只放 SQL，不放业务</b>：灵石分档换算、合并压缩、装备效果施加、
 * 背包容量判断、{@code PlayerDataService.markDirty} 全都在服务层。
 * 特别是 {@code markDirty} —— 它要发起缓存失效，属于跨模块的副作用，
 * 混进 DAO 会让"写库"和"通知别人"绑死。</p>
 */
public class ItemDAO {

    // ==================== 背包查询 ====================

    /** 背包已用槽位数（quantity > 0 的行数）。 */
    public int getInventoryUsedSlots(long playerId) {
        String sql = "SELECT COUNT(*) FROM players_items WHERE player_id = ? AND quantity > 0";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询背包使用槽位数失败", e);
        }
        return 0;
    }

    public int getInventoryUsedSlots(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players_items WHERE player_id = ? AND quantity > 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /** 单个物品的持有数量；没有该行返回 0。 */
    public long getItemCount(long playerId, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getItemCount(conn, playerId, itemKey);
        } catch (SQLException e) {
            throw new RuntimeException("查询物品数量失败", e);
        }
    }

    public long getItemCount(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return 0;
    }

    /** 背包里全部 quantity > 0 的物品，按 item_key 排序。 */
    public Map<String, Long> getInventoryQuantities(long playerId) {
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0 ORDER BY item_key";
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("item_key"), rs.getLong("quantity"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取背包失败", e);
        }
        return result;
    }

    public Map<String, Long> getInventoryQuantities(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0 ORDER BY item_key";
        Map<String, Long> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("item_key"), rs.getLong("quantity"));
                }
            }
        }
        return result;
    }

    // ==================== 灵石（四档面额） ====================

    /**
     * 折算成"下品灵石等值"的总量。
     *
     * <p>前四档按 {@code 数量 × 汇率} 累加，再加上旧版单一 key 的存量。
     * 汇率与 key 的对应关系在 {@code CurrencyEffect}，DAO 只负责累加。</p>
     */
    public long getTotalSpiritStonesInLowGrade(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getTotalSpiritStonesInLowGrade(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询灵石数量失败", e);
        }
    }

    public long getTotalSpiritStonesInLowGrade(Connection conn, long playerId) throws SQLException {
        long total = 0;
        for (int i = 0; i < 4; i++) {
            String key = CurrencyEffect.getStoneKey(i);
            long count = getItemCount(conn, playerId, key);
            total += count * CurrencyEffect.getExchangeRate(i);
        }
        long oldCount = getItemCount(conn, playerId, CurrencyEffect.SPIRIT_STONE_OLD_KEY);
        total += oldCount;
        return total;
    }

    // ==================== 背包写入 ====================

    /**
     * 添加物品（upsert：存在则累加，不存在则插入）。
     *
     * @return 是否写到了行
     */
    public boolean addItem(long playerId, String itemKey, long quantity) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return addItem(conn, playerId, itemKey, quantity);
        } catch (SQLException e) {
            throw new RuntimeException("添加物品失败", e);
        }
    }

    public boolean addItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON CONFLICT(player_id, item_key) DO UPDATE SET quantity = quantity + excluded.quantity,
                    updated_at = CURRENT_TIMESTAMP
                """;
        } else {
            sql = """
                INSERT INTO players_items (player_id, item_key, quantity)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity),
                    updated_at = CURRENT_TIMESTAMP
                """;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.setLong(3, quantity);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 原子移除物品：单条 SQL 同时完成"检查数量"与"扣除"，消除 TOCTOU 竞态。
     *
     * @return true 扣除成功；false 数量不足或物品不存在
     */
    public boolean removeItem(long playerId, String itemKey, long quantity) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return removeItem(conn, playerId, itemKey, quantity);
        } catch (SQLException e) {
            throw new RuntimeException("移除物品失败", e);
        }
    }

    public boolean removeItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        // 原子操作：一条 SQL 同时完成检查与扣除
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE player_id = ? AND item_key = ? AND quantity >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.setLong(4, quantity);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                // 扣除成功后清理零数量行
                cleanZeroQuantity(conn, playerId, itemKey);
                return true;
            }
            return false;
        }
    }

    private void cleanZeroQuantity(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            ps.executeUpdate();
        }
    }

    // ==================== 装备 ====================

    /** 某个槽位当前装备的 item_key；未装备返回 null。 */
    public String getEquippedItem(long playerId, String slot) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getEquippedItem(conn, playerId, slot);
        } catch (SQLException e) {
            throw new RuntimeException("查询装备失败", e);
        }
    }

    public String getEquippedItem(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("item_key");
                }
            }
        }
        return null;
    }

    /** 全部装备：slot -> item_key。 */
    public Map<String, String> getEquipment(long playerId) {
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取装备列表失败", e);
        }
        return result;
    }

    public Map<String, String> getEquipment(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        }
        return result;
    }

    /** 写入/替换某个槽位的装备（upsert）。 */
    public void upsertEquipment(long playerId, String slot, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            upsertEquipment(conn, playerId, slot, itemKey);
        } catch (SQLException e) {
            throw new RuntimeException("装备物品失败", e);
        }
    }

    public void upsertEquipment(Connection conn, long playerId, String slot, String itemKey) throws SQLException {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = """
                INSERT INTO players_equipment (player_id, slot, item_key)
                VALUES (?, ?, ?)
                ON CONFLICT(player_id, slot) DO UPDATE SET item_key = excluded.item_key,
                    updated_at = CURRENT_TIMESTAMP
                """;
        } else {
            sql = """
                INSERT INTO players_equipment (player_id, slot, item_key)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE item_key = VALUES(item_key),
                    updated_at = CURRENT_TIMESTAMP
                """;
        }
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.setString(3, itemKey);
            ps.executeUpdate();
        }
    }

    /** 卸下：删掉某个槽位的装备记录。 */
    public void deleteEquipment(long playerId, String slot) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteEquipment(conn, playerId, slot);
        } catch (SQLException e) {
            throw new RuntimeException("卸下装备失败", e);
        }
    }

    public void deleteEquipment(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }
    }

    // ==================== 使用物品时的行锁 ====================

    /**
     * 锁定并读取某行物品数量。
     *
     * <p>SQLite 的 {@code FOR UPDATE} 子句由 {@code DatabaseDialect} 提供
     * （SQLite 下退化成不带锁的读，靠 WAL + 单写者保证）；
     * MySQL 下是真正的 {@code SELECT ... FOR UPDATE}。</p>
     *
     * @return 数量；没有该行返回 -1（调用方据此判断"没有这个物品"）
     */
    public long lockAndGetQuantity(long playerId, String itemKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return lockAndGetQuantity(conn, playerId, itemKey);
        } catch (SQLException e) {
            throw new RuntimeException("锁定物品失败", e);
        }
    }

    public long lockAndGetQuantity(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? " +
                DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return -1;
    }
}
