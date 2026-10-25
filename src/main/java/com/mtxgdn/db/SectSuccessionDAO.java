package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗主继任域（{@code sects} 行的缺位/竞争期字段 + {@code sect_members.role}）的读写入口。
 *
 * <p>从 {@code SectSuccessionDomain} 抽出来的 DAO。每个"意图"一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"标记宗主缺位失败"、
 *       "清理到期宗主竞争期失败"等，一个都没改）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么一部分方法只有 Connection 版</b>：继位与宗主争夺战的落库原本就各是一个
 * {@code runTransaction}（{@link #applySuccession} 与 {@link #applyContestOutcome}
 * 两条条件 UPDATE + 两次 {@link #setMemberRole}）。这三步必须共用同一条 {@code conn}：
 * 一是"换了宗主却没改成员行"会留下两个 {@code LEADER}，二是 SQLite 的
 * {@code BEGIN DEFERRED} 已持写锁，事务里再开连接就是 {@code SQLITE_BUSY_SNAPSHOT}
 * （不受 {@code busy_timeout} 约束）。所以它们<b>刻意不给</b>自取连接的重载 ——
 * 给了就等于给"在事务外单独改一张子表"开门。反过来，标记缺位、清缺位标记、清理到期竞争期、
 * 发起挑战、两条扫描查询在拆分前就是"单语句即提交"的独立短连接，这里保持同样的形态，
 * 两个重载都给。</p>
 *
 * <p><b>没有双方言分支</b>：这个域的六条语句在 SQLite 与 MySQL 下逐字通用
 * （没有 {@code ON CONFLICT} / {@code ON DUPLICATE KEY} / {@code MAX(0,·)} 之类），
 * 所以本类没有 {@code private static String xxxSql()} 这样的方言收口方法。
 * 真要给某条语句加方言分支时，请收进这样一个私有方法，而不是在调用处拼字符串。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>退位不动 {@code leader_player_id}</b>：{@link #markLeaderAbsent} 只把
 *       {@code leader_absent_since} 置成"现在"并清掉竞争期字段，{@code leader_player_id}
 *       原样留着。所以"宗主已退位"与"宗门没有宗主"是两件事 —— 前者等继任者，后者
 *       （{@code clearLeaderAbsent} 之后）等新成员加入。退位者真正的降级发生在
 *       {@link #applySuccession} 里那次 {@link #setMemberRole} 上，而且<b>只在
 *       {@code incumbentId > 0} 时执行</b>：已退宗的人这行不存在，UPDATE 自然不命中。</li>
 *   <li><b>继位是条件更新</b>：{@link #applySuccession} 的
 *       {@code WHERE id = ? AND leader_absent_since = ?} 用"读到的缺位时间"当版本号。
 *       指令与扫描任务同时触发时只有一个能改成功，另一个拿到 {@code 0} 行后整轮放弃。
 *       同时这条 UPDATE 自己把 {@code leader_absent_since} 归 0 —— 幂等是靠数据库
 *       而不是"再读一次判断"，改成先 SELECT 再 UPDATE 就会两个请求都判成功。</li>
 *   <li><b>没有竞争者时挑战者列写 NULL 而不是 0</b>：{@link #applySuccession} 在
 *       {@code challengerPlayerId <= 0} 时走 {@code setNull(3, Types.BIGINT)}。
 *       {@code contest_challenger_id} 是可为 NULL 的 BIGINT，"无挑战者"与
 *       "挑战者是 0 号玩家"必须能区分；而 {@link #clearExpiredContests} /
 *       {@link #applyContestOutcome} 清这一列时用的也是字面量 {@code NULL}。</li>
 *   <li><b>竞争期三列一起清</b>：{@link #clearExpiredContests} 清的是
 *       {@code contest_until = 0, contest_challenger_id = NULL, contest_requested = 0}
 *       三列，且 WHERE 带 {@code contest_until > 0} —— 没有竞争期的行不会被反复
 *       "清理"，扫描任务的计数因此只反映真正到期的那一批。</li>
 *   <li><b>发起挑战是条件更新</b>：{@link #requestContest} 的
 *       {@code WHERE id = ? AND contest_challenger_id = ? AND contest_until > ?}
 *       把"竞争期已结束"和"挑战者已换人"都挡在外面，返回 0 行时调用方给出
 *       "竞争期已结束"的文案，而不是让玩家以为发起成功了。</li>
 *   <li><b>争夺战结算防双端重复</b>：{@link #applyContestOutcome} 的 WHERE 同时钉住
 *       {@code contest_requested = 1}、{@code contest_challenger_id = ?} 与
 *       {@code leader_player_id = ?} 三个值，且把 {@code leader_absent_since} 一并归 0
 *       —— 不清的话新宗主一就位，下一轮扫描又会把他当"缺位宗主"再换一次。
 *       败者改任副宗主也在这里，两次 {@link #setMemberRole} 的顺序是先胜后败。</li>
 *   <li><b>扫不活跃宗主刻意"先 SELECT id 再逐条 UPDATE"</b>：MySQL 不允许在 UPDATE
 *       的子查询里直接引用被更新的表（ERROR 1093），拆开写两种方言都安全。
 *       这条 SELECT 的三个门槛一个都不能少：{@code leader_absent_since = 0}
 *       （已在缺位的不重复标）、{@code p.last_active_time > 0}（老库升级后该列全为 0，
 *       当成纪元起点会在升级瞬间把全服宗主一次性判成弃坑）、
 *       {@code COUNT(*) > 1}（单人宗门无人可继位，标了只会来回闪烁）。</li>
 *   <li><b>时间戳一律由调用方给</b>：{@code now} / {@code cutoff} 都是参数。
 *       DAO 不自己调 {@code System.currentTimeMillis()} —— 否则一次重试里的时间戳
 *       会悄悄变化，而继位/竞争期的边界判定对"现在"极其敏感。</li>
 *   <li><b>异常一律包成 {@link RuntimeException}</b>：自取连接版沿用原实现的文案；
 *       {@code conn} 版直接抛 {@link SQLException}，由 {@code runTransaction} 统一回滚
 *       —— 原实现在事务块内也是 {@code catch (SQLException e) { throw new RuntimeException(e); }}，
 *       文案为空，两边最终都被转成"事务执行失败"，不改变可观测行为。</li>
 * </ul>
 *
 * <p>业务规则（谁能退位、竞争者怎么挑、归一化怎么压战力、{@code Map} 的 key 与文案）
 * 一律留在服务层，不进 DAO。</p>
 */
public class SectSuccessionDAO {

    // ==================== 宗主缺位 ====================

    /**
     * 标记宗主缺位（条件更新：仅在当前仍在任时生效）。
     *
     * <p>刻意<b>不</b>动 {@code leader_player_id}：退位只是"等继任者"，宗门仍然有宗主。
     * 同时把竞争期三列清掉 —— 缺位期间不该还挂着一个竞争期。</p>
     *
     * @param now 缺位起始时间（毫秒），由调用方给定
     * @return 受影响行数；0 表示该宗门已经处于缺位状态（重复调用不会推进时间）
     */
    public int markLeaderAbsent(long sectId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return markLeaderAbsent(conn, sectId, now);
        } catch (SQLException e) {
            throw new RuntimeException("标记宗主缺位失败", e);
        }
    }

    public int markLeaderAbsent(Connection conn, long sectId, long now) throws SQLException {
        String sql = "UPDATE sects SET leader_absent_since = ?, contest_until = 0, "
                + "contest_challenger_id = NULL, contest_requested = 0, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, sectId);
            return ps.executeUpdate();
        }
    }

    /**
     * 清除缺位标记（条件更新：仅在值未被他人改动时生效）。
     *
     * <p>返回值刻意是 {@code void}：原实现就是 {@code executeUpdate()} 后直接丢弃行数 ——
     * "无人可继位"这一支不关心有没有命中，没命中下一轮扫描会重新判定。</p>
     *
     * @param expectedAbsentSince 读到的缺位起始时间，用作乐观锁版本号
     */
    public void clearLeaderAbsent(long sectId, long expectedAbsentSince) {
        try (Connection conn = DatabaseManager.getConnection()) {
            clearLeaderAbsent(conn, sectId, expectedAbsentSince);
        } catch (SQLException e) {
            throw new RuntimeException("清除宗主缺位标记失败", e);
        }
    }

    public void clearLeaderAbsent(Connection conn, long sectId, long expectedAbsentSince)
            throws SQLException {
        String sql = "UPDATE sects SET leader_absent_since = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND leader_absent_since = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, expectedAbsentSince);
            ps.executeUpdate();
        }
    }

    // ==================== 竞争期 ====================

    /**
     * 竞争期到期但未被挑战：宗主保住位置，只清字段。
     *
     * <p>WHERE 里的 {@code contest_until > 0} 让"没有竞争期的行"不被反复清理，
     * 扫描任务的计数因此只反映真正到期的那一批。</p>
     *
     * @param now 当前时间（毫秒），由调用方给定
     * @return 被清理的宗门行数
     */
    public int clearExpiredContests(long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return clearExpiredContests(conn, now);
        } catch (SQLException e) {
            throw new RuntimeException("清理到期宗主竞争期失败", e);
        }
    }

    public int clearExpiredContests(Connection conn, long now) throws SQLException {
        String sql = "UPDATE sects SET contest_until = 0, contest_challenger_id = NULL, "
                + "contest_requested = 0, updated_at = CURRENT_TIMESTAMP "
                + "WHERE contest_until > 0 AND contest_until <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    /**
     * 挑战者发起挑战：置 {@code contest_requested = 1}，等宗主应战。
     *
     * <p>条件更新把"竞争期已结束"与"挑战者已换人"都挡在外面：返回 0 行时调用方要给出
     * "竞争期已结束"的文案，而不是让玩家以为发起成功了。</p>
     *
     * @param challengerPlayerId 读到的挑战者 id（{@code Sect#getContestChallengerId()}）
     * @param now                当前时间（毫秒），由调用方给定
     * @return 受影响行数；0 表示竞争期已结束或挑战者已变
     */
    public int requestContest(long sectId, long challengerPlayerId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return requestContest(conn, sectId, challengerPlayerId, now);
        } catch (SQLException e) {
            throw new RuntimeException("发起宗主挑战失败", e);
        }
    }

    public int requestContest(Connection conn, long sectId, long challengerPlayerId, long now)
            throws SQLException {
        String sql = "UPDATE sects SET contest_requested = 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND contest_challenger_id = ? AND contest_until > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, challengerPlayerId);
            ps.setLong(3, now);
            return ps.executeUpdate();
        }
    }

    // ==================== 扫描查询 ====================

    /**
     * 列出"缺位且无进行中竞争期"的宗门 id，交给调用方逐个继位。
     *
     * @param now 当前时间（毫秒），由调用方给定
     */
    public List<Long> listAbsentLeaderSectIds(long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return listAbsentLeaderSectIds(conn, now);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗主缺位的宗门失败", e);
        }
    }

    public List<Long> listAbsentLeaderSectIds(Connection conn, long now) throws SQLException {
        String sql = "SELECT id FROM sects WHERE leader_absent_since > 0 AND contest_until <= ?";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /**
     * "长期不活跃的宗主"所在宗门 id。
     *
     * <p>刻意是"先 SELECT id 再逐条 UPDATE"的前半段：MySQL 不允许在 UPDATE 的子查询里
     * 直接引用被更新的表（ERROR 1093），拆开写两种方言都安全。三个门槛见类注释。</p>
     *
     * @param now    当前时间（毫秒），由调用方给定
     * @param cutoff 不活跃判定线（{@code now - absentDays}），由调用方算好传入
     */
    public List<Long> findInactiveLeaderSectIds(long now, long cutoff) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findInactiveLeaderSectIds(conn, now, cutoff);
        } catch (SQLException e) {
            throw new RuntimeException("查询不活跃宗主失败", e);
        }
    }

    public List<Long> findInactiveLeaderSectIds(Connection conn, long now, long cutoff)
            throws SQLException {
        String sql = "SELECT s.id FROM sects s JOIN players p ON s.leader_player_id = p.id "
                + "WHERE s.leader_absent_since = 0 AND s.contest_until <= ? "
                + "AND p.last_active_time > 0 AND p.last_active_time < ? "
                + "AND (SELECT COUNT(*) FROM sect_members m WHERE m.sect_id = s.id) > 1";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    // ==================== 继位（只在事务内使用） ====================

    /**
     * 条件更新 {@code sects} 完成一次继位。
     *
     * <p><b>只有 Connection 版</b>：它只可能在 {@code runTransaction} 的 lambda 内被调用，
     * 后面紧跟两次 {@link #setMemberRole}。给一个自取连接的重载只会诱导调用方在事务外
     * 单独换宗主 —— 那会留下"{@code sects} 说他是宗主、{@code sect_members} 说你是"的
     * 双宗主中间态。</p>
     *
     * @param contestUntil        竞争期截止时间；0 = 不开竞争期
     * @param challengerPlayerId  挑战者 id；{@code <= 0} 时写 SQL {@code NULL}
     * @param expectedAbsentSince 读到的缺位起始时间，用作乐观锁版本号
     * @return 受影响行数；0 表示状态已被其他请求推进，本次继位作废
     */
    public int applySuccession(Connection conn, long sectId, long newLeaderPlayerId,
                               long contestUntil, long challengerPlayerId, long expectedAbsentSince)
            throws SQLException {
        String sql = "UPDATE sects SET leader_player_id = ?, leader_absent_since = 0, "
                + "contest_until = ?, contest_challenger_id = ?, contest_requested = 0, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND leader_absent_since = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newLeaderPlayerId);
            ps.setLong(2, contestUntil);
            if (challengerPlayerId > 0) {
                ps.setLong(3, challengerPlayerId);
            } else {
                ps.setNull(3, Types.BIGINT);
            }
            ps.setLong(4, sectId);
            ps.setLong(5, expectedAbsentSince);
            return ps.executeUpdate();
        }
    }

    // ==================== 宗主争夺战（只在事务内使用） ====================

    /**
     * 条件更新 {@code sects} 结算宗主争夺战。
     *
     * <p><b>只有 Connection 版</b>：与 {@link #applySuccession} 同理，它后面紧跟
     * "胜者升 LEADER、败者降 VICE_LEADER"两次 {@link #setMemberRole}，
     * 四步必须同生共死。</p>
     *
     * <p>WHERE 同时钉住 {@code contest_requested = 1}、{@code contest_challenger_id = ?}
     * 与 {@code leader_player_id = ?}，双端重复结算只有一个能成功。{@code leader_absent_since}
     * 一并归 0 —— 不清的话新宗主一就位，下一轮扫描又会把他当缺位宗主再换一次。</p>
     *
     * @param winnerPlayerId    胜者（新任宗主）
     * @param challengerPlayerId 挑战者
     * @param leaderPlayerId    落库前的现任宗主
     * @return 受影响行数；0 表示挑战状态已被其他请求处理
     */
    public int applyContestOutcome(Connection conn, long sectId, long winnerPlayerId,
                                   long challengerPlayerId, long leaderPlayerId)
            throws SQLException {
        String sql = "UPDATE sects SET leader_player_id = ?, contest_until = 0, "
                + "contest_challenger_id = NULL, contest_requested = 0, leader_absent_since = 0, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND contest_requested = 1 AND contest_challenger_id = ? AND leader_player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, winnerPlayerId);
            ps.setLong(2, sectId);
            ps.setLong(3, challengerPlayerId);
            ps.setLong(4, leaderPlayerId);
            return ps.executeUpdate();
        }
    }

    /**
     * 事务内设置成员职位。
     *
     * <p><b>只有 Connection 版</b>：继位与争夺战都在 {@code runTransaction} 里调它，
     * 而且两次调用（升新任宗主 / 降旧宗主）必须和上面的条件 UPDATE 落在同一条连接上。</p>
     *
     * <p>{@code role} 是绑定参数而不是字面量：这个域要把人升成 {@code LEADER} 也要降成
     * {@code VICE_LEADER}，与 {@code SectRegistryDAO} 那两个写死角色名的专用方法不同。</p>
     */
    public void setMemberRole(Connection conn, long sectId, long playerId, String role)
            throws SQLException {
        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }
}
