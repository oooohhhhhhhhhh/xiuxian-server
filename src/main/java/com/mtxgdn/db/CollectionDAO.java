package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 图鉴解锁记录（{@code player_collection}）的数据访问。
 *
 * <p>表结构：{@code UNIQUE (player_id, category, entry_key)}，一行 = 一个玩家解锁了一个条目。
 * 唯一约束是幂等的最终保证 —— 服务层先查再插只是为了少跑一次事务，
 * 即使并发重复解锁，第二次插入也会被数据库挡掉。</p>
 */
public class CollectionDAO {

    /** 集齐某个分类的"伪条目" key（复用同一张表，保证集齐奖励只发一次）。 */
    public static final String COMPLETION_KEY = "__completion__";

    /** 解锁一行；已存在返回 {@code false}（不抛异常）。 */
    public boolean insertUnlock(Connection conn, long playerId, String category, String entryKey,
                                long unlockedAt) throws SQLException {
        if (exists(conn, playerId, category, entryKey)) {
            return false;
        }
        String sql = "INSERT INTO player_collection (player_id, category, entry_key, unlocked_at, reward_granted) "
                + "VALUES (?, ?, ?, ?, 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, category);
            ps.setString(3, entryKey);
            ps.setLong(4, unlockedAt);
            return ps.executeUpdate() > 0;
        }
    }

    /** 该条目是否已解锁（含集齐伪条目）。 */
    public boolean exists(Connection conn, long playerId, String category, String entryKey)
            throws SQLException {
        String sql = "SELECT 1 FROM player_collection WHERE player_id = ? AND category = ? AND entry_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, category);
            ps.setString(3, entryKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 已解锁的真实条目（不含集齐伪条目）。 */
    public Set<String> unlockedKeys(Connection conn, long playerId, String category)
            throws SQLException {
        String sql = "SELECT entry_key FROM player_collection "
                + "WHERE player_id = ? AND category = ? AND entry_key <> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, category);
            ps.setString(3, COMPLETION_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> keys = new LinkedHashSet<>();
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
                return keys;
            }
        }
    }

    /** 已解锁的真实条目数（不含集齐伪条目）。 */
    public int countUnlocked(Connection conn, long playerId, String category) throws SQLException {
        String sql = "SELECT COUNT(*) FROM player_collection "
                + "WHERE player_id = ? AND category = ? AND entry_key <> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, category);
            ps.setString(3, COMPLETION_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 某个玩家某个分类的全部解锁时间戳（key → unlocked_at）。 */
    public java.util.Map<String, Long> unlockedAt(Connection conn, long playerId, String category)
            throws SQLException {
        String sql = "SELECT entry_key, unlocked_at FROM player_collection "
                + "WHERE player_id = ? AND category = ? AND entry_key <> ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, category);
            ps.setString(3, COMPLETION_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                java.util.Map<String, Long> result = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getLong(2));
                }
                return result;
            }
        }
    }

    // ==================== 自取连接版本（服务层禁止自己开连接） ====================

    /** 该条目是否已解锁（自取连接）。 */
    public boolean exists(long playerId, String category, String entryKey) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return exists(conn, playerId, category, entryKey);
        } catch (SQLException e) {
            throw new RuntimeException("查询图鉴条目失败", e);
        }
    }

    /** 已解锁的真实条目 key（自取连接）。 */
    public Set<String> unlockedKeys(long playerId, String category) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return unlockedKeys(conn, playerId, category);
        } catch (SQLException e) {
            throw new RuntimeException("查询图鉴解锁失败", e);
        }
    }

    /** 某分类的解锁快照：条目 → 解锁时间、已解锁数量、是否已发集齐奖励。 */
    public CategorySnapshot readSnapshot(long playerId, String category) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return new CategorySnapshot(
                    unlockedAt(conn, playerId, category),
                    countUnlocked(conn, playerId, category),
                    exists(conn, playerId, category, COMPLETION_KEY));
        } catch (SQLException e) {
            throw new RuntimeException("查询图鉴进度失败", e);
        }
    }

    /** 单个分类的解锁快照。 */
    public record CategorySnapshot(java.util.Map<String, Long> unlockedAt,
                                   int unlockedCount,
                                   boolean completed) {
    }
}
