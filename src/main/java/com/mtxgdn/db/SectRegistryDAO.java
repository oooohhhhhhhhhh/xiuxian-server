package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 宗门建档（{@code sects} 表那一行的创建/升级/转让，以及解散时的连带删除）
 * 的读写入口。
 *
 * <p>从 {@code SectRegistryDomain} 抽出来的 DAO。每个方法一对重载：
 * {@code xxx(...)} 自己开一条短连接并把 {@link SQLException} 包成
 * {@link RuntimeException}；{@code xxx(Connection conn, ...)} 只用调用方的连接、
 * 直接抛 {@link SQLException}。</p>
 *
 * <p><b>为什么这里必须有 Connection 重载</b>：这个域的三段写操作
 * （创建宗门、宗主转让、解散宗门）<b>原本就各是一个
 * {@code runTransaction}</b>，块内三到四步必须共用同一条 {@code conn}：
 * 一是"扣了灵石却没建出宗门"必须整体回滚，二是 SQLite 的
 * {@code BEGIN DEFERRED} 已持写锁，再开连接就是 {@code SQLITE_BUSY_SNAPSHOT}
 * （不受 {@code busy_timeout} 约束），三是解散时的四条 DELETE 必须原子完成，
 * 否则会留下"没有宗门却有成员"的孤儿行。所以事务内的每一步都有一个
 * {@code conn} 重载，事务块本身留在服务层 —— <b>DAO 不碰事务</b>。</p>
 *
 * <p><b>哪些不显然的原语义被保留了</b>：</p>
 * <ul>
 *   <li><b>初始声望写死 1000</b>：创建宗门时 {@code prestige} 列的值是字面量
 *       {@code 1000L}（注释说明这是为了让宗门升级/宗门战在创建后即可积累使用，
 *       避免声望死锁）。它不是配置项，DAO 也不该从 {@code AppConfig} 读。</li>
 *   <li><b>宗门 id 由调用方从 generated keys 里取</b>：{@code createSect} 返回新行的
 *       自增 id，调用方紧接着用它插 {@code sect_members} 的 LEADER 行。
 *       原实现在 {@code keys.next()} 里做这件事，并把"直接构造 {@code Sect} 返回"
 *       的注释保留了下来（事务内跨连接查询读不到未提交数据）。</li>
 *   <li><b>升级的三列一起改</b>：{@code level} / {@code prestige - cost} /
 *       {@code max_members} 在同一条 UPDATE 里，且<b>不带</b>声望是否足够的条件
 *       —— 校验在服务层（{@code sect.getPrestige() < cost} 就直接返回失败文案），
 *       所以这里没有 {@code AND prestige >= ?}。</li>
 *   <li><b>解散的四条 DELETE 有顺序</b>：先 {@code sect_warehouse} /
 *       {@code sect_applications} / {@code sect_members}，最后才是 {@code sects}。
 *       外键约束（若启用）要求父行最后删，因此这个顺序不能重排。</li>
 *   <li><b>转让是三条 UPDATE</b>：先换 {@code sects.leader_player_id}，
 *       再把旧宗主降成 {@code VICE_LEADER}、把新宗主升成 {@code LEADER}。
 *       三个角色名都是字面量，逐字保留。</li>
 *   <li><b>异常一律包成 {@link RuntimeException}</b>：原实现在事务块内用
 *       {@code catch (SQLException e) { throw new RuntimeException(e); }}，
 *       文案为空。DAO 的 {@code conn} 版则直接抛 {@link SQLException}，
 *       由事务统一回滚 —— 文案差异不影响行为（都会被
 *       {@code runTransaction} 转成"事务执行失败"）。</li>
 * </ul>
 */
public class SectRegistryDAO {

    // ==================== 创建 ====================

    /**
     * 插入一行新宗门，返回它的自增 id。
     *
     * <p>初始声望写死 {@code 1000}（见类注释）。本方法<b>只</b>插 {@code sects}，
     * 插 LEADER 成员行由调用方用返回的 id 接着做 —— 两步必须共享同一条连接，
     * 否则新宗门的成员行会落在另一条连接上单独提交。</p>
     *
     * @return 新宗门 id；没拿到 generated keys 时返回 {@code -1}
     */
    public long createSect(Connection conn, String name, String description, long leaderPlayerId)
            throws SQLException {
        String sql = "INSERT INTO sects (name, description, leader_player_id, prestige) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description != null ? description : "一个新兴的修仙宗门");
            ps.setLong(3, leaderPlayerId);
            // 初始声望 1000：使宗门升级/宗门战在创建后即可积累使用，避免声望死锁
            ps.setLong(4, 1000L);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                return keys.getLong(1);
            }
        }
        return -1;
    }

    /**
     * 插入宗主的成员行（{@code role = 'LEADER'}）。
     */
    public void insertLeaderMember(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "INSERT INTO sect_members (sect_id, player_id, role) VALUES (?, ?, 'LEADER')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 升级 ====================

    /** 宗门升级：等级 +1、扣声望、按新等级重算人数上限。 */
    public void levelUp(long sectId, int newLevel, long cost, int newMaxMembers) {
        try (Connection conn = DatabaseManager.getConnection()) {
            levelUp(conn, sectId, newLevel, cost, newMaxMembers);
        } catch (SQLException e) {
            throw new RuntimeException("宗门升级失败", e);
        }
    }

    public void levelUp(Connection conn, long sectId, int newLevel, long cost, int newMaxMembers)
            throws SQLException {
        String sql = "UPDATE sects SET level = ?, prestige = prestige - ?, max_members = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newLevel);
            ps.setLong(2, cost);
            ps.setInt(3, newMaxMembers);
            ps.setLong(4, sectId);
            ps.executeUpdate();
        }
    }

    // ==================== 宗主转让（事务内使用） ====================

    /** 换 {@code sects.leader_player_id}。 */
    public void setLeader(Connection conn, long sectId, long newLeaderPlayerId) throws SQLException {
        String sql = "UPDATE sects SET leader_player_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, newLeaderPlayerId);
            ps.setLong(2, sectId);
            ps.executeUpdate();
        }
    }

    /** 把旧宗主降为副宗主。 */
    public void demoteToViceLeader(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "UPDATE sect_members SET role = 'VICE_LEADER' WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    /** 把新宗主升为宗主。 */
    public void promoteToLeader(Connection conn, long sectId, long playerId) throws SQLException {
        String sql = "UPDATE sect_members SET role = 'LEADER' WHERE sect_id = ? AND player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sectId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    // ==================== 解散（事务内使用） ====================

    /** 清空宗门仓库。 */
    public void deleteWarehouse(Connection conn, long sectId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sect_warehouse WHERE sect_id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
    }

    /** 清空宗门的入宗申请。 */
    public void deleteApplications(Connection conn, long sectId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sect_applications WHERE sect_id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
    }

    /** 清空宗门成员行。 */
    public void deleteMembers(Connection conn, long sectId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sect_members WHERE sect_id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
    }

    /** 删除宗门本身（必须最后一步：子表都清完再删父行）。 */
    public void deleteSect(Connection conn, long sectId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sects WHERE id = ?")) {
            ps.setLong(1, sectId);
            ps.executeUpdate();
        }
    }
}
