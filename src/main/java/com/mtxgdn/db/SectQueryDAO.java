package com.mtxgdn.db;

import com.mtxgdn.game.entity.Sect;
import com.mtxgdn.game.entity.SectMember;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门本体与成员的<b>只读</b>查询（{@code sects} / {@code sect_members} 表）外加宗门排行的
 * 读写入口 —— 严格说只有"读"入口，这个类一条写语句都没有。
 *
 * <p>从 {@code SectQueryDomain} 抽出来的 DAO。这个域被其它所有域共用
 * （成员域要用它查成员、仓库域要用它、继位域要用它列成员、金库域要遍历全部宗门……），
 * 所以它的形状直接影响别人。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询宗门列表失败"、
 *       "查询宗门失败"、"查询玩家宗门失败"、"查询宗门排行失败"、
 *       "查询宗门成员失败"，一个都没改）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里必须用这一版，否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}。</li>
 * </ul>
 *
 * <p><b>八个方法全都有 Connection 版，尽管当前没有一处调用在事务里</b>：
 * 这些查询被五个域共用，别域的事务块随时可能想复用同一条语句（仓库捐献事务里复核成员、
 * 继位事务里重读宗门、宣战事务里确认声望……），那时只能传 {@code conn}，自己开连接会撞
 * {@code SQLITE_BUSY_SNAPSHOT}。这与金库域的做法一致：先给足重载，
 * 比等第一次死锁再补要便宜。</p>
 *
 * <p><b>这个域必须一直只读</b>：原实现的类注释就写明"一旦这里出现写操作，
 * 调用方就无法判断我这一句会不会偷偷落库"。所以 DAO 里没有 INSERT / UPDATE / DELETE，
 * 也没有 {@code setAutoCommit} / {@code commit} / {@code rollback} / {@code runTransaction}。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>"全部宗门"与"前 N 名"是两条 SQL，没有合并</b>：{@link #getAllSects} 与
 *       {@link #getTopSects} 的列清单、JOIN、表名一模一样，唯一差别是后者多一个
 *       {@code LIMIT ?}（且排序从两级变一级）。原实现就是两条独立拼接的字符串
 *       （类注释特意说明"没有合并成常量"），这里也一样。合并成一个
 *       {@code findSects(int limit)} 会让"全量列表"也要传 limit，或者让某个调用方
 *       顺手传个 10 把全量查询变成分页查询。</li>
 *   <li><b>{@code LIMIT ?} 是绑定参数，不是字符串拼接</b>：{@code ps.setInt(1, limit)}
 *       逐字保留。改成 {@code "LIMIT " + limit} 会打开 SQL 注入的口子，
 *       改成 {@code setMaxRows} 则会改变"SQL 本身长什么样"。</li>
 *   <li><b>{@code member_count} 是标量相关子查询，不是 JOIN + GROUP BY</b>：
 *       {@code (SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id)}。
 *       用相关子查询才能保证 {@code s.*} 那一行不因为多 JOIN 一张表而重复，
 *       也就不需要 DISTINCT。这是原实现的选择，逐字保留。</li>
 *   <li><b>{@code getPlayerSect} 的 {@code sect_members} 是 INNER JOIN，
 *       其他四条宗门查询没有这一条</b>：INNER JOIN 让"不是这个宗门成员的人"
 *       直接查不到行，从而返回 {@code null}（= 玩家还没有宗门）。
 *       而 {@code players} 在<b>全部五条</b>宗门查询里都是 {@code LEFT JOIN} ——
 *       宗主被删号时这一行还要在，只是 {@code leader_name} 退化为 null。
 *       两个 JOIN 的左右之别是刻意的，不能"统一风格"。</li>
 *   <li><b>{@code getPlayerSect} 里子查询的 {@code sm} 与外层
 *       {@code sect_members sm} 同名</b>：靠 SQL 作用域规则，子查询内的 {@code sm}
 *       指向自己。逐字保留，没有"顺手"改名为 {@code sm2} —— 那是改写查询，
 *       不属于纯结构重构。</li>
 *   <li><b>{@code getSectMembers} 的排序是 CASE 表达式 + 二级排序</b>：
 *       {@code LEADER=0 → VICE_LEADER=1 → ELDER=2 → INNER_MEMBER=3 → ELSE=4}，
 *       同职位内按 {@code sm.joined_at} 正序（先入门在前）。{@code ELSE 4} 把外门弟子
 *       与任何未知角色一起排在最后，缺了它未知角色会排到最前面。
 *       这条 ORDER BY <b>不带 LIMIT</b>：一个宗门的成员数受 {@code max_members} 限制，
 *       服务层要拿全量去挑继任者和出战成员。</li>
 *   <li><b>{@code getMember} / {@code getPlayerMember} 不带 ORDER BY、不带 LIMIT</b>：
 *       {@code (sect_id, player_id)} 组合唯一，"第一行"就是"唯一行"。
 *       原实现只判一次 {@code rs.next()}，这里保持
 *       {@code if (rs.next()) return mapMember(rs); ... return null;}。</li>
 *   <li><b>查不到一律返回 {@code null}</b>，不是抛异常、不是空实体 ——
 *       调用方（建档、入宗、退宗、踢人、任命……）全都靠 {@code == null} 走失败文案。</li>
 *   <li><b>异常文案按查询粒度区分</b>：列表 / 单体 / 玩家宗门 / 排行 / 成员各一句，
 *       日志里能直接看出是哪条查询炸了。{@code getSectById} 与 {@code getSectByName}
 *       共用"查询宗门失败"是原实现如此，没有给它们各起一个；
 *       三条成员查询共用"查询宗门成员失败"也一样。</li>
 *   <li><b>五条宗门查询共用一个 SELECT 前缀，仍然写了五遍</b>：
 *       {@code SELECT s.*, p.name AS leader_name, (SELECT COUNT(*) ...) AS member_count}。
 *       抽成私有常量或 {@code private static String sectSelectPrefix()} 属于"顺手重构"，
 *       会让字符串拼接形状与拆分前不一致，也会让人以为改一处就能改全部 ——
 *       所以这个类里没有任何私有 SQL 片段方法。</li>
 *   <li><b>{@code s.*} / {@code sm.*} 列头不改成逐列</b>，映射的逐列容错
 *       {@code try/catch} 也原样保留：{@code leader_absent_since} / {@code contest_until} /
 *       {@code contest_challenger_id} / {@code contest_requested} / {@code treasury} /
 *       {@code salary_per_member} / {@code last_salary_time} / {@code leader_name} /
 *       {@code member_count} 这些列老库可能没有，读不到就退化成默认值；而
 *       {@code id} / {@code name} / {@code description} / {@code leader_player_id} /
 *       {@code level} / {@code prestige} / {@code max_members} / {@code created_at}
 *       是主数据，缺列就让查询失败。</li>
 *   <li><b>{@code SectMember.playerRealmName} 依然不被赋值</b>：成员查询只 SELECT 了
 *       {@code p.name} / {@code p.realm} / {@code p.level}，没有境界名列，
 *       {@code mapMember} 也从未 set 它。逐字保留，没有"补全"这个字段。</li>
 *   <li><b>映射是复制进来的，不是调用服务层的</b>：{@code SectSupport.mapSect} /
 *       {@code mapMember} 是 {@code com.mtxgdn.game.service} 包里的<b>包级</b>静态方法，
 *       {@code com.mtxgdn.db} 根本看不见它们；而且让 DAO 依赖服务层是层级倒置。
 *       所以按成员域 DAO 的既有做法（{@code SectMembershipDAO} 也是这样把
 *       {@code mapApplication} 原样抄了一份）搬进来，列读取顺序与每列的容错都没动。</li>
 *   <li><b>DAO 不自己取时间</b>：这八条查询一条时间戳都不碰，
 *       没有 {@code System.currentTimeMillis()}，也没有 {@code CURRENT_TIMESTAMP}。</li>
 * </ul>
 *
 * <p>业务规则（成员上限、职位权限、国际化文案、{@code SectOperationResult} 组装）
 * 一律留在服务层，不进 DAO —— 这个域原本就只有查询，所以服务层剩下的
 * 只有"把调用转给 DAO"这一层。</p>
 */
public class SectQueryDAO {

    // ==================== 宗门查询 ====================

    /**
     * 全部宗门（按声望倒序，同声望再按等级倒序）。
     *
     * <p><b>不带 LIMIT</b>：WebSocket 宗门列表与 {@code /宗门 列表} 要的就是全量。</p>
     */
    public List<Sect> getAllSects() {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getAllSects(conn);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门列表失败", e);
        }
    }

    public List<Sect> getAllSects(Connection conn) throws SQLException {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC, s.level DESC";
        List<Sect> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapSect(rs));
            }
        }
        return result;
    }

    /** 按 id 查宗门；不存在返回 {@code null}。 */
    public Sect getSectById(long sectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getSectById(conn, sectId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门失败", e);
        }
    }

    public Sect getSectById(Connection conn, long sectId) throws SQLException {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        }
        return null;
    }

    /** 按名字查宗门（建宗时已校验唯一）；不存在返回 {@code null}。 */
    public Sect getSectByName(String name) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getSectByName(conn, name);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门失败", e);
        }
    }

    public Sect getSectByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id WHERE s.name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        }
        return null;
    }

    /**
     * 玩家当前所属的宗门；还没加入任何宗门返回 {@code null}。
     *
     * <p>INNER JOIN {@code sect_members}：不是成员就查不到行。
     * {@code players} 仍是 LEFT JOIN —— 宗主被删号时这一行还要在。</p>
     */
    public Sect getPlayerSect(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getPlayerSect(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家宗门失败", e);
        }
    }

    public Sect getPlayerSect(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s JOIN sect_members sm ON s.id = sm.sect_id " +
                "LEFT JOIN players p ON s.leader_player_id = p.id WHERE sm.player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSect(rs);
            }
        }
        return null;
    }

    /**
     * 宗门排行（按声望倒序取前 {@code limit} 个）。
     *
     * <p>与 {@link #getAllSects} 是两条独立的 SQL：只按声望一级排序，并且带
     * {@code LIMIT ?}。{@code limit} 是绑定参数。</p>
     */
    public List<Sect> getTopSects(int limit) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getTopSects(conn, limit);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门排行失败", e);
        }
    }

    public List<Sect> getTopSects(Connection conn, int limit) throws SQLException {
        String sql = "SELECT s.*, p.name AS leader_name, " +
                "(SELECT COUNT(*) FROM sect_members sm WHERE sm.sect_id = s.id) AS member_count " +
                "FROM sects s LEFT JOIN players p ON s.leader_player_id = p.id " +
                "ORDER BY s.prestige DESC LIMIT ?";
        List<Sect> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapSect(rs));
            }
        }
        return result;
    }

    // ==================== 成员查询 ====================

    /**
     * 宗门全部成员（宗主 → 副宗主 → 长老 → 内门 → 其余，同职位按加入时间正序）。
     *
     * <p><b>不带 LIMIT</b>：服务层要拿全量去挑继任者和出战成员。</p>
     */
    public List<SectMember> getSectMembers(long sectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getSectMembers(conn, sectId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门成员失败", e);
        }
    }

    public List<SectMember> getSectMembers(Connection conn, long sectId) throws SQLException {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? ORDER BY " +
                "CASE sm.role WHEN 'LEADER' THEN 0 WHEN 'VICE_LEADER' THEN 1 WHEN 'ELDER' THEN 2 WHEN 'INNER_MEMBER' THEN 3 ELSE 4 END, sm.joined_at";
        List<SectMember> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapMember(rs));
            }
        }
        return result;
    }

    /** 某个宗门里的某个成员；不存在返回 {@code null}。 */
    public SectMember getMember(long sectId, long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getMember(conn, sectId, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门成员失败", e);
        }
    }

    public SectMember getMember(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id " +
                "WHERE sm.sect_id = ? AND sm.player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapMember(rs);
            }
        }
        return null;
    }

    /** 玩家（不限宗门）的成员行；还没加入任何宗门返回 {@code null}。 */
    public SectMember getPlayerMember(long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return getPlayerMember(conn, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询宗门成员失败", e);
        }
    }

    public SectMember getPlayerMember(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT sm.*, p.name AS player_name, p.realm AS player_realm, p.level AS player_level " +
                "FROM sect_members sm JOIN players p ON sm.player_id = p.id WHERE sm.player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapMember(rs);
            }
        }
        return null;
    }

    // ==================== 映射 ====================

    /**
     * {@code sects} 行（含 LEFT JOIN 出来的 {@code leader_name} 与子查询算出来的
     * {@code member_count}）→ 实体。
     *
     * <p>逐字搬自原服务包的 {@code SectSupport.mapSect}：列读取顺序不变，
     * 每列各自的容错 {@code try/catch}（老库缺列时退化为默认值）不变。</p>
     */
    private static Sect mapSect(ResultSet rs) throws SQLException {
        Sect s = new Sect();
        s.setId(rs.getLong("id"));
        s.setName(rs.getString("name"));
        s.setDescription(rs.getString("description"));
        s.setLeaderPlayerId(rs.getLong("leader_player_id"));
        s.setLevel(rs.getInt("level"));
        s.setPrestige(rs.getLong("prestige"));
        s.setMaxMembers(rs.getInt("max_members"));
        s.setCreatedAt(rs.getString("created_at"));
        // 继位/竞争期列：老库可能尚未迁移，读不到就当"宗主在任、无竞争期"
        try { s.setLeaderAbsentSince(rs.getLong("leader_absent_since")); } catch (SQLException ignored) {}
        try { s.setContestUntil(rs.getLong("contest_until")); } catch (SQLException ignored) {}
        try { s.setContestChallengerId(rs.getLong("contest_challenger_id")); } catch (SQLException ignored) {}
        try { s.setContestRequested(rs.getBoolean("contest_requested")); } catch (SQLException ignored) {}
        try { s.setTreasury(rs.getLong("treasury")); } catch (SQLException ignored) {}
        try { s.setSalaryPerMember(rs.getLong("salary_per_member")); } catch (SQLException ignored) {}
        try { s.setLastSalaryTime(rs.getLong("last_salary_time")); } catch (SQLException ignored) {}
        try { s.setLeaderName(rs.getString("leader_name")); } catch (SQLException ignored) {}
        try { s.setMemberCount(rs.getInt("member_count")); } catch (SQLException ignored) {}
        return s;
    }

    /**
     * {@code sect_members} 行（含 JOIN 出来的玩家列）→ 实体。
     *
     * <p>逐字搬自原服务包的 {@code SectSupport.mapMember}：列读取顺序不变，
     * 每列各自的容错 {@code try/catch} 不变，{@code playerRealmName} 依旧不赋值
     * （SQL 里没有境界名列）。</p>
     */
    private static SectMember mapMember(ResultSet rs) throws SQLException {
        SectMember m = new SectMember();
        m.setId(rs.getLong("id"));
        m.setSectId(rs.getLong("sect_id"));
        m.setPlayerId(rs.getLong("player_id"));
        m.setRole(rs.getString("role"));
        m.setContribution(rs.getLong("contribution"));
        m.setJoinedAt(rs.getString("joined_at"));
        try { m.setPlayerName(rs.getString("player_name")); } catch (SQLException ignored) {}
        try { m.setPlayerRealm(rs.getInt("player_realm")); } catch (SQLException ignored) {}
        try { m.setPlayerLevel(rs.getInt("player_level")); } catch (SQLException ignored) {}
        return m;
    }
}
