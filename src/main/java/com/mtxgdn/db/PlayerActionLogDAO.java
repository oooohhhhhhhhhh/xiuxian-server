package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PlayerActionLogDAO {

    public void insertPlayerActionLog(long userId, String playerName, String action, String detail, String qqNumber) {
        String sql = "INSERT INTO player_action_logs (user_id, player_name, action, detail, qq_number) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setString(2, playerName != null ? playerName : "");
            ps.setString(3, action);
            ps.setString(4, detail != null ? detail : "");
            ps.setString(5, qqNumber != null ? qqNumber : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入玩家操作日志失败", e);
        }
    }

    public List<Map<String, Object>> queryPlayerActionLogs(Long userId, String playerName, String action,
                                                           String qqNumber, String startTime, String endTime,
                                                           int limit, int offset) {
        List<Map<String, Object>> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM player_action_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (playerName != null && !playerName.isBlank()) {
            sql.append(" AND player_name LIKE ?");
            params.add("%" + playerName + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action LIKE ?");
            params.add("%" + action + "%");
        }
        if (qqNumber != null && !qqNumber.isBlank()) {
            sql.append(" AND qq_number = ?");
            params.add(qqNumber);
        }
        if (startTime != null && !startTime.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime);
        }

        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("userId", rs.getLong("user_id"));
                    row.put("playerName", rs.getString("player_name"));
                    row.put("action", rs.getString("action"));
                    row.put("detail", rs.getString("detail"));
                    row.put("qqNumber", rs.getString("qq_number"));
                    row.put("createdAt", rs.getTimestamp("created_at").toString());
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家操作日志失败", e);
        }
        return result;
    }

    public int countPlayerActionLogs(Long userId, String playerName, String action,
                                     String qqNumber, String startTime, String endTime) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM player_action_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (playerName != null && !playerName.isBlank()) {
            sql.append(" AND player_name LIKE ?");
            params.add("%" + playerName + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action LIKE ?");
            params.add("%" + action + "%");
        }
        if (qqNumber != null && !qqNumber.isBlank()) {
            sql.append(" AND qq_number = ?");
            params.add(qqNumber);
        }
        if (startTime != null && !startTime.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startTime);
        }
        if (endTime != null && !endTime.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endTime);
        }

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("统计玩家操作日志失败", e);
        }
        return 0;
    }
}