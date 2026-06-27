package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class EnergyService {

    /**
     * 获取玩家能量值
     */
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

    /**
     * 设置玩家能量值（覆盖）
     */
    public void setEnergy(long playerId, long energy) {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "INSERT INTO player_energy (player_id, energy) VALUES (?, ?) ON CONFLICT(player_id) DO UPDATE SET energy = excluded.energy";
        } else {
            sql = "INSERT INTO player_energy (player_id, energy) VALUES (?, ?) ON DUPLICATE KEY UPDATE energy = VALUES(energy)";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, energy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("设置能量值失败", e);
        }
    }

    /**
     * 增加玩家能量值
     */
    public void addEnergy(long playerId, long amount) {
        if (amount <= 0) return;
        ensureRowExists(playerId);
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

    /**
     * 减少玩家能量值，返回是否成功（能量不足时返回 false）
     */
    public boolean removeEnergy(long playerId, long amount) {
        if (amount <= 0) return true;
        long current = getEnergy(playerId);
        if (current < amount) {
            return false;
        }
        String sql = "UPDATE player_energy SET energy = energy - ? WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, playerId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("减少能量值失败", e);
        }
    }

    /**
     * 确保 player_energy 表中存在该玩家的记录
     */
    private void ensureRowExists(long playerId) {
        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = "INSERT OR IGNORE INTO player_energy (player_id, energy) VALUES (?, 0)";
        } else {
            sql = "INSERT IGNORE INTO player_energy (player_id, energy) VALUES (?, 0)";
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("初始化能量记录失败", e);
        }
    }
}
