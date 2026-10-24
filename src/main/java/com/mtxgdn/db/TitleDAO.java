package com.mtxgdn.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 称号（{@code player_titles} 表）的读写入口。
 *
 * <p>从 {@code TitleService} 抽出来的第 11 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p><b>为什么这里必须有一批 Connection 重载</b>：装备称号是
 * "卸下当前已装备的 → 把目标那个置为已装备"两步，原实现靠
 * {@code runTransaction} 把同一条 {@code conn} 传给两个 private 方法。
 * 抽 DAO 之后这两步也必须共用连接：各自提交时中间失败会留下
 * "一个都没装备"或"新旧两个都装备"的状态，而
 * {@code getEquippedTitle} 只取匹配的第一行，玩家看到的称号会随机漂移。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li>装备两步都是<b>条件更新</b>：卸下只动 {@code is_equipped = true} 的行，
 *       装备只动 {@code title_key} 匹配的那一行。两者都不假设"该玩家只有一行"；</li>
 *   <li>{@code unequipAll} 返回受影响行数，调用方据此决定文案是"已卸下"还是
 *       "当前没有装备中的称号" —— 这个行数是业务分支，不能丢；</li>
 *   <li>{@code findPlayerTitles} 的 {@code ORDER BY acquired_at DESC}
 *       决定列表顺序；"注册表里查不到就跳过该行"的过滤在服务层，
 *       保证脏 key 不会让整份列表失败；</li>
 *   <li>{@code grantTitle} 插入时 {@code is_equipped} 固定为 {@code false} ——
 *       授予绝不顶掉当前装备；</li>
 *   <li>{@code hasTitle} 查不到时返回 {@code false} 而不是抛异常 ——
 *       原实现在 {@code catch} 里吞掉 {@code SQLException} 返回 false。</li>
 * </ul>
 *
 * <p>称号是否存在于 {@code TitleRegistry}、是否已拥有、文案组装全在服务层，
 * 一律不进 DAO。</p>
 */
public class TitleDAO {

    // ==================== 查询 ====================

    /**
     * 玩家全部称号，按获得时间倒序。
     *
     * <p>每行是 {@code [titleKey, isEquipped, acquiredAt]}。
     * 这里刻意<b>不</b>做 {@code TitleRegistry} 查询：那会把静态注册表
     * 依赖拖进 DAO，而"查不到定义就跳过该行"是业务规则。</p>
     */
    public List<Object[]> findPlayerTitles(long playerId) {
        String sql = "SELECT title_key, is_equipped, acquired_at FROM player_titles WHERE player_id = ? ORDER BY acquired_at DESC";
        List<Object[]> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapTitleRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家称号列表失败", e);
        }
        return rows;
    }

    public List<Object[]> findPlayerTitles(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT title_key, is_equipped, acquired_at FROM player_titles WHERE player_id = ? ORDER BY acquired_at DESC";
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapTitleRow(rs));
                }
            }
        }
        return rows;
    }

    /** 当前装备的称号 key；没有装备返回 {@code null}。 */
    public String findEquippedTitleKey(long playerId) {
        String sql = "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setBoolean(2, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("title_key");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询当前装备称号失败", e);
        }
        return null;
    }

    public String findEquippedTitleKey(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT title_key FROM player_titles WHERE player_id = ? AND is_equipped = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setBoolean(2, true);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("title_key");
                }
            }
        }
        return null;
    }

    /** 玩家是否已拥有该称号（查询失败时保守返回 false）。 */
    public boolean hasTitle(long playerId, String titleKey) {
        String sql = "SELECT 1 FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean hasTitle(Connection conn, long playerId, String titleKey) throws SQLException {
        String sql = "SELECT 1 FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ==================== 写操作 ====================

    /** 授予称号（{@code is_equipped} 固定为 false，绝不顶掉当前装备）。 */
    public void grantTitle(long playerId, String titleKey) {
        String sql = "INSERT INTO player_titles (player_id, title_key, is_equipped) VALUES (?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            ps.setBoolean(3, false);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("授予称号失败", e);
        }
    }

    public void grantTitle(Connection conn, long playerId, String titleKey) throws SQLException {
        String sql = "INSERT INTO player_titles (player_id, title_key, is_equipped) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            ps.setBoolean(3, false);
            ps.executeUpdate();
        }
    }

    /**
     * 撤销称号。
     *
     * @return 是否真的删掉了行（调用方据此区分"撤销成功"与"玩家本来就没有"）
     */
    public boolean revokeTitle(long playerId, String titleKey) {
        String sql = "DELETE FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("撤销称号失败", e);
        }
    }

    public boolean revokeTitle(Connection conn, long playerId, String titleKey) throws SQLException {
        String sql = "DELETE FROM player_titles WHERE player_id = ? AND title_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, titleKey);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 卸下当前装备的称号。
     *
     * <p>条件是 {@code is_equipped = true} 而不是 {@code title_key = ?}：
     * 调用方只想知道"有没有装备中的称号"，并不关心是哪一个。</p>
     *
     * @return 受影响行数，0 表示当前没有装备中的称号
     */
    public int unequipAll(long playerId) {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND is_equipped = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            ps.setBoolean(3, true);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("卸下称号失败", e);
        }
    }

    public int unequipAll(Connection conn, long playerId) throws SQLException {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND is_equipped = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, false);
            ps.setLong(2, playerId);
            ps.setBoolean(3, true);
            return ps.executeUpdate();
        }
    }

    /** 装备指定称号（只动 {@code title_key} 匹配的那一行）。 */
    public void equipTitle(long playerId, String titleKey) {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND title_key = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true);
            ps.setLong(2, playerId);
            ps.setString(3, titleKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("装备称号失败", e);
        }
    }

    public void equipTitle(Connection conn, long playerId, String titleKey) throws SQLException {
        String sql = "UPDATE player_titles SET is_equipped = ? WHERE player_id = ? AND title_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true);
            ps.setLong(2, playerId);
            ps.setString(3, titleKey);
            ps.executeUpdate();
        }
    }

    // ==================== ResultSet → 行 ====================

    /** 一行读成 {@code [titleKey, isEquipped, acquiredAt]}。 */
    private Object[] mapTitleRow(ResultSet rs) throws SQLException {
        return new Object[]{
                rs.getString("title_key"),
                rs.getBoolean("is_equipped"),
                rs.getString("acquired_at")};
    }
}
