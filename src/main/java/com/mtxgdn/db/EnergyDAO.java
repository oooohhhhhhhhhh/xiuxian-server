package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 玩家能量（{@code player_energy} 表）的读写入口。
 *
 * <p>从 {@code EnergyService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询能量值失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code EnergyService}，包括 upsert 的两套方言分支
 * （SQLite {@code ON CONFLICT} / {@code INSERT OR IGNORE}，
 * MySQL {@code ON DUPLICATE KEY UPDATE} / {@code INSERT IGNORE}）。</p>
 */
public class EnergyDAO {

    /** 玩家能量值；没有记录时返回 0。 */
    public long getEnergy(long playerId) {
        String sql = "SELECT energy FROM player_energy WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("energy");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询能量值失败", e);
        }
        return 0;
    }

    public long getEnergy(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT energy FROM player_energy WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("energy");
                }
            }
        }
        return 0;
    }

    /** 覆盖式设置能量值（upsert：没有记录就插入）。 */
    public void setEnergy(long playerId, long energy) {
        String sql = upsertSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, energy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("设置能量值失败", e);
        }
    }

    public void setEnergy(Connection conn, long playerId, long energy) throws SQLException {
        String sql = upsertSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, energy);
            ps.executeUpdate();
        }
    }

    /** 原子增加能量值。 */
    public void addEnergy(long playerId, long amount) {
        String sql = "UPDATE player_energy SET energy = energy + ? WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加能量值失败", e);
        }
    }

    public void addEnergy(Connection conn, long playerId, long amount) throws SQLException {
        String sql = "UPDATE player_energy SET energy = energy + ? WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /**
     * 条件扣减能量值。
     *
     * <p>{@code AND energy >= ?} 让"检查"和"扣减"在一条 UPDATE 里完成，
     * 并发下不会扣成负数。改成先 SELECT 再 UPDATE 就会输掉竞态。</p>
     *
     * @return true 扣减成功；false 能量不足
     */
    public boolean removeEnergy(long playerId, long amount) {
        String sql = "UPDATE player_energy SET energy = energy - ? WHERE player_id = ? AND energy >= ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("减少能量值失败", e);
        }
    }

    public boolean removeEnergy(Connection conn, long playerId, long amount) throws SQLException {
        String sql = "UPDATE player_energy SET energy = energy - ? WHERE player_id = ? AND energy >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.setLong(3, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /** 确保记录存在（{@code INSERT IGNORE}，已有行不动）。 */
    public void ensureRowExists(long playerId) {
        String sql = ensureRowSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("初始化能量记录失败", e);
        }
    }

    public void ensureRowExists(Connection conn, long playerId) throws SQLException {
        String sql = ensureRowSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    private String upsertSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_energy (player_id, energy) VALUES (?, ?) ON CONFLICT(player_id) DO UPDATE SET energy = excluded.energy";
        }
        return "INSERT INTO player_energy (player_id, energy) VALUES (?, ?) ON DUPLICATE KEY UPDATE energy = VALUES(energy)";
    }

    private String ensureRowSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT OR IGNORE INTO player_energy (player_id, energy) VALUES (?, 0)";
        }
        return "INSERT IGNORE INTO player_energy (player_id, energy) VALUES (?, 0)";
    }
}
