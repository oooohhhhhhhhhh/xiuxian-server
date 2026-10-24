package com.mtxgdn.db;

import com.mtxgdn.game.entity.Formation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 阵法（{@code formations} 表）的读写入口。
 *
 * <p>从 {@code FormationService} 抽出来的第 10 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询阵法失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p><b>为什么这里必须有一批 Connection 重载</b>：{@code placeFormation} /
 * {@code upgradeFormation} / {@code removeFormation} /
 * {@code placeFormationFromTreasure} 四步都是"扣灵石 → 写阵法"的事务，
 * 而 {@code deactivate} 既被事务内的 {@code removeFormation} 调用，
 * 也被事务外的 {@code deactivateFormation}（读到过期阵法时让它当场失效）调用。
 * 两处都必须落在同一条连接上：失效与布置如果是两次独立提交，
 * 中间失败就会留下"库里还挂着 active=1 的过期阵法"，玩家再也布不下新的。</p>
 *
 * <p><b>保留了哪些不显然的原语义</b>：</p>
 * <ul>
 *   <li>{@code findActiveFormation} 的 {@code ORDER BY expires_at DESC LIMIT 1}
 *       是"多行并存时取最晚过期的那个"，不是随手写的；</li>
 *   <li>插入语句带 {@link Statement#RETURN_GENERATED_KEYS}（原实现即如此，
 *       虽然取出后并未读取自增主键），照原样保留；</li>
 *   <li>{@code updated_at = CURRENT_TIMESTAMP} 只在升级时写，
 *       插入与失效都不碰它 —— 这三条语句的列清单刻意不一致；</li>
 *   <li>{@code expireDueFormations} 的 {@code expires_at < ?} 是严格小于，
 *       等于临界值的那一毫秒不算过期。</li>
 * </ul>
 *
 * <p>阵法效果数值（{@code Formation.getSpiritEnergyBoostForLevel} 等）、
 * 灵石扣减、{@code PlayerDataService.markDirty}、内置模块门控全在服务层，
 * 一律不进 DAO。</p>
 */
public class FormationDAO {

    // ==================== 查询 ====================

    /**
     * 当前生效的阵法；没有生效中的返回 {@code null}。
     *
     * <p>原实现把"过期就失效并返回 null"的判断放在服务层，
     * 这里只负责把行读出来。</p>
     */
    public Formation findActiveFormation(long playerId) {
        String sql = "SELECT * FROM formations WHERE player_id = ? AND active = 1 ORDER BY expires_at DESC LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapFormation(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询阵法失败", e);
        }
        return null;
    }

    public Formation findActiveFormation(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM formations WHERE player_id = ? AND active = 1 ORDER BY expires_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapFormation(rs);
                }
            }
        }
        return null;
    }

    // ==================== 写操作 ====================

    /**
     * 布置一条阵法（{@code active} 固定写 1）。
     *
     * <p>参数里的名称与各项加成数值都由服务层算好后传入，
     * DAO 不做任何数值推导。</p>
     */
    public void insertFormation(long playerId, String formationKey, String name, int level,
                                int spiritEnergyBoost, int cultivationBonus, int defenseBonus,
                                int heartDemonResist, int durationMinutes,
                                long placedAt, long expiresAt) {
        String sql = insertSql();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, playerId, formationKey, name, level, spiritEnergyBoost, cultivationBonus,
                    defenseBonus, heartDemonResist, durationMinutes, placedAt, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("布置阵法失败", e);
        }
    }

    public void insertFormation(Connection conn, long playerId, String formationKey, String name,
                                int level, int spiritEnergyBoost, int cultivationBonus,
                                int defenseBonus, int heartDemonResist, int durationMinutes,
                                long placedAt, long expiresAt) throws SQLException {
        String sql = insertSql();
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, playerId, formationKey, name, level, spiritEnergyBoost, cultivationBonus,
                    defenseBonus, heartDemonResist, durationMinutes, placedAt, expiresAt);
            ps.executeUpdate();
        }
    }

    private String insertSql() {
        return "INSERT INTO formations (player_id, formation_key, name, level, " +
                "spirit_energy_boost, cultivation_bonus, defense_bonus, heart_demon_resist, " +
                "duration_minutes, placed_at, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";
    }

    private void bindInsert(PreparedStatement ps, long playerId, String formationKey, String name,
                            int level, int spiritEnergyBoost, int cultivationBonus,
                            int defenseBonus, int heartDemonResist, int durationMinutes,
                            long placedAt, long expiresAt) throws SQLException {
        ps.setLong(1, playerId);
        ps.setString(2, formationKey);
        ps.setString(3, name);
        ps.setInt(4, level);
        ps.setInt(5, spiritEnergyBoost);
        ps.setInt(6, cultivationBonus);
        ps.setInt(7, defenseBonus);
        ps.setInt(8, heartDemonResist);
        ps.setInt(9, durationMinutes);
        ps.setLong(10, placedAt);
        ps.setLong(11, expiresAt);
    }

    /**
     * 升级：只改等级与四项加成，并刷新 {@code updated_at}。
     *
     * <p>注意这条语句<b>不</b>碰 {@code expires_at} / {@code duration_minutes}
     * —— 升级不延长阵法的剩余时间，这是原实现的语义。</p>
     */
    public void updateFormationStats(long formationId, int level, int spiritEnergyBoost,
                                     int cultivationBonus, int defenseBonus, int heartDemonResist) {
        String sql = "UPDATE formations SET level = ?, spirit_energy_boost = ?, " +
                "cultivation_bonus = ?, defense_bonus = ?, heart_demon_resist = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindStats(ps, formationId, level, spiritEnergyBoost, cultivationBonus, defenseBonus, heartDemonResist);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("升级阵法失败", e);
        }
    }

    public void updateFormationStats(Connection conn, long formationId, int level, int spiritEnergyBoost,
                                     int cultivationBonus, int defenseBonus, int heartDemonResist)
            throws SQLException {
        String sql = "UPDATE formations SET level = ?, spirit_energy_boost = ?, " +
                "cultivation_bonus = ?, defense_bonus = ?, heart_demon_resist = ?, " +
                "updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindStats(ps, formationId, level, spiritEnergyBoost, cultivationBonus, defenseBonus, heartDemonResist);
            ps.executeUpdate();
        }
    }

    private void bindStats(PreparedStatement ps, long formationId, int level, int spiritEnergyBoost,
                           int cultivationBonus, int defenseBonus, int heartDemonResist)
            throws SQLException {
        ps.setInt(1, level);
        ps.setInt(2, spiritEnergyBoost);
        ps.setInt(3, cultivationBonus);
        ps.setInt(4, defenseBonus);
        ps.setInt(5, heartDemonResist);
        ps.setLong(6, formationId);
    }

    /**
     * 把一条阵法置为失效（{@code active = 0}）。
     *
     * <p>拆除与"读到过期阵法时当场失效"共用这一条语句，
     * 因此它既有自取连接的重载也有 Connection 重载。</p>
     */
    public void deactivate(long formationId) {
        String sql = "UPDATE formations SET active = 0 WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, formationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("阵法失效失败", e);
        }
    }

    public void deactivate(Connection conn, long formationId) throws SQLException {
        String sql = "UPDATE formations SET active = 0 WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, formationId);
            ps.executeUpdate();
        }
    }

    /**
     * 定时结算：把所有已到期的生效阵法一次性置为失效。
     *
     * <p>比较用的是严格小于（{@code expires_at < ?}），等于临界值的那一毫秒
     * 不算过期 —— 与原实现一致。</p>
     */
    public int expireDueFormations(long now) {
        String sql = "UPDATE formations SET active = 0 WHERE active = 1 AND expires_at < ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("阵法定时检查失败", e);
        }
    }

    public int expireDueFormations(Connection conn, long now) throws SQLException {
        String sql = "UPDATE formations SET active = 0 WHERE active = 1 AND expires_at < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            return ps.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    private Formation mapFormation(ResultSet rs) throws SQLException {
        Formation f = new Formation();
        f.setId(rs.getLong("id"));
        f.setPlayerId(rs.getLong("player_id"));
        f.setFormationKey(rs.getString("formation_key"));
        f.setName(rs.getString("name"));
        f.setLevel(rs.getInt("level"));
        f.setSpiritEnergyBoost(rs.getInt("spirit_energy_boost"));
        f.setCultivationBonus(rs.getInt("cultivation_bonus"));
        f.setDefenseBonus(rs.getInt("defense_bonus"));
        f.setHeartDemonResist(rs.getInt("heart_demon_resist"));
        f.setDurationMinutes(rs.getInt("duration_minutes"));
        f.setPlacedAt(rs.getLong("placed_at"));
        f.setExpiresAt(rs.getLong("expires_at"));
        f.setActive(rs.getBoolean("active"));
        return f;
    }
}
