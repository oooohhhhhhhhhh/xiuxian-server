package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 宗门金库与工资（{@code sects} 表上的 treasury / salary / prestige 列）的读写入口。
 *
 * <p>从 {@code SectTreasuryDomain} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"更新宗门声望失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：这个域在拆分前就<b>没有</b>
 * {@code runTransaction} —— 五条语句每条都是"单语句即提交"，靠
 * {@code WHERE ... >= ?} 的条件更新和"扣了灵石却没进金库就退回去"的补偿来保证一致性。
 * 所以这里没有任何一个方法<b>必须</b>在事务里跑。但重载仍然要提供，原因有两个：
 * 一是这个域与仓库域、继位域共用同一张 {@code sects} 表，别的域的事务块
 * （如仓库捐献里加声望、继位时清竞争期字段）随时可能想复用同一条语句，
 * 那时只能传 {@code conn}，自己开连接会撞 {@code SQLITE_BUSY_SNAPSHOT}；
 * 二是与原实现的连接语义对齐：原来的每条语句都是独立短连接，现在自取连接版
 * 必须保持"一次方法调用 = 一条短连接"，不能合并。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>声望下限 0 的双方言分支</b>：SQLite 的标量取大是 {@code MAX(0, ...)}，
 *       MySQL 是 {@code GREATEST(0, ...)}，两者不通用。原实现在服务层拼这个表达式，
 *       现在搬进 {@link #prestigeClamp()}，一句都没改。</li>
 *   <li><b>条件扣减</b>：{@code deductTreasuryIfEnough} 的
 *       {@code AND treasury >= ?} 让"余额够不够"与"扣减"在一条 UPDATE 里完成。
 *       并发下（自动工资扫描与宗主手动发放可能同时跑）这保证不会扣成负数，
 *       也不会两个任务各发一次。返回值 {@code false} 表示"被并发抢先扣过了"，
 *       调用方据此整轮放弃 —— 这个布尔返回值就是原实现的
 *       {@code ps.executeUpdate() == 0} 判断。</li>
 *   <li><b>发薪时间戳由调用方给</b>：{@code setSalaryTier} 的
 *       {@code CASE WHEN last_salary_time = 0 AND ? > 0 THEN ? ELSE last_salary_time END}
 *       与 {@code markSalaryPaid} 的 {@code last_salary_time = ?} 都把"现在"作为参数
 *       收下，DAO 不自己调 {@code System.currentTimeMillis()}。原实现是在绑定参数时
 *       取值的，保持"取值的时机归调用方"才不会让一次重试里的时间戳悄悄变化。</li>
 *   <li><b>从"未开启"变为"开启"才开始计时</b>：{@code last_salary_time = 0} 表示档位刚设、
 *       还没开始计时，此时 {@code CASE} 把起始时间设成"现在"；若它非 0 则原样保留。
 *       这条口径写在 SQL 里，抽 DAO 时一个字都没动。</li>
 *   <li><b>三处失败都不该让调用方炸</b>：原实现的 {@code addPrestige} /
 *       {@code markSalaryPaid} 只打 warn 不抛，{@code addTreasury} /
 *       {@code setSalaryTier} / {@code deductTreasuryIfEnough} 打 error 后返回失败结果。
 *       DAO 这边统一抛 {@link RuntimeException}（否则调用方无法区分"没写进去"与
 *       "写成功了"），吞异常、退灵石、返回失败文案这些都留在服务层。</li>
 * </ul>
 */
public class SectTreasuryDAO {

    // ==================== 声望 ====================

    /**
     * 增减宗门声望（正数增加、负数减少，下限 0）。
     *
     * <p>{@code clamp} 的双方言分支逐字保留：SQLite 用 {@code MAX(0, ...)}，
     * MySQL 用 {@code GREATEST(0, ...)}。</p>
     */
    public void addPrestige(long sectId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addPrestige(conn, sectId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("更新宗门声望失败", e);
        }
    }

    public void addPrestige(Connection conn, long sectId, long amount) throws SQLException {
        String sql = "UPDATE sects SET prestige = " + prestigeClamp()
                + ", updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 金库 ====================

    /** 金库入账（只加不减，扣减走 {@link #deductTreasuryIfEnough} 的条件更新）。 */
    public void addTreasury(long sectId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addTreasury(conn, sectId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("宗门金库入账失败", e);
        }
    }

    public void addTreasury(Connection conn, long sectId, long amount) throws SQLException {
        String sql = "UPDATE sects SET treasury = treasury + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 工资 ====================

    /**
     * 设定工资档位；{@code perMember = 0} 等于关闭工资。
     *
     * <p>只在"从未开启 → 开启"这一刻把 {@code last_salary_time} 设成 {@code now}，
     * 之后每次设定都保留原来的计时基点。</p>
     *
     * @param now 调用方的"现在"（毫秒时间戳）
     */
    public void setSalaryTier(long sectId, long perMember, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            setSalaryTier(conn, sectId, perMember, now);
        } catch (SQLException e) {
            throw new RuntimeException("设定宗门工资失败", e);
        }
    }

    public void setSalaryTier(Connection conn, long sectId, long perMember, long now) throws SQLException {
        String sql = "UPDATE sects SET salary_per_member = ?, "
                + "last_salary_time = CASE WHEN last_salary_time = 0 AND ? > 0 THEN ? ELSE last_salary_time END, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, perMember);
            ps.setLong(2, perMember);
            ps.setLong(3, now);
            ps.setLong(4, sectId);
            ps.executeUpdate();
        }
    }

    /**
     * 条件扣减金库并推进工资结算时间。
     *
     * <p>{@code AND treasury >= ?} 让"余额够不够"和"扣减 + 记时"在一条 UPDATE 里完成：
     * 并发下（自动扫描与宗主手动发放）只有一个任务能扣成功，另一个拿到 {@code false}
     * 后整轮放弃，等下一个周期。改成先 SELECT 再 UPDATE 就会两个任务各发一次工资。</p>
     *
     * @param now 本次结算时间（毫秒时间戳），由调用方给定
     * @return true 扣减成功；false 余额不足或已被并发抢先扣过
     */
    public boolean deductTreasuryIfEnough(long sectId, long amount, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return deductTreasuryIfEnough(conn, sectId, amount, now);
        } catch (SQLException e) {
            throw new RuntimeException("宗门工资扣减金库失败", e);
        }
    }

    public boolean deductTreasuryIfEnough(Connection conn, long sectId, long amount, long now) throws SQLException {
        String sql = "UPDATE sects SET treasury = treasury - ?, last_salary_time = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND treasury >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, now);
            ps.setLong(3, sectId);
            ps.setLong(4, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /** 只推进工资结算时间（金库为 0、发不出钱时也要推进，避免每次扫描都重复尝试）。 */
    public void markSalaryPaid(long sectId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            markSalaryPaid(conn, sectId, now);
        } catch (SQLException e) {
            throw new RuntimeException("更新宗门工资结算时间失败", e);
        }
    }

    public void markSalaryPaid(Connection conn, long sectId, long now) throws SQLException {
        String sql = "UPDATE sects SET last_salary_time = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 方言 ====================

    /**
     * 声望下限 0 的取大表达式。
     *
     * <p>SQLite 的标量取大是 {@code MAX(a,b)}，MySQL 是 {@code GREATEST(a,b)} ——
     * 两者不通用，这是原实现就有的分支，原样搬过来。</p>
     */
    private String prestigeClamp() {
        return DatabaseManager.isSqlite() ? "MAX(0, prestige + ?)" : "GREATEST(0, prestige + ?)";
    }
}
