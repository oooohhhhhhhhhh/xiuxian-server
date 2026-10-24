package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装备耐久（{@code player_item_durability} 表）的读写入口。
 *
 * <p>从 {@code DurabilityService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询装备耐久失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code DurabilityService} 原来有两条
 * "读耐久 → 写耐久"的链路：{@code repair} 在扣灵石之前要逐件读当前耐久算价格，
 * {@code getDurability} 又被 {@code describeSlot}、{@code isBroken}、
 * {@code getBrokenPenalty} 反复调用。这些读都可能在别的服务的
 * {@code runTransaction} lambda 内被间接触发（例如战斗结算里的装备面板展示）。
 * 抽 DAO 之后若让它们各自自取连接，在 SQLite 下会撞
 * {@code SQLITE_BUSY_SNAPSHOT}，在连接池上会借空后卡满 10 秒。
 * 所以这里必须保留"收调用方连接"的重载。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>{@code getDurability} 在没有记录时返回<b>满耐久</b>
 *       （{@code DurabilityService.getMaxDurability()}），不是 0 —— "从未磨损过"
 *       与"磨光了"必须区分开，否则新装备一上来就是坏的。</li>
 *   <li>{@code getEquippedDurability} 用 {@code LEFT JOIN}：没有耐久行的装备
 *       （从未磨损）同样要出现在结果里，值取满耐久。内连接会把新装备整行吞掉。</li>
 *   <li>磨损用的是"插入时给满耐久 - 消耗、已存在则扣减"的 upsert，
 *       SQLite {@code MAX(0, ...)} / MySQL {@code GREATEST(0, ...)} 保证不扣成负数。</li>
 *   <li>修理写的是绝对值（覆盖成满耐久），双方言下写库方式不同：
 *       SQLite 分支把 {@code max} 作为 UPDATE 的值显式绑定，MySQL 分支靠
 *       {@code VALUES(durability)} 回引插入值 —— 这个差异必须原样保留。</li>
 *   <li>两个写方法都是 {@code addBatch} + {@code executeBatch} 一次性落库：
 *       多件装备要么一起磨损/修好，要么整批失败，不会出现"只修好了一件"。</li>
 * </ul>
 */
public class DurabilityDAO {

    /**
     * 事务内版本：只用调用方的连接读耐久。
     *
     * <p>这个重载是<b>公开 API 的一部分</b>：{@code ItemService.unequipItem}
     * 在 {@code runTransaction} 的 lambda 里调它判断"能不能卸下已损坏的装备"。
     * 抽 DAO 时不能把 {@code Connection} 首参的形状改掉，否则调用方要改代码。</p>
     *
     * @param maxDurability 满耐久值，没有记录时返回它（"从未磨损过" ≠ "磨光了"）
     */
    public int getDurability(Connection conn, long playerId, String itemKey) throws SQLException {
        return getDurability(conn, playerId, itemKey, getMaxDurabilityForCaller());
    }

