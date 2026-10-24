package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * 宗门战（{@code sect_wars} 表与 {@code sects.prestige} 列）的读写入口。
 *
 * <p>从 {@code SectWarDomain} 抽出来的 DAO。每个方法一对重载：
 * {@code xxx(...)} 自己开一条短连接并把 {@link SQLException} 包成
 * {@link RuntimeException}；{@code xxx(Connection conn, ...)} 只用调用方的连接、
 * 直接抛 {@link SQLException}。</p>
 *
 * <p><b>为什么这里必须有 Connection 重载</b>：宣战的"扣灵石 + 扣声望"<b>原本就是一个
 * {@code runTransaction}</b>，其中扣声望这一步要和其他步骤共用同一条 {@code conn}
 * —— 若改成自取连接，SQLite 的 {@code BEGIN DEFERRED} 已持写锁时会直接
 * {@code SQLITE_BUSY_SNAPSHOT}，而且"灵石扣了、声望没扣"这类中间态无法回滚。
 * 所以 {@link #deductPrestige(Connection, long, long)} 专供事务内使用。
 * 反过来，声望结算（赢家加、输家扣）与战报写入在拆分前就<b>不在</b>事务内，
 * 各自是一条独立短连接 —— 这里保持同样的形态，只提供自取连接版，
 * 不额外提供 {@code conn} 重载：给了就会诱导调用方把它们并进事务，那是行为改动。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>输家声望下限 0 的双方言分支</b>：SQLite 用 {@code MAX(0, ...)}，
 *       MySQL 用 {@code GREATEST(0, ...)}。注意它与金库域的
 *       {@code SectTreasuryDAO} 是同一口径，但两个域在拆分前各自拼了一份，
 *       这里同样各留一份 —— 合并成一个公共常量属于"顺手重构"，
 *       会让两处的失败语义（一个抛异常、一个只打日志）更难对齐。</li>
 *   <li><b>赢家声望没有下限保护</b>：{@code rewardSql} 就是 {@code prestige + ?}
 *       且奖励恒为正数，保持原样。</li>
 *   <li><b>赢家加与输家扣共用同一条连接</b>：原实现把两条 UPDATE 放进同一个
 *       {@code try (Connection conn = ...)} 块里，所以这里用
 *       {@link #settleWarPrestige} 把它们合成一次方法调用，而不是各开一条连接
 *       —— 分两次开连接会让"加成功、扣失败"变成可能。</li>
 *   <li><b>冷却是读 {@code MAX(created_at)}</b>：不是"最近一条战报"而是"所有战报里
 *       最新的创建时间"，因此返回值可能是 {@code null}（从未打过）。DAO 直接交出
 *       {@link Timestamp}（或 {@code null}），"还剩多少小时、是否要拒绝宣战"
 *       的判断留在服务层。</li>
 *   <li><b>战报表的列序</b>：{@code prestige_stake} 存的是赢家奖励
 *       {@code Sect.WAR_WIN_PRESTIGE}（即双方押上的声望），不是别的值。</li>
 * </ul>
 */
public class SectWarDAO {

    // ==================== 查询 ====================

    /**
     * 同一防守方最近一次被宣战的时间；从未打过返回 {@code null}。
     *
     * <p>读的是 {@code MAX(created_at)}，所以"发起但中途失败"的记录也会算进去 ——
     * 这与拆分前一致：只要插过一条 {@code sect_wars} 行就进入冷却。</p>
     */
    public Timestamp lastWarTime(long defenderSectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return lastWarTime(conn, defenderSectId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门战记录失败", e);
        }
    }

    public Timestamp lastWarTime(Connection conn, long defenderSectId) throws SQLException {
        String sql = "SELECT MAX(created_at) FROM sect_wars WHERE defender_sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, defenderSectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getTimestamp(1);
                }
            }
        }
        return null;
    }

    // ==================== 宣战（事务内使用） ====================

    /**
     * 宣战时扣发起方声望（无下限保护：发起前已校验过声望足够）。
     *
     * <p>只有 {@code conn} 版：它只可能在"扣灵石 + 扣声望"的事务块里被调用。</p>
     */
    public void deductPrestige(Connection conn, long sectId, long amount) throws SQLException {
        String sql = "UPDATE sects SET prestige = prestige - ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 结算（刻意独立于事务） ====================

    /**
     * 发放宗门战声望：赢家加、输家扣（下限 0）。
     *
     * <p>两条 UPDATE 共享同一条连接，与原实现一致。</p>
     *
     * @param winnerSectId 胜者宗门 id
     * @param loserSectId  败者宗门 id
     * @param prestige     双方押上的声望（胜者加这么多，败者扣这么多）
     */
    public void settleWarPrestige(long winnerSectId, long loserSectId, long prestige) {
        try (Connection conn = DatabaseManager.getConnection()) {
            settleWarPrestige(conn, winnerSectId, loserSectId, prestige);
        } catch (SQLException e) {
            throw new RuntimeException("发放宗门战奖励失败", e);
        }
    }

    public void settleWarPrestige(Connection conn, long winnerSectId, long loserSectId, long prestige)
            throws SQLException {
        String rewardSql = "UPDATE sects SET prestige = prestige + ? WHERE id = ?";
        String loseSql = "UPDATE sects SET prestige = " + prestigeClamp() + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(rewardSql)) {
            ps.setLong(1, prestige);
            ps.setLong(2, winnerSectId);
            ps.executeUpdate();
        }
        // 失败方扣声望
        try (PreparedStatement ps = conn.prepareStatement(loseSql)) {
            ps.setLong(1, prestige);
            ps.setLong(2, loserSectId);
            ps.executeUpdate();
        }
    }

    /**
     * 写入战报。
     *
     * <p>刻意是自取连接的短连接：原实现里它跟在声望结算之后、
     * 不在任何事务内。战报写失败会让整场战斗的结果丢失，所以异常直接往外抛。</p>
     */
    public void saveWarLog(long attackerSectId, long defenderSectId, long winnerSectId,
                           int attackerWins, int defenderWins, long prestigeStake, String battleLog) {
        try (Connection conn = DatabaseManager.getConnection()) {
            saveWarLog(conn, attackerSectId, defenderSectId, winnerSectId,
                    attackerWins, defenderWins, prestigeStake, battleLog);
        } catch (SQLException e) {
            throw new RuntimeException("保存宗门战记录失败", e);
        }
    }

    public void saveWarLog(Connection conn, long attackerSectId, long defenderSectId, long winnerSectId,
                           int attackerWins, int defenderWins, long prestigeStake, String battleLog)
            throws SQLException {
        String sql = "INSERT INTO sect_wars (attacker_sect_id, defender_sect_id, winner_sect_id, "
                + "attacker_wins, defender_wins, prestige_stake, battle_log) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, attackerSectId);
            ps.setLong(2, defenderSectId);
            ps.setLong(3, winnerSectId);
            ps.setInt(4, attackerWins);
            ps.setInt(5, defenderWins);
            ps.setLong(6, prestigeStake);
            ps.setString(7, battleLog);
            ps.executeUpdate();
        }
    }

    // ==================== 方言 ====================

    /**
     * 声望下限 0 的取大表达式。
     *
     * <p>SQLite 是 {@code MAX(0, ...)}，MySQL 是 {@code GREATEST(0, ...)} ——
     * 两者不通用，这是原实现就有的分支，原样搬过来。</p>
     */
    private String prestigeClamp() {
        return DatabaseManager.isSqlite() ? "MAX(0, prestige - ?)" : "GREATEST(0, prestige - ?)";
    }
}
