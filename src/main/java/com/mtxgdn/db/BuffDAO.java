package com.mtxgdn.db;

import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code player_buffs} 表的读写入口。
 *
 * <p>从 {@code BuffService} 抽出来的 DAO。查询类成对重载；写操作与
 * "读一批 → 逐条认领"的结算只有 Connection 版。</p>
 *
 * <p><b>这个域的核心是"认领"语义，不是 CRUD</b>：增益的属性加成被直接
 * 加到了 {@code players} 行上（{@code addAttack}/{@code addDefense}/…），
 * 所以"删掉一行增益"和"回退属性"必须严格一一对应。原实现用<b>条件
 * DELETE 的命中行数</b>当认领凭证 —— {@code executeUpdate() == 1} 才是
 * "这一行归我处理"的证据。抽 DAO 时这个判断被原样搬成
 * {@link #deleteExpiredBuffIfClaimed} 的 {@code boolean} 返回值。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li><b>每一条读写路径都先结算过期增益</b>：查 / 加 / 清 / 登录加载
 *       全部先跑一遍 {@code expire}，玩家看到的属性永远是最新的。这不是
 *       冗余 —— 后台兜底任务默认 60 秒一次（原实现是每秒全表扫描），
 *       在线玩家在两次 tick 之间必须靠惰性路径自我修正。</li>
 *   <li><b>认领条件带 {@code expire_time <= ?}</b>：玩家在期间重新嗑药
 *       刷新了时长（{@code expire_time} 被推到未来）时不会被误删、误扣。</li>
 *   <li><b>结算失败只 warn 不抛</b>（{@link #findExpiredBuffsForPlayer}）：
 *       结算失败不该让"查一下我有几个 buff"这种只读请求报错。</li>
 *   <li><b>兜底扫描失败完全静默</b>（{@link #findAllExpiredBuffs}）：
 *       原实现在这里连 warn 都没有 —— 它是每秒/每分钟跑的定时任务，
 *       数据库短暂不可用时刷日志只会淹没真正的问题。</li>
 *   <li><b>兜底扫描不带 {@code player_id}</b>：它要覆盖离线玩家，而惰性
 *       路径只处理"正在访问的那个玩家"。</li>
 *   <li><b>{@code getTotalBonus} 的列名是字符串拼接</b>：四个调用方
 *       （攻击/防御/速度/灵力）传的是硬编码字面量，不是用户输入。
 *       抽 DAO 时保留拼接，因为改成四条 SQL 会让"四个加成"这组概念
 *       散开；但列名<b>必须</b>由 DAO 校验，见
 *       {@link #sumActiveBonus(Connection, long, long, String)}。</li>
 *   <li><b>{@code COALESCE(SUM(...), 0)}</b>：没有任何增益行时
 *       {@code SUM} 返回 NULL，直接 {@code getInt} 会得到 0 但语义不明；
 *       显式 COALESCE 让"没有增益"与"加成恰好是 0"同形。</li>
 *   <li><b>同 buffId 已存在时只刷新时长，不叠加属性</b>：重复使用同种
 *       丹药不该让加成翻倍。判存在用独立的 SELECT，不是 UPSERT ——
 *       "刷新"与"插入"分支里只有插入才动属性。</li>
 *   <li><b>插入前先清掉该玩家已过期的增益</b>：否则"同 buffId 已存在"
 *       会把一条早已过期的行当成有效增益，只刷新它的时长而<b>不</b>加属性，
 *       玩家白嗑一颗药。</li>
 *   <li><b>{@code clearAllBuffs} 的汇总 SELECT 不带 {@code expire_time}
 *       过滤</b>：它要删全部行，所以汇总也必须是全部行 —— 带过滤会漏掉
 *       已过期但还没被结算的行，那些行的加成会永久留在 {@code players} 上。</li>
 *   <li><b>{@code reloadBuffsOnStartup} 是加不是减</b>：停机期间
 *       {@code players} 行没被改过，所以把仍然有效的加成再加一遍。
 *       前提是它先结算过期行，把停机期间过期的那些扣回去。</li>
 * </ul>
 */
public class BuffDAO {

    private static final GameLogger LOG = GameLogger.getLogger(BuffDAO.class);

    /** 四个合法的加成列名。拼进 SQL 前必须过这一关。 */
    private static final List<String> BONUS_COLUMNS =
            List.of("attack_bonus", "defense_bonus", "speed_bonus", "spirit_bonus");

    /**
     * 校验列名。
     *
     * <p>列名要拼进 SQL（不能用占位符），所以这里做白名单。四个调用方
     * 传的都是字面量，但 DAO 是 public 的，不该指望调用方永远守规矩。</p>
     */
    private static String requireBonusColumn(String column) {
        if (!BONUS_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("未知的增益列: " + column);
        }
        return column;
    }

    // ==================== 活跃增益 ====================

    /**
     * 玩家当前有效的增益 {@code buff_id} 列表。
     *
     * <p>只取 id：调用方（{@code removeAllBuffs}）要逐条走完整的
     * {@code removeBuff} 路径去回退属性，不需要加成本身。</p>
     *
     * <p>{@code now} 由调用方给：DAO 不自己读时钟。</p>
     */
    public List<String> findActiveBuffIds(long playerId, long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findActiveBuffIds(conn, playerId, now);
        } catch (SQLException e) {
            throw new RuntimeException("查询活跃 Buff 失败", e);
        }
    }

    /** 事务内版本：只用调用方连接。{@code now} 由调用方给。 */
    public List<String> findActiveBuffIds(Connection conn, long playerId, long now)
            throws SQLException {
        String sql = "SELECT buff_id FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("buff_id"));
                }
                return ids;
            }
        }
    }

    /**
     * 玩家当前有效的增益（六列全要，用于展示）。
     *
     * <p>只有 Connection 版：调用方必须先在同一连接上结算过期行，
     * 再读这份列表 —— 否则会展示出"已过期但还没回退"的增益。</p>
     */
    public List<ActiveBuffRow> findActiveBuffs(Connection conn, long playerId, long now)
            throws SQLException {
        String sql = "SELECT buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus, "
                + "expire_time FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<ActiveBuffRow> rows = new ArrayList<>();
                while (rs.next()) {
                    ActiveBuffRow row = new ActiveBuffRow();
                    row.buffId = rs.getString("buff_id");
                    row.attackBonus = rs.getInt("attack_bonus");
                    row.defenseBonus = rs.getInt("defense_bonus");
                    row.speedBonus = rs.getInt("speed_bonus");
                    row.spiritBonus = rs.getInt("spirit_bonus");
                    row.expireTime = rs.getLong("expire_time");
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** 一行活跃增益。 */
    public static class ActiveBuffRow {
        public String buffId;
        public int attackBonus;
        public int defenseBonus;
        public int speedBonus;
        public int spiritBonus;
        public long expireTime;
    }

    /**
     * 四个加成列的总和（{@code COALESCE(SUM(col), 0)}）。
     *
     * <p>只有 Connection 版：调用方必须先结算过期行，否则会把已失效的
     * 加成算进来。</p>
     *
     * <p>{@code COALESCE} 不能省：没有任何增益行时 {@code SUM} 返回 NULL，
     * 显式归零让"没有增益"与"加成恰好是 0"同形。</p>
     */
    public int sumActiveBonus(Connection conn, long playerId, long now, String column)
            throws SQLException {
        String sql = "SELECT COALESCE(SUM(" + requireBonusColumn(column) + "), 0) as total "
                + "FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("total") : 0;
            }
        }
    }

    /**
     * 玩家<b>全部</b>增益行的四列加成汇总（不带 {@code expire_time} 过滤）。
     *
     * <p>只有 Connection 版：{@code clearAllBuffs} 在事务里用。</p>
     *
     * <p><b>刻意不带时间过滤</b>：调用方要把全部行删掉，所以汇总也必须是
     * 全部行 —— 带过滤会漏掉已过期但还没被结算的行，那些行的加成会永久
     * 留在 {@code players} 上。</p>
     */
    public BonusSum sumAllBonuses(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus "
                + "FROM player_buffs WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                BonusSum sum = new BonusSum();
                while (rs.next()) {
                    sum.attack += rs.getInt("attack_bonus");
                    sum.defense += rs.getInt("defense_bonus");
                    sum.speed += rs.getInt("speed_bonus");
                    sum.spirit += rs.getInt("spirit_bonus");
                }
                return sum;
            }
        }
    }

    /**
     * 玩家<b>仍然有效</b>的增益行的四列加成汇总（带 {@code expire_time > ?}）。
     *
     * <p>只有 Connection 版：{@code reloadBuffsOnStartup} 在事务里用。
     * 语义是"加"：停机期间 {@code players} 行没被改过，所以把仍然有效的
     * 加成再加一遍。前提是调用方先结算过期行。</p>
     */
    public BonusSum sumStillValidBonuses(Connection conn, long playerId, long now)
            throws SQLException {
        String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus "
                + "FROM player_buffs WHERE player_id = ? AND expire_time > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                BonusSum sum = new BonusSum();
                while (rs.next()) {
                    sum.attack += rs.getInt("attack_bonus");
                    sum.defense += rs.getInt("defense_bonus");
                    sum.speed += rs.getInt("speed_bonus");
                    sum.spirit += rs.getInt("spirit_bonus");
                }
                return sum;
            }
        }
    }

    /** 四列加成的累加和。 */
    public static class BonusSum {
        public int attack;
        public int defense;
        public int speed;
        public int spirit;
    }

    // ==================== 加 / 删增益 ====================

    /**
     * 该玩家是否已有同 buffId 的增益行（不论是否过期）。
     *
     * <p><b>刻意不看 {@code expire_time}</b>：调用方在调它之前必须先结算
     * 过期行。否则一条早已过期的行会让这里返回 true，于是只刷新时长而
     * <b>不</b>加属性，玩家白嗑一颗药。</p>
     */
    public boolean buffExists(Connection conn, long playerId, String buffId) throws SQLException {
        String sql = "SELECT id FROM player_buffs WHERE player_id = ? AND buff_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, buffId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 把已有增益的到期时间推到新的值（不叠加属性）。
     *
     * <p>只有 Connection 版：与"加属性"必须同生共死。</p>
     */
    public void refreshBuffExpire(Connection conn, long playerId, String buffId, long expireTime)
            throws SQLException {
        String sql = "UPDATE player_buffs SET expire_time = ? WHERE player_id = ? AND buff_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, expireTime);
            ps.setLong(2, playerId);
            ps.setString(3, buffId);
            ps.executeUpdate();
        }
    }

    /**
     * 插入一条增益。
     *
     * <p>只有 Connection 版：与"加属性"必须同生共死。</p>
     */
    public void insertBuff(Connection conn, long playerId, String buffId,
                           int attackBonus, int defenseBonus, int speedBonus, int spiritBonus,
                           long expireTime) throws SQLException {
        String sql = "INSERT INTO player_buffs "
                + "(player_id, buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus, expire_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, buffId);
            ps.setInt(3, attackBonus);
            ps.setInt(4, defenseBonus);
            ps.setInt(5, speedBonus);
            ps.setInt(6, spiritBonus);
            ps.setLong(7, expireTime);
            ps.executeUpdate();
        }
    }

    /**
     * 读一条增益的四列加成（用于移除前知道该扣多少）。
     *
     * <p>只有 Connection 版：调用方在事务里"读 → 删 → 扣属性"。</p>
     *
     * <p>读不到返回一个全 0 的 {@link BonusSum}：原实现用 0 初值，
     * 找不到行时四个分量都是 0，于是四个 {@code if (x != 0)} 全部跳过。</p>
     */
    public BonusSum findBuffBonuses(Connection conn, long playerId, String buffId)
            throws SQLException {
        String sql = "SELECT attack_bonus, defense_bonus, speed_bonus, spirit_bonus "
                + "FROM player_buffs WHERE player_id = ? AND buff_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, buffId);
            try (ResultSet rs = ps.executeQuery()) {
                BonusSum sum = new BonusSum();
                if (rs.next()) {
                    sum.attack = rs.getInt("attack_bonus");
                    sum.defense = rs.getInt("defense_bonus");
                    sum.speed = rs.getInt("speed_bonus");
                    sum.spirit = rs.getInt("spirit_bonus");
                }
                return sum;
            }
        }
    }

    /** 删掉玩家的一条增益（不判认领）。只有 Connection 版。 */
    public void deleteBuff(Connection conn, long playerId, String buffId) throws SQLException {
        String sql = "DELETE FROM player_buffs WHERE player_id = ? AND buff_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, buffId);
            ps.executeUpdate();
        }
    }

    /** 删掉玩家的全部增益。只有 Connection 版。 */
    public void deleteAllBuffs(Connection conn, long playerId) throws SQLException {
        String sql = "DELETE FROM player_buffs WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 过期结算 ====================

    /**
     * 该玩家已过期的增益（五列，用于结算）。
     *
     * <p>只有 Connection 版：结算必须在调用方的事务里，与条件 DELETE
     * 及属性回退同生共死。</p>
     *
     * <p><b>查询失败只 warn 不抛</b>：结算失败不该让"查一下我有几个 buff"
     * 这种只读请求报错。</p>
     */
    public List<ExpiredBuffRow> findExpiredBuffsForPlayer(Connection conn, long playerId, long now) {
        String sql = "SELECT buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus "
                + "FROM player_buffs WHERE player_id = ? AND expire_time <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<ExpiredBuffRow> rows = new ArrayList<>();
                while (rs.next()) {
                    ExpiredBuffRow row = new ExpiredBuffRow();
                    row.playerId = playerId;
                    row.buffId = rs.getString("buff_id");
                    row.attackBonus = rs.getInt("attack_bonus");
                    row.defenseBonus = rs.getInt("defense_bonus");
                    row.speedBonus = rs.getInt("speed_bonus");
                    row.spiritBonus = rs.getInt("spirit_bonus");
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            LOG.warn("查询过期增益失败 player=" + playerId + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * <b>全表</b>已过期增益（六列，含 {@code player_id}），供兜底任务用。
     *
     * <p>自取连接版：定时任务没有外层事务。查询失败<b>完全静默</b>返回
     * 空列表 —— 原实现如此（连 warn 都没有）。它是每分钟跑的定时任务，
     * 数据库短暂不可用时刷日志只会淹没真正的问题。</p>
     */
    public List<ExpiredBuffRow> findAllExpiredBuffs(long now) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findAllExpiredBuffs(conn, now);
        } catch (SQLException e) {
            return List.of();
        }
    }

    /**
     * 事务内版本：只用调用方连接。
     *
     * <p>失败<b>也</b>静默返回空列表，与自取连接版一致：兜底任务不该因为
     * 一次查询失败就把整批结算掉。</p>
     */
    public List<ExpiredBuffRow> findAllExpiredBuffs(Connection conn, long now) {
        String sql = "SELECT player_id, buff_id, attack_bonus, defense_bonus, speed_bonus, spirit_bonus "
                + "FROM player_buffs WHERE expire_time <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                List<ExpiredBuffRow> rows = new ArrayList<>();
                while (rs.next()) {
                    ExpiredBuffRow row = new ExpiredBuffRow();
                    row.playerId = rs.getLong("player_id");
                    row.buffId = rs.getString("buff_id");
                    row.attackBonus = rs.getInt("attack_bonus");
                    row.defenseBonus = rs.getInt("defense_bonus");
                    row.speedBonus = rs.getInt("speed_bonus");
                    row.spiritBonus = rs.getInt("spirit_bonus");
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            return List.of();
        }
    }

    /** 一行已过期的增益。 */
    public static class ExpiredBuffRow {
        public long playerId;
        public String buffId;
        public int attackBonus;
        public int defenseBonus;
        public int speedBonus;
        public int spiritBonus;
    }

    /**
     * 认领一条过期增益：条件 DELETE，命中即"这一行归我处理"。
     *
     * <p><b>只有 Connection 版</b>：必须在调用方的事务里，与属性回退同生
     * 共死。</p>
     *
     * <p><b>返回 {@code boolean} 而不是行数</b>：原实现的判断是
     * {@code executeUpdate() != 1} 就放弃 —— 只有严格等于 1 才算认领成功。
     * 用 {@code == 1} 而不是 {@code > 0}：主键约束下不可能删掉多于一行，
     * 但如果真发生了（比如未来改成按 {@code buff_id} 批量删），
     * {@code > 0} 会让"删了 3 行却只回退 1 行的属性"变成静默的账目不平。</p>
     *
     * <p><b>条件里带 {@code expire_time <= ?}</b>：玩家在期间重新嗑药刷新了
     * 时长（{@code expire_time} 被推到未来）时不会被误删、误扣。</p>
     *
     * <p>删除本身失败只 warn 并返回 false：让调用方跳过这一条继续处理
     * 其余的，而不是让整批结算失败。</p>
     */
    public boolean deleteExpiredBuffIfClaimed(Connection conn, long playerId, String buffId, long now) {
        String sql = "DELETE FROM player_buffs "
                + "WHERE player_id = ? AND buff_id = ? AND expire_time <= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, buffId);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            LOG.warn("删除过期增益失败 player=" + playerId + " buff=" + buffId + ": " + e.getMessage());
            return false;
        }
    }
}
