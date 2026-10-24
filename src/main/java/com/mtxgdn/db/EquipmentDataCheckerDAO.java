package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动期装备数据自检（{@code players} / {@code players_equipment} /
 * {@code players_items}）的读写入口。
 *
 * <p>从 {@code EquipmentDataChecker} 抽出来的 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"获取玩家列表失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。</li>
 * </ul>
 *
 * <p><b>为什么需要 Connection 重载</b>：{@code checkPlayerEquipment} 是
 * "读装备 → 按玩家逐个修"的一次性体检，原实现把整个循环体包在
 * {@code DatabaseManager.runTransaction} 的 lambda 里，然后把 {@code conn}
 * 手工传给五个 private 方法（{@code getEquipment} / {@code getEquipmentKeys} /
 * {@code getEquipmentTypeItemsInBackpack} / {@code getItemCount} /
 * {@code removeFromEquipment} / {@code removeItem}）。这样设计的理由是：
 * 一个玩家的检查要么全部修完，要么一条都不改 —— 修到一半崩掉会留下
 * "槽位删了但背包没扣"的中间态。抽 DAO 之后如果让这些读写各自自取连接，
 * 每次调用都是独立提交，事务就名存实亡了。</p>
 *
 * <p><b>不显然的原语义</b>：</p>
 * <ul>
 *   <li>{@code getAllPlayerIds} 是<b>唯一</b>自取连接的方法：它在事务之外
 *       先取到玩家清单，再逐个开事务修。原实现就是这样，逐字保留。</li>
 *   <li>查询失败时原实现只打 {@code LOG.error} 并返回空列表 / 0，
 *       不往上抛 —— 一个玩家读失败不该让整个启动自检中断。</li>
 *   <li>{@code removeItem} 是"先条件扣减、再清理零数量行"两步，
 *       两步必须共用同一条连接：分开两次连接时，清理可能落在另一次提交里，
 *       中间失败会留下 {@code quantity = 0} 的僵尸行。</li>
 *   <li>{@code quantity >= ?} 让"检查数量"和"扣除"在一条 UPDATE 里完成，
 *       并发下不会扣成负数。</li>
 *   <li>{@code getEquipmentTypeItemsInBackpack} 的 SELECT 带了
 *       {@code quantity > 0}，读出来的结果<b>只用于打日志</b>
 *       （提示"背包里有装备但未装备"），不参与修复。</li>
 * </ul>
 */
public class EquipmentDataCheckerDAO {

    /** 全部玩家 id（自取连接：它在事务之外，先取清单再逐个修）。 */
    public List<Long> findAllPlayerIds() {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM players";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取玩家列表失败", e);
        }
        return ids;
    }

    /** 某个玩家的装备（slot → item_key）。 */
    public Map<String, String> getEquipment(Connection conn, long playerId) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        String sql = "SELECT slot, item_key FROM players_equipment WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("slot"), rs.getString("item_key"));
                }
            }
        }
        return result;
    }

    /** 某个玩家已装备的 item_key 列表。 */
    public List<String> getEquipmentKeys(Connection conn, long playerId) throws SQLException {
        List<String> keys = new ArrayList<>();
        String sql = "SELECT item_key FROM players_equipment WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("item_key"));
                }
            }
        }
        return keys;
    }

    /**
     * 背包里 {@code quantity > 0} 的 item_key 列表。
     *
     * <p>原实现读出来只用于打日志（提示"背包里有装备但未装备"），
     * 不参与修复。</p>
     */
    public List<String> getBackpackItemKeys(Connection conn, long playerId) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT item_key, quantity FROM players_items WHERE player_id = ? AND quantity > 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("item_key"));
                }
            }
        }
        return result;
    }

    /** 背包里某个物品的数量；没有该行返回 0。 */
    public long getItemCount(Connection conn, long playerId, String itemKey) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity");
                }
            }
        }
        return 0;
    }

    /** 删掉某个槽位的装备记录（槽位指向的物品 key 在注册表里不存在时）。 */
    public void removeFromEquipment(Connection conn, long playerId, String slot) throws SQLException {
        String sql = "DELETE FROM players_equipment WHERE player_id = ? AND slot = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, slot);
            ps.executeUpdate();
        }
    }

    /**
     * 从背包里扣掉一个物品，并清理归零的行。
     *
     * <p>两步必须共用同一条连接：条件扣减（{@code quantity >= ?} 消除 TOCTOU）
     * 与清理零数量行分开两次连接时，清理可能落在另一次提交里，
     * 中间失败会留下 {@code quantity = 0} 的僵尸行。</p>
     */
    public void removeItem(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE player_id = ? AND item_key = ? AND quantity >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.setLong(4, quantity);
            ps.executeUpdate();

            String cleanSql = "DELETE FROM players_items WHERE player_id = ? AND item_key = ? AND quantity <= 0";
            try (PreparedStatement cleanPs = conn.prepareStatement(cleanSql)) {
                cleanPs.setLong(1, playerId);
                cleanPs.setString(2, itemKey);
                cleanPs.executeUpdate();
            }
        }
    }
}
