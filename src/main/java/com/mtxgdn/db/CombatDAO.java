package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 玩家每日计数（{@code player_daily} 表）的读写入口。
 *
 * <p>从 {@code CombatService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：PVP 次数是"先查再递增"的两步，
 * 原实现把它们写成两个 static 方法，各自自取连接。这意味着"查出来还有次数"
 * 与"把次数 +1"落在两次独立提交上 —— 两个并发请求可以同时看到第 9 次，
 * 然后双双写入第 10 次，每日限制就被绕过了一次。{@code incrementDailyPvpCount}
 * 本身用 upsert 的 {@code CASE WHEN last_daily_reset = ?} 把"跨天重置"和
 * "+1"压在一条语句里，这部分是安全的；但调用方（战斗结算）如果要把它和
 * 别的写操作放进同一个事务，就必须有 Connection 重载。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>两个方法都<b>不抛异常</b>：原实现在 catch 里只往 {@code System.err}
 *       打一行然后降级（读返回 0，写什么都不做）。理由是"表或列可能不存在
 *       （升级前）"，让一次 PVP 因为计数失败而整个失败是不可接受的。
 *       所以自取连接的重载也是吞掉异常后降级，而不是包成 RuntimeException
 *       往上抛 —— 抛出去会改变调用方的失败模式。</li>
 *   <li>{@code incrementDailyPvpCount} 的 upsert 有两个方言分支：
 *       SQLite 用 {@code ON CONFLICT(player_id)} + {@code CASE WHEN}，
 *       MySQL 用 {@code ON DUPLICATE KEY UPDATE} + {@code IF(...)}。
 *       两边的"跨天则重置为 1、否则 +1"语义必须逐字保留。</li>
 *   <li>查询的 WHERE 带 {@code last_daily_reset = ?}（今天）：
 *       跨天的旧行不会被读到，读出来自然是 0。</li>
 *   <li>{@code ensureDailyReset} 不在 DAO 里 —— 它是
 *       {@code DailyService} 的跨模块调用，属于编排，留在服务层。</li>
 * </ul>
 */
public class CombatDAO {

    /**
     * 读取玩家今日 PVP 次数（跨天自动清零）。
     *
     * <p>失败时降级为 0（原实现只打 stderr）—— 表或列在升级前可能不存在，
     * 不能因为计数读不到就让玩家打不了 PVP。</p>
     */
    public int getDailyPvpCount(long playerId, String today) {
        String sql = "SELECT pvp_count FROM player_daily WHERE player_id = ? AND last_daily_reset = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("pvp_count");
                }
            }
        } catch (SQLException e) {
            System.err.println("[Combat] 读取PVP次数失败: " + e.getMessage());
        }
        return 0;
    }

    public int getDailyPvpCount(Connection conn, long playerId, String today) throws SQLException {
        String sql = "SELECT pvp_count FROM player_daily WHERE player_id = ? AND last_daily_reset = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("pvp_count");
                }
            }
        }
        return 0;
    }

    /**
     * 原子递增玩家今日 PVP 次数（跨天自动重置为 1）。
     *
     * <p>"跨天重置"和"+1"压在一条 upsert 里：拆成先 UPDATE 再 INSERT 就会在
     * 跨天那一刻出现两行，或把次数重置丢回旧值。</p>
     *
     * <p>失败时什么都不做（原实现只打 stderr）。</p>
     */
    public void incrementDailyPvpCount(long playerId, String today) {
        String sql = incrementSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setString(3, today);
            ps.setString(4, today);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[Combat] 记录PVP次数失败: " + e.getMessage());
        }
    }

    public void incrementDailyPvpCount(Connection conn, long playerId, String today) throws SQLException {
        String sql = incrementSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setString(3, today);
            ps.setString(4, today);
            ps.executeUpdate();
        }
    }

    /** 双方言的 upsert：SQLite 用 {@code CASE WHEN}，MySQL 用 {@code IF}。 */
    private String incrementSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_daily (player_id, pvp_count, last_daily_reset) VALUES (?, 1, ?) " +
                    "ON CONFLICT(player_id) DO UPDATE SET " +
                    "pvp_count = CASE WHEN last_daily_reset = ? THEN pvp_count + 1 ELSE 1 END, " +
                    "last_daily_reset = ?";
        }
        return "INSERT INTO player_daily (player_id, pvp_count, last_daily_reset) VALUES (?, 1, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "pvp_count = IF(last_daily_reset = ?, pvp_count + 1, 1), " +
                "last_daily_reset = ?";
    }
}
