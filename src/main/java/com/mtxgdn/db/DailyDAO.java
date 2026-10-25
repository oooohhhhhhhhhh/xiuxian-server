package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 玩家每日签到与日常（{@code player_daily} 表）的读写入口。
 *
 * <p>从 {@link DailyService} 抽出来的 DAO。每个"查询类"意图一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询每日数据失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>哪些方法只有 Connection 版</b>：{@link #recordMorningCultivation} 只在
 * {@code runTransaction} 的 lambda 内被调用 —— 它与
 * {@code playerService.addExperience(conn, ...)}、
 * {@code itemService.addSpiritStones(conn, ...)} 三条语句必须同生共死。
 * 若签到行落库而经验/灵石没有落库（或反过来），玩家就白签一次到；
 * 反之发了两份奖励却没记 {@code last_morning_cultivation}，第二天还能再领一次。
 * 自取连接版在这里只会诱导调用方在事务外用它，所以<b>刻意不提供</b>。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>（每日签到是幂等判定 + 奖励发放，
 * 任何一处 SQL 语义变化都会造成重复领奖或漏发）：</p>
 * <ul>
 *   <li><b>"今天是否已签到"的判定是一个字符都没动的字符串相等</b>：
 *       服务层读出来的 {@code last_morning_cultivation} 是
 *       {@code VARCHAR(16)}，与 {@code LocalDate.now().toString()}（形如
 *       {@code 2026-03-15}，长度 10）逐字比较。这里既不改成 {@code date} 类型比较，
 *       也不加时区换算 —— 原实现就是这么比的，改成类型比较会让
 *       "库里存了空串/NULL 的老行"的判定结果变化。</li>
 *   <li><b>签到的 upsert 没有 WHERE 条件</b>：防重复领奖<b>不</b>靠 SQL 的
 *       {@code WHERE}，而是靠服务层在读出行之后的那次
 *       {@code daily.lastMorningCultivation.equals(todayStr)} 提前返回。
 *       upsert 只负责"写入本次签到的日期 + 连签天数 + 总天数 +1"，
 *       所以 {@code ON CONFLICT(player_id)} / {@code ON DUPLICATE KEY UPDATE}
 *       两个分支都必须逐字保留，包括
 *       {@code total_active_days = total_active_days + 1} 这个累加式
 *       （写成 {@code excluded.total_active_days} 会把天数覆盖成 1，
 *       灵根共鸣的 7 天 / 30 天里程碑就永远触发不了）。</li>
 *   <li><b>{@code total_active_days} 的初值是字面量 1，不是绑定参数</b>：
 *       {@code VALUES (?, ?, ?, 1)} 里的 {@code 1} 与原实现一致，
 *       没有改成 {@code total_active_days + 1} 之外的任何形式。</li>
 *   <li><b>连签天数是调用方算好传进来的</b>：{@code consecutive} 的
 *       "昨天签过就 +1、否则重新从 1 开始"留在服务层，DAO 只把它当
 *       {@code setInt(3, ...)} 收下，不自己读日期、不自己比较。</li>
 *   <li><b>跨日复位的 WHERE 一个字符都不能少</b>：
 *       {@code WHERE player_id = ? AND (last_daily_reset IS NULL OR last_daily_reset <> ?)}
 *       保证"已经复位过今天"的行不被反复 UPDATE。少了 {@code IS NULL} 分支，
 *       第一次建行（{@code last_daily_reset} 走 DEFAULT (CURRENT_DATE)、
 *       但在老库里可能是 NULL）的行就永远复位不了；少了 {@code <> ?}，
 *       每次调用都会把四个计数和奖励标记再归零一次，玩家当天的进度会凭空消失。</li>
 *   <li><b>复位的日期由调用方传进来</b>：原实现在方法体里调
 *       {@code LocalDate.now().toString()}，DAO 不自己调 {@code LocalDate.now()} ——
 *       "现在"归调用方，DAO 只做参数的搬运工。</li>
 *   <li><b>计数累加的 upsert 把"跨日归 1"和"+1"压在同一条语句里</b>：
 *       {@code ON CONFLICT(player_id) DO UPDATE SET count = count + 1, last_daily_reset = CURRENT_DATE}。
 *       注意这里的 {@code last_daily_reset = CURRENT_DATE} 是<b>数据库侧</b>取值，
 *       不是绑定参数 —— 原实现如此，抽 DAO 时没有改成传参
 *       （改了会让 SQLite 与 MySQL 的 CURRENT_DATE 语义漂移）。</li>
 *   <li><b>机缘奖励标记是裸列名拼接</b>：{@link #markFortuneClaimed} 的
 *       {@code "UPDATE player_daily SET " + column + " = 1 WHERE player_id = ?"}
 *       把列名拼进 SQL。列名来自服务层的字面量
 *       （{@code exploration_rewarded} 等四个 + 终身的
 *       {@code resonance7_awarded} / {@code resonance30_awarded}），
 *       不是外部输入，所以保留原样；调用方传进来的列名不受信任时不该用这个方法。</li>
 *   <li><b>{@code getDaily()} 返回的是"整行映射"</b>：
 *       列名逐个 {@code rs.getString}/{@code rs.getInt} 读，与原实现的
 *       {@code mapDaily} 逐列一致；{@code xxx_rewarded} 三列一律
 *       {@code == 1} 判布尔（库里是 {@code INT DEFAULT 0}，不是 BOOLEAN）。</li>
 *   <li><b>共鸣里程碑不进 DAO</b>：{@code resonance7_awarded} /
 *       {@code resonance30_awarded} 只在 {@link #markFortuneClaimed} 里被写，
 *       "7 天 / 30 天"的门槛判断与六维加成留在服务层。它们是终身一次性标记，
 *       <b>不在</b> {@link #resetDaily} 的复位范围内 —— 这一点写在这里是因为
 *       复位语句的列清单就是据此定的，加列时不要顺手把它们扫进去。</li>
 *   <li><b>DAO 不碰事务、不依赖服务层</b>：没有 {@code setAutoCommit(} /
 *       {@code commit(} / {@code rollback(} / {@code runTransaction(}；
 *       校验、国际化、奖励计算、调 {@code PlayerService} / {@code ItemService}
 *       全都留在服务层。</li>
 * </ul>
 */
public class DailyDAO {

    // ==================== 查询 ====================

    /**
     * 读取某玩家的每日行；不存在返回 {@code null}。
     *
     * <p>原实现是 {@code SELECT * FROM player_daily WHERE player_id = ?}，
     * {@code rs.next()} 为假就走"插入一行再返回空对象"的分支。
     * "不存在则建行"这一步留在服务层（它和读是两个语义，见
     * {@link #insertDaily(Connection, long)}），这里只负责"读"。</p>
     */
    public PlayerDaily getDaily(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getDaily(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询每日数据失败", e);
        }
    }

    public PlayerDaily getDaily(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM player_daily WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapDaily(rs);
                }
            }
        }
        return null;
    }

    // ==================== 写入 ====================

    /**
     * 写入本次早间修行的签到行（upsert：没有记录就插入）。
     *
     * <p><b>只有 Connection 版</b>：它与加经验、加灵石必须落在同一条连接、
     * 同一个事务里，理由见类注释。</p>
     *
     * <p>双方言分支逐字保留：SQLite {@code ON CONFLICT(player_id) DO UPDATE SET}，
     * MySQL {@code ON DUPLICATE KEY UPDATE}。{@code total_active_days} 是
     * <b>累加</b>而不是覆盖，见类注释。</p>
     *
     * @param todayStr    "今天"的日期串（形如 {@code 2026-03-15}），由调用方给出
     * @param consecutive 本次的连签天数，由调用方算出
     */
    public void recordMorningCultivation(Connection conn, long playerId, String todayStr, int consecutive)
            throws SQLException {
        String sql = morningCultivationSql();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, todayStr);
            ps.setInt(3, consecutive);
            ps.executeUpdate();
        }
    }

    /**
     * 建一行空的每日记录（只有 {@code player_id} 一列有值，其余走列默认值）。
     *
     * <p>原实现是"查不到就 {@code INSERT INTO player_daily (player_id) VALUES (?)}"，
     * 然后返回一个全零的 {@code PlayerDaily}。这里把"建行"和"读"拆开，
     * 顺序由服务层保持：先 {@link #getDaily}，返回 {@code null} 时调这个方法，
     * 再返回空对象。</p>
     */
    public void insertDaily(Connection conn, long playerId) throws SQLException {
        String sql = "INSERT INTO player_daily (player_id) VALUES (?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    /** 自取连接版的建行（委托给 Connection 版，SQL 只写一份）。 */
    public void insertDaily(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertDaily(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("创建每日数据失败", e);
        }
    }

    /**
     * 跨日复位：把四个今日机缘计数、四个奖励标记与 PVP 次数归零并刷新日期。
     *
     * <p>WHERE 的 {@code (last_daily_reset IS NULL OR last_daily_reset <> ?)}
     * 是防重复归零的关键，一个字符都不能少，见类注释。</p>
     *
     * <p><b>返回值被丢掉了</b>：原实现也是 {@code ps.executeUpdate();}
     * 不取返回值 —— 复位 0 行只意味着"今天已经复位过"，不是错误。</p>
     *
     * @param today "今天"的日期串，由调用方给出（DAO 不自己调 {@code LocalDate.now()}）
     */
    public void resetDaily(Connection conn, long playerId, String today) throws SQLException {
        String sql = "UPDATE player_daily SET " +
                "exploration_count = 0, battle_count = 0, secret_realm_count = 0, skill_learn_count = 0, pvp_count = 0, " +
                "exploration_rewarded = 0, battle_rewarded = 0, secret_realm_rewarded = 0, skill_learn_rewarded = 0, " +
                "last_daily_reset = ? WHERE player_id = ? AND (last_daily_reset IS NULL OR last_daily_reset <> ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today);
            ps.setLong(2, playerId);
            ps.setString(3, today);
            ps.executeUpdate();
        }
    }

    /**
     * 自取连接版的跨日复位（委托给 Connection 版，SQL 只写一份）。
     *
     * <p>失败文案"每日状态复位失败"沿用原实现。</p>
     */
    public void resetDaily(long playerId, String today) {
        try (Connection conn = DatabaseManager.getConnection()) {
            resetDaily(conn, playerId, today);
        } catch (SQLException e) {
            throw new RuntimeException("每日状态复位失败", e);
        }
    }

    /**
     * 计数型每日字段自增 1（upsert，同日累加；跨日复位由 {@link #resetDaily} 负责）。
     *
     * <p>列名由调用方给定（{@code exploration_count} / {@code battle_count} /
     * {@code secret_realm_count} / {@code skill_learn_count}），与原实现一样
     * 是拼接进 SQL 的 —— 标识符天生无法参数化。列名必须是受信任的字面量。</p>
     *
     * <p>双方言分支逐字保留，包括 {@code last_daily_reset = CURRENT_DATE}
     * 这个数据库侧取值的写法（不是绑定参数）。</p>
     */
    public void incrementDailyCount(Connection conn, long playerId, String countColumn) throws SQLException {
        String sql = dailyCountIncrementSql(countColumn);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    /** 自取连接版的计数自增（委托给 Connection 版）。 */
    public void incrementDailyCount(long playerId, String countColumn) {
        try (Connection conn = DatabaseManager.getConnection()) {
            incrementDailyCount(conn, playerId, countColumn);
        } catch (SQLException e) {
            throw new RuntimeException("记录每日次数失败", e);
        }
    }

    /**
     * 把某个机缘奖励 / 共鸣里程碑标记为"已发放"。
     *
     * <p>列名拼进 SQL（见类注释），值固定是字面量 {@code 1}。
     * 这个 UPDATE <b>没有 WHERE 奖励条件</b> —— 它只在服务层判断过
     * "达标且未发过"之后才被调用，防重复发放完全靠服务层的那次判断。</p>
     */
    public void markFortuneClaimed(Connection conn, long playerId, String column) throws SQLException {
        String sql = "UPDATE player_daily SET " + column + " = 1 WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    /** 自取连接版的奖励标记（委托给 Connection 版）。 */
    public void markFortuneClaimed(long playerId, String column) {
        try (Connection conn = DatabaseManager.getConnection()) {
            markFortuneClaimed(conn, playerId, column);
        } catch (SQLException e) {
            throw new RuntimeException("标记机缘奖励失败", e);
        }
    }

    // ==================== 映射 ====================

    /**
     * {@code player_daily} 行 → {@link PlayerDaily}。
     *
     * <p>逐字搬自原 {@code DailyService.mapDaily}：列读取顺序不变，
     * 三个 {@code xxx_rewarded} 与两个 {@code resonanceXX_awarded}
     * 一律 {@code getInt(...) == 1} 判布尔（列类型是 {@code INT DEFAULT 0}）。</p>
     */
    private static PlayerDaily mapDaily(ResultSet rs) throws SQLException {
        PlayerDaily d = new PlayerDaily();
        d.lastMorningCultivation = rs.getString("last_morning_cultivation");
        d.consecutiveDays = rs.getInt("consecutive_days");
        d.totalActiveDays = rs.getInt("total_active_days");
        d.explorationCount = rs.getInt("exploration_count");
        d.battleCount = rs.getInt("battle_count");
        d.secretRealmCount = rs.getInt("secret_realm_count");
        d.skillLearnCount = rs.getInt("skill_learn_count");
        d.explorationRewarded = rs.getInt("exploration_rewarded") == 1;
        d.battleRewarded = rs.getInt("battle_rewarded") == 1;
        d.secretRealmRewarded = rs.getInt("secret_realm_rewarded") == 1;
        d.skillLearnRewarded = rs.getInt("skill_learn_rewarded") == 1;
        d.resonance7Awarded = rs.getInt("resonance7_awarded") == 1;
        d.resonance30Awarded = rs.getInt("resonance30_awarded") == 1;
        return d;
    }

    // ==================== 方言 ====================

    /**
     * 早间修行签到的 upsert。
     *
     * <p>SQLite 用 {@code ON CONFLICT(player_id) DO UPDATE SET} +
     * {@code excluded.xxx}；MySQL 用 {@code ON DUPLICATE KEY UPDATE} +
     * {@code VALUES(xxx)}。两边的 {@code total_active_days = total_active_days + 1}
     * 都是累加，不是覆盖。</p>
     */
    private static String morningCultivationSql() {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_daily (player_id, last_morning_cultivation, consecutive_days, total_active_days) "
                    + "VALUES (?, ?, ?, 1) "
                    + "ON CONFLICT(player_id) DO UPDATE SET "
                    + "last_morning_cultivation = excluded.last_morning_cultivation, "
                    + "consecutive_days = excluded.consecutive_days, "
                    + "total_active_days = total_active_days + 1";
        }
        return "INSERT INTO player_daily (player_id, last_morning_cultivation, consecutive_days, total_active_days) "
                + "VALUES (?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE "
                + "last_morning_cultivation = VALUES(last_morning_cultivation), "
                + "consecutive_days = VALUES(consecutive_days), "
                + "total_active_days = total_active_days + 1";
    }

    /**
     * 计数型每日字段的跨方言累加。
     *
     * <p>SQLite {@code ON CONFLICT(player_id) DO UPDATE SET}，
     * MySQL {@code ON DUPLICATE KEY UPDATE}。{@code last_daily_reset = CURRENT_DATE}
     * 是数据库侧取值，两边一致。</p>
     */
    private static String dailyCountIncrementSql(String countColumn) {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_daily (player_id, " + countColumn + ", last_daily_reset) VALUES (?, 1, CURRENT_DATE) " +
                    "ON CONFLICT(player_id) DO UPDATE SET " + countColumn + " = " + countColumn + " + 1, " +
                    "last_daily_reset = CURRENT_DATE";
        }
        return "INSERT INTO player_daily (player_id, " + countColumn + ", last_daily_reset) VALUES (?, 1, CURRENT_DATE) " +
                "ON DUPLICATE KEY UPDATE " + countColumn + " = " + countColumn + " + 1, " +
                "last_daily_reset = CURRENT_DATE";
    }

    /**
     * 一行 {@code player_daily}。
     *
     * <p><b>从 {@code DailyService.PlayerDaily} 原样搬过来的</b>：字段名、
     * 默认值（{@code int} 0 / {@code boolean} false / {@code String} null）
     * 一个都没改。搬过来是因为它本质上就是这张表的行映射 —— 服务层的类型
     * 住在 db 包会迫使 DAO 反向依赖服务层
     * （{@code import com.mtxgdn.game.service.DailyService}），那是层级倒置。</p>
     *
     * <p>全项目没有任何地方用 {@code DailyService.PlayerDaily} 这个限定名
     * 引用它，所以搬走不破坏任何公开 API。</p>
     *
     * <p><b>字段是 {@code public} 而不是 private + getter</b>：原实现是
     * 包级可见，搬包之后 {@code game.service} 就读不到了。加 getter 会让
     * 服务层十几处 {@code daily.explorationCount} 全部改写成
     * {@code daily.getExplorationCount()}，diff 淹没真正的改动；
     * 而这个类型是纯粹的行映射，没有不变量需要保护 —— 谁会去写一个
     * 只有 12 个 int/boolean 的 DTO 的"非法状态"？所以直接放开字段，
     * 与 {@link EconomyDAO.AuctionSettlementRow} 等其它行映射类型一致。</p>
     *
     * <p><b>{@code xxxRewarded} 三列的默认值是 {@code false}</b>：
     * 库里是 {@code INT DEFAULT 0}，映射时按 {@code == 1} 判布尔。
     * "没领过"与"库里这一列不存在"都是 false，调用方据此发奖。</p>
     */
    public static class PlayerDaily {
        public String lastMorningCultivation;
        public int consecutiveDays;
        public int totalActiveDays;
        public int explorationCount;
        public int battleCount;
        public int secretRealmCount;
        public int skillLearnCount;
        public boolean explorationRewarded;
        public boolean battleRewarded;
        public boolean secretRealmRewarded;
        public boolean skillLearnRewarded;
        public boolean resonance7Awarded;
        public boolean resonance30Awarded;
    }
}
