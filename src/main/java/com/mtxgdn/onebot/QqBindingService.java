package com.mtxgdn.onebot;

import com.mtxgdn.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class QqBindingService {

    public QqBinding findByQq(String qqNumber) {
        String sql = "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE qq_number = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    QqBinding binding = new QqBinding();
                    binding.setId(rs.getLong("id"));
                    binding.setQqNumber(rs.getString("qq_number"));
                    binding.setUserId(rs.getLong("user_id"));
                    binding.setCreatedAt(rs.getString("created_at"));
                    return binding;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询QQ绑定失败", e);
        }
        return null;
    }

    public QqBinding findByUserId(Long userId) {
        String sql = "SELECT id, qq_number, user_id, created_at FROM qq_bindings WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    QqBinding binding = new QqBinding();
                    binding.setId(rs.getLong("id"));
                    binding.setQqNumber(rs.getString("qq_number"));
                    binding.setUserId(rs.getLong("user_id"));
                    binding.setCreatedAt(rs.getString("created_at"));
                    return binding;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询用户绑定失败", e);
        }
        return null;
    }

    public void bind(String qqNumber, Long userId) {
        QqBinding existingByQq = findByQq(qqNumber);
        if (existingByQq != null) {
            throw new RuntimeException("该QQ号已绑定用户: " + existingByQq.getUserId());
        }

        QqBinding existingByUser = findByUserId(userId);
        if (existingByUser != null) {
            throw new RuntimeException("该用户已绑定QQ号: " + existingByUser.getQqNumber());
        }

        String sql = "INSERT INTO qq_bindings (qq_number, user_id) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            stmt.setLong(2, userId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("绑定QQ失败", e);
        }
    }

    public void unbindByQq(String qqNumber) {
        String sql = "DELETE FROM qq_bindings WHERE qq_number = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, qqNumber);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new RuntimeException("该QQ号未绑定任何用户");
            }
        } catch (SQLException e) {
            throw new RuntimeException("解绑QQ失败", e);
        }
    }

    public void unbindByUserId(Long userId) {
        String sql = "DELETE FROM qq_bindings WHERE user_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            int affected = stmt.executeUpdate();
            if (affected == 0) {
                throw new RuntimeException("该用户未绑定任何QQ");
            }
        } catch (SQLException e) {
            throw new RuntimeException("解绑用户失败", e);
        }
    }
}