    /**
     * 单个物品的当前耐久。没有记录 = 从未磨损过 = 满耐久。
     *
     * @param maxDurability 满耐久值（来自 {@code DurabilityService.getMaxDurability()}），
     *                      由调用方传入：DAO 不读配置项
     * @return 当前耐久；没有记录时返回 maxDurability
     */
    public int getDurability(long playerId, String itemKey, int maxDurability) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getDurability(conn, playerId, itemKey, maxDurability);
        } catch (SQLException e) {
            throw new RuntimeException("查询装备耐久失败", e);
        }
    }

    public int getDurability(Connection conn, long playerId, String itemKey, int maxDurability) throws SQLException {
        String sql = "SELECT durability FROM player_item_durability WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("durability");
                }
            }
        }
        return maxDurability;
    }

    /**
     * 装备栏各槽位的当前耐久（slot → durability）。未装备的槽位不出现在结果里。
     *
     * <p>用 {@code LEFT JOIN}：没有耐久行的装备（从未磨损）也要出现，
     * 值取满耐久。</p>
     */
    public Map<String, Integer> getEquippedDurability(long playerId, int maxDurability) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String sql = "SELECT e.slot, e.item_key, d.durability FROM players_equipment e "
                + "LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int dur = rs.getInt("durability");
                    if (rs.wasNull()) {
                        dur = maxDurability;
                    }
                    result.put(rs.getString("slot"), dur);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询装备耐久列表失败", e);
        }
        return result;
    }

    public Map<String, Integer> getEquippedDurability(Connection conn, long playerId, int maxDurability) throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        String sql = "SELECT e.slot, e.item_key, d.durability FROM players_equipment e "
                + "LEFT JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int dur = rs.getInt("durability");
                    if (rs.wasNull()) {
                        dur = maxDurability;
                    }
                    result.put(rs.getString("slot"), dur);
                }
            }
        }
        return result;
    }

    /**
     * 已损坏（耐久 ≤ 0）的装备 item_key 列表。
     *
     * <p>原实现在 {@code getBrokenPenalty} 里一边查这个结果集一边累加
     * {@code BuffEffect} 加成。那段累加要读 {@code ItemRegistry}，
     * 属于业务，所以 DAO 只把"哪些装备坏了"交出去，加成由服务层算。</p>
     */
    public java.util.List<String> findBrokenItemKeys(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findBrokenItemKeys(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("统计损坏装备惩罚失败", e);
        }
    }

    public java.util.List<String> findBrokenItemKeys(Connection conn, long playerId) throws SQLException {
        java.util.List<String> keys = new java.util.ArrayList<>();
        String sql = "SELECT e.item_key, d.durability FROM players_equipment e "
                + "JOIN player_item_durability d ON d.player_id = e.player_id AND d.item_key = e.item_key "
                + "WHERE e.player_id = ? AND d.durability <= 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("item_key"));
                }
            }
        }
        return keys;
    }

    /**
     * 给一批装备扣耐久（下限 0）。
     *
     * <p>upsert 一次写多行：没有记录的装备第一次磨损时按"满耐久 - 消耗"落库，
     * 已有记录的直接扣减。双方言分支（SQLite {@code ON CONFLICT} + {@code MAX}，
     * MySQL {@code ON DUPLICATE KEY UPDATE} + {@code GREATEST}）保证不扣成负数。</p>
     *
     * <p>注意这里<b>不</b>抛异常：磨损失败不该让战斗报错（原实现只打 warn），
     * 由服务层决定容错。</p>
     *
     * @param amount 每件装备的耐久消耗
     * @param maxDurability 满耐久值，用于"第一次磨损"时的落库值
     */
    public void applyWear(long playerId, java.util.Collection<String> itemKeys, int amount, int maxDurability) {
        String sql = wearSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : itemKeys) {
                if (itemKey == null || itemKey.isEmpty()) continue;
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, Math.max(0, maxDurability - amount));
                ps.setInt(4, amount);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("装备磨损结算失败", e);
        }
    }

    public void applyWear(Connection conn, long playerId, java.util.Collection<String> itemKeys,
                          int amount, int maxDurability) throws SQLException {
        String sql = wearSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : itemKeys) {
                if (itemKey == null || itemKey.isEmpty()) continue;
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, Math.max(0, maxDurability - amount));
                ps.setInt(4, amount);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 把一批装备的耐久覆盖成满值（修理）。
     *
     * <p>双方言的写库方式刻意不同：SQLite 分支把 {@code max} 作为 UPDATE 的值
     * 显式绑定，MySQL 分支靠 {@code VALUES(durability)} 回引插入值。
     * 这是原实现的写法，逐字保留。</p>
     *
     * @param itemKeys 要修理的物品 key（调用方已经算过价格并扣完灵石）
     * @param maxDurability 满耐久值，也是修理后的目标值
     */
    public void repairToMax(long playerId, java.util.Collection<String> itemKeys, int maxDurability) {
        String sql = repairSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : itemKeys) {
                if (itemKey == null || itemKey.isEmpty()) continue;
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, maxDurability);
                if (DatabaseManager.isSqlite()) {
                    ps.setInt(4, maxDurability);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("修理装备写库失败", e);
        }
    }

    public void repairToMax(Connection conn, long playerId, java.util.Collection<String> itemKeys,
                            int maxDurability) throws SQLException {
        String sql = repairSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String itemKey : itemKeys) {
                if (itemKey == null || itemKey.isEmpty()) continue;
                ps.setLong(1, playerId);
                ps.setString(2, itemKey);
                ps.setInt(3, maxDurability);
                if (DatabaseManager.isSqlite()) {
                    ps.setInt(4, maxDurability);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 满耐久值的兜底来源。
     *
     * <p>只在"调用方没有显式传满耐久值"的三参重载里用得到。
     * 配置项本身仍归 {@code DurabilityService.getMaxDurability()}，
     * 这里只是让旧的三参调用点不用改代码。</p>
     */
    private static int getMaxDurabilityForCaller() {
        return com.mtxgdn.game.service.DurabilityService.getMaxDurability();
    }

    private String wearSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                    + "ON CONFLICT(player_id, item_key) DO UPDATE SET "
                    + "durability = MAX(0, durability - ?), updated_at = CURRENT_TIMESTAMP";
        }
        return "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "durability = GREATEST(0, durability - ?), updated_at = CURRENT_TIMESTAMP";
    }

    private String repairSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                    + "ON CONFLICT(player_id, item_key) DO UPDATE SET "
                    + "durability = ?, updated_at = CURRENT_TIMESTAMP";
        }
        return "INSERT INTO player_item_durability (player_id, item_key, durability) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE durability = VALUES(durability), updated_at = CURRENT_TIMESTAMP";
    }
}
