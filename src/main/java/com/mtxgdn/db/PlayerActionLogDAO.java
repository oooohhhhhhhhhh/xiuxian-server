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

    /**
     * 删除早于指定天数的操作日志。
     *
     * <p><b>为什么需要它</b>：{@code player_action_logs} 只增不删 —— 每条指令、每次登录、
     * 每次管理操作都会写一行，而全代码库没有任何清理逻辑。跑上几个月就是几十万行，
     * 管理后台的日志翻页越来越慢，备份文件也越来越大，且永远没有任何回收途径。</p>
     *
     * <p>这里按"保留最近 N 天"做定期清理。{@code retentionDays <= 0} 表示不清理
     * （保留全部），便于需要长期审计的部署关掉该功能。</p>
     *
     * @param retentionDays 保留天数；&le;0 时直接返回 0
     * @return 删除的行数
     */
    public int purgeOlderThanDays(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        long cutoffMillis = System.currentTimeMillis() - retentionDays * 24L * 3600 * 1000;
        // created_at 在不同方言下分别是 DATETIME / TEXT，直接按毫秒数与字符串比较都不可靠，
        // 因此用方言无关的"按时间戳列自身格式"来比较：
        // MySQL 的 DATETIME 与 SQLite 的 TEXT 都接受 'YYYY-MM-DD HH:MM:SS' 字面量。
        String cutoff = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(cutoffMillis));
        String sql = "DELETE FROM player_action_logs WHERE created_at < ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清理玩家操作日志失败", e);
        }
    }

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