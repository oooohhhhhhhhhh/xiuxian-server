package com.mtxgdn.db;

import com.mtxgdn.game.entity.SectApplication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 宗门成员与入宗申请（{@code sect_applications} / {@code sect_members} 表）的读写入口。
 *
 * <p>从 {@code SectMembershipDomain} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询申请失败"、
 *       "添加成员失败"等，一个都没改）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p><b>为什么有 Connection 重载</b>：{@link #deleteSectCascade} 只在事务里出现 ——
 * 宗主退宗引发的"清仓库 → 清申请 → 清成员 → 删宗门"四步必须同生共死，
 * 中间任何一步失败却把前面的落库，就会留下一个没有宗主的空宗门记录。
 * {@link #addContribution} 同样提供 Connection 版：它本来就在同一条连接上
 * 先改成员贡献、再给宗门加声望（原实现如此），拆成两次独立短连接会改变这个
 * "一次调用一条连接"的语义，也让调用方无法在事务里复用它。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>重复申请判定靠存在性查询</b>：{@link #hasPendingApplication} 就是原实现那句
 *       {@code SELECT id FROM sect_applications WHERE ... status = 'pending'}，
 *       返回布尔而不是行数 —— 原实现只判断 {@code rs.next()}；</li>
 *   <li><b>入宗申请的 message 空串兜底</b>：{@code message != null ? message : ""}
 *       留在绑定参数处，与原来逐字相同；</li>
 *   <li><b>新成员固定是外门弟子</b>：{@code role} 列写的是字面量 {@code 'OUTER_MEMBER'}，
 *       不是绑定参数，抽 DAO 时没有改成传参；</li>
 *   <li><b>声望只在贡献为正时加</b>：{@link #addContribution} 的
 *       {@code updated > 0 && amount > 0} 两道门都与原来一致，
 *       返回的是成员表的受影响行数，调用方据此区分"成员不存在"与"已更新"；</li>
 *   <li><b>清库顺序不能反</b>：{@link #deleteSectCascade} 固定
 *       {@code sect_warehouse → sect_applications → sect_members → sects}，
 *       与原实现四条语句的顺序一一对应；</li>
 *   <li><b>宗主退宗前的成员数门槛</b>：{@link #countMembers} 用
 *       {@code SELECT COUNT(*)}，而 {@code COUNT(*)} 恒返回一行，
 *       所以原实现的 {@code rs.next() && getInt(1) > 1} 等价于
 *       {@code > 1}，这里直接返回行数让调用方自己比较。</li>
 * </ul>
 *
 * <p><b>映射为什么在 DAO 里</b>：{@code ResultSet → SectApplication} 的逐列读取
 * （含 {@code player_name} 那一列的容错 {@code try/catch}，老库缺列时退化为 null）
 * 原本写在服务包的 {@code SectSupport} 里。DAO 的职责就是把行变成实体，
 * 所以这里原样搬了一份；列顺序、每列各自的容错、都没有改动。</p>
 *
 * <p>业务规则（成员上限、权限判断、称号授予、{@code SectOperationResult} 组装）
 * 一律留在服务层，不进 DAO。</p>
 */
public class SectMembershipDAO {

    // ==================== 入宗申请 ====================

    /**
     * 该玩家是否已有待审申请。
     *
     * <p>原实现是"查出来有行就拒绝"，所以这里返回布尔而不是行数。</p>
     */
    public boolean hasPendingApplication(long sectId, long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return hasPendingApplication(conn, sectId, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("查询申请失败", e);
        }
    }

    public boolean hasPendingApplication(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "SELECT id FROM sect_applications WHERE sect_id = ? AND player_id = ? AND status = 'pending'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 提交入宗申请（{@code message} 为空时落空串，与原实现一致）。 */
    public void insertApplication(long sectId, long playerId, String message) {
        try (Connection conn = DatabaseManager.getConnection()) {
            insertApplication(conn, sectId, playerId, message);
        } catch (SQLException e) {
            throw new RuntimeException("提交申请失败", e);
        }
    }

    public void insertApplication(Connection conn, long sectId, long playerId, String message) throws SQLException {
        String sql = "INSERT INTO sect_applications (sect_id, player_id, message) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.setString(3, message != null ? message : "");
            ps.executeUpdate();
        }
    }

    /** 待审申请列表（按提交时间正序）。 */
    public List<SectApplication> findPendingApplications(long sectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findPendingApplications(conn, sectId);
        } catch (SQLException e) {
            throw new RuntimeException("查询申请列表失败", e);
        }
    }

    public List<SectApplication> findPendingApplications(Connection conn, long sectId) throws SQLException {
        String sql = "SELECT sa.*, p.name AS player_name FROM sect_applications sa " +
                "JOIN players p ON sa.player_id = p.id WHERE sa.sect_id = ? AND sa.status = 'pending' ORDER BY sa.created_at";
        List<SectApplication> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapApplication(rs));
            }
        }
        return result;
    }

    /** 单条申请（含 JOIN 出来的玩家名）；不存在返回 {@code null}。 */
    public SectApplication findApplicationById(long applicationId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return findApplicationById(conn, applicationId);
        } catch (SQLException e) {
            throw new RuntimeException("查询申请失败", e);
        }
    }

    public SectApplication findApplicationById(Connection conn, long applicationId) throws SQLException {
        String sql = "SELECT sa.*, p.name AS player_name FROM sect_applications sa " +
                "JOIN players p ON sa.player_id = p.id WHERE sa.id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapApplication(rs);
            }
        }
        return null;
    }

    /** 改申请状态（{@code accepted} / {@code rejected}）。 */
    public int updateApplicationStatus(long applicationId, String status) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return updateApplicationStatus(conn, applicationId, status);
        } catch (SQLException e) {
            throw new RuntimeException("处理申请失败", e);
        }
    }

    public int updateApplicationStatus(Connection conn, long applicationId, String status) throws SQLException {
        String sql = "UPDATE sect_applications SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, applicationId);
            return ps.executeUpdate();
        }
    }

    // ==================== 成员 ====================

    /** 加入宗门（角色固定为外门弟子）。 */
    public void addMember(long sectId, long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            addMember(conn, sectId, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("添加成员失败", e);
        }
    }

    public void addMember(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'OUTER_MEMBER')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 宗门当前成员数。 */
    public int countMembers(long sectId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return countMembers(conn, sectId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countMembers(Connection conn, long sectId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sect_members WHERE sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 把某个成员移出宗门。 */
    public void deleteMember(long sectId, long playerId) {
        try (Connection conn = DatabaseManager.getConnection()) {
            deleteMember(conn, sectId, playerId);
        } catch (SQLException e) {
            throw new RuntimeException("删除成员失败", e);
        }
    }

    public void deleteMember(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "DELETE FROM sect_members WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 任命：改成员职位。 */
    public int setMemberRole(long sectId, long playerId, String role) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return setMemberRole(conn, sectId, playerId, role);
        } catch (SQLException e) {
            throw new RuntimeException("任命失败", e);
        }
    }

    public int setMemberRole(Connection conn, long sectId, long playerId, String role) throws SQLException {
        String sql = "UPDATE sect_members SET role = ? WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            return ps.executeUpdate();
        }
    }

    /**
     * 加贡献值，并在贡献为正时同步给宗门加声望。
     *
     * <p>两条语句跑在<b>同一条连接</b>上（原实现即如此）：先改成员贡献，
     * 命中且 {@code amount > 0} 时再改宗门声望。</p>
     *
     * @return 成员表的受影响行数；0 表示该成员不在这个宗门里
     */
    public int addContribution(long sectId, long playerId, long amount) {
        try (Connection conn = DatabaseManager.getConnection()) {
            return addContribution(conn, sectId, playerId, amount);
        } catch (SQLException e) {
            throw new RuntimeException("更新贡献值失败", e);
        }
    }

    public int addContribution(Connection conn, long sectId, long playerId, long amount) throws SQLException {
        String sql = "UPDATE sect_members SET contribution = contribution + ? WHERE sect_id = ? AND player_id = ?";
        int updated;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setLong(2, sectId);
            ps.setLong(3, playerId);
            updated = ps.executeUpdate();
        }
        if (updated > 0 && amount > 0) {
            String prestigeSql = "UPDATE sects SET prestige = prestige + ? WHERE id = ?";
            try (PreparedStatement pps = conn.prepareStatement(prestigeSql)) {
                pps.setLong(1, amount);
                pps.setLong(2, sectId);
                pps.executeUpdate();
            }
        }
        return updated;
    }

    // ==================== 解散 ====================

    /**
     * 解散宗门：按固定顺序清四张表。
     *
     * <p><b>只有 Connection 版</b>：它只在 {@code runTransaction} 的 lambda 内被调用，
     * 四步必须同生共死。提供一个自取连接的重载只会诱导调用方在事务外用它 ——
     * 那等于把"半解散"的宗门留在库里。</p>
     *
     * <p>顺序固定为 {@code sect_warehouse → sect_applications → sect_members → sects}，
     * 与原实现四条语句的顺序逐字对应，没有调整。</p>
     */
    public void deleteSectCascade(Connection conn, long sectId) throws SQLException {
        String sql = "DELETE FROM sect_warehouse WHERE sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
        sql = "DELETE FROM sect_applications WHERE sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
        sql = "DELETE FROM sect_members WHERE sect_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
        sql = "DELETE FROM sects WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 映射 ====================

    /**
     * {@code sect_applications} 行（含 JOIN 出来的玩家名）→ 实体。
     *
     * <p>逐字搬自原服务包的 {@code SectSupport.mapApplication}：列读取顺序不变，
     * {@code player_name} 那一列保留容错 {@code try/catch}（老库缺列时退化为 null）。</p>
     */
    private static SectApplication mapApplication(ResultSet rs) throws SQLException {
        SectApplication a = new SectApplication();
        a.setId(rs.getLong("id"));
        a.setSectId(rs.getLong("sect_id"));
        a.setPlayerId(rs.getLong("player_id"));
        a.setMessage(rs.getString("message"));
        a.setStatus(rs.getString("status"));
        a.setCreatedAt(rs.getString("created_at"));
        try { a.setPlayerName(rs.getString("player_name")); } catch (SQLException ignored) {}
        return a;
    }
}
