package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 强化等级（{@code players_equipment.enhance_level} 列）的读写入口。
 *
 * <p>从 {@code EnhanceService} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询强化等级失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：这是本 DAO 存在的核心理由。
 * {@code EnhanceService.unequipItem(conn, ...)}（强化碎裂）在<b>同一条连接</b>上
 * 依次做四件事：读 item_key → 读 enhance_level → 逐项回退玩家属性 →
 * {@code DELETE FROM players_equipment}。原实现靠把 {@code conn} 手工传给三个
 * private 方法保证这四步同连接同事务 —— "删装备行"与"扣属性"要么一起成功，
 * 要么一起回滚。抽 DAO 之后如果让它们各自自取连接，第 4 步回滚时前三步
 * 已经单独提交了：装备从身上消失了，但它的属性加成还实打实加在
 * {@code players.attack/defense/speed/spirit} 上，玩家可以靠"强化到高等级再碎掉"
 * 永久白嫖一份属性，而这份属性不在任何界面上显示为来源。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>查询失败时原实现只打 {@code LOG.error} 然后<b>降级</b>：
 *       {@code getEnhanceLevel} 返回 0，{@code getEquippedItemKey} 返回 null。
 *       调用方把 null 当成"这个槽位没有装备"。所以这两个自取连接的重载
 *       不抛 RuntimeException —— 抛出去会让整个强化流程变成一个异常，
 *       而降级语义是"查不到就当成没有"。</li>
 *   <li>{@code enhance_level} 与 {@code item_key} 在同一个表里，
 *       两个查询的 WHERE 子句逐字相同（{@code player_id = ? AND slot = ?}）。</li>
 *   <li>{@code DELETE} 没有 {@code AND enhance_level = ?} 之类的乐观锁条件：
 *       原实现就是这样，逐字保留。</li>
 * </ul>
 */
public class EnhanceDAO {

    /**
     * 某个槽位当前的强化等级；未装备或查询失败时返回 0。
     */
    public int getEnhanceLevel(long playerId, String slot) {
        String sql = "SELECT enhance_level FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("enhance_level");
                }
            }
        } catch (SQLException e) {
            throw new EnhanceQueryException("查询强化等级失败: " + e.getMessage(), e);
        }
        return 0;
    }

    public int getEnhanceLevel(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "SELECT enhance_level FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("enhance_level");
                }
            }
        }
        return 0;
    }

    /**
     * 某个槽位当前装备的 item_key；未装备返回 null。
     */
    public String getEquippedItemKey(long playerId, String slot) {
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("item_key");
                }
            }
        } catch (SQLException e) {
            throw new EnhanceQueryException("查询装备失败: " + e.getMessage(), e);
        }
        return null;
    }

    public String getEquippedItemKey(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("item_key");
                }
            }
        }
        return null;
    }

    /**
     * 覆盖某个槽位的强化等级。
     *
     * <p><b>只在事务里用</b>：升级/降级/碎裂都发生在
     * {@code EnhanceService.enhanceItem} 的 {@code runTransaction} lambda 内，
     * 这一步必须和扣金币、扣强化石同生共死。</p>
     */
    public void setEnhanceLevel(Connection conn, long playerId, String slot, int level) throws SQLException {
        String sql = "UPDATE players_equipment SET enhance_level = ? WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setLong(2, playerId);
            ps.setString(3, slot);
            ps.executeUpdate();
        }
    }

    /**
     * 删掉某个槽位的装备记录（强化碎裂）。
     *
     * <p><b>只在事务里用</b>，且必须与调用方的属性回退走同一条连接：
     * 见类注释里"白嫖属性"那段。原实现的 DELETE 没有任何额外的
     * 乐观锁条件，逐字保留。</p>
     */
    public void deleteEquipment(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }
    }

    /**
     * 强化查询失败时抛出，让调用方能降级成"没有装备 / 等级 0"。
     *
     * <p>单独一个异常类型是为了让 {@code EnhanceService} 能精确地只捕获
     * 这一类失败做降级，而不至于把事务里真正的业务异常也一起吞掉。</p>
     */
    public static class EnhanceQueryException extends RuntimeException {
        public EnhanceQueryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
