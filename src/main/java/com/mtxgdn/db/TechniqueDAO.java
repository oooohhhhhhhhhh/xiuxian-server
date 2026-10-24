package com.mtxgdn.db;

import com.mtxgdn.game.entity.Technique;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 功法（{@code techniques} / {@code players_techniques}）的读写入口。
 *
 * <p>从 {@code TechniqueService} 抽出来的第 5 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询功法失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code TechniqueService}，包括 {@code INNER JOIN} 的写法、
 * {@code ORDER BY} 的顺序、以及 {@code proficiency = proficiency + ?} 这种
 * 依赖数据库自增的表达式 —— 一律没有"顺手优化"。</p>
 */
public class TechniqueDAO {

    // ==================== 功法定义 ====================

    /** 按 id 查功法定义（不含玩家的 level/proficiency/is_equipped）。 */
    public Technique findById(long techniqueId) {
        String sql = "SELECT * FROM techniques WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapTechnique(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询功法失败", e); }
        return null;
    }

    public Technique findById(Connection conn, long techniqueId) throws SQLException {
        String sql = "SELECT * FROM techniques WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapTechnique(rs);
            }
        }
        return null;
    }

    /** 全部功法定义，按 required_realm, id 排序。 */
    public List<Technique> findAll() {
        String sql = "SELECT * FROM techniques ORDER BY required_realm, id";
        List<Technique> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapTechnique(rs));
        } catch (SQLException e) { throw new RuntimeException("查询功法列表失败", e); }
        return result;
    }

    public List<Technique> findAll(Connection conn) throws SQLException {
        String sql = "SELECT * FROM techniques ORDER BY required_realm, id";
        List<Technique> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapTechnique(rs));
        }
        return result;
    }

    /** 功法名是否已存在（出厂数据按名称幂等补齐时用）。 */
    public boolean existsByName(String name) {
        String sql = "SELECT COUNT(*) FROM techniques WHERE name = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) { throw new RuntimeException("查询功法名称失败", e); }
        return false;
    }

    public boolean existsByName(Connection conn, String name) throws SQLException {
        String sql = "SELECT COUNT(*) FROM techniques WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    /** 插入一条功法定义。 */
    public void insertDefinition(String name, String description, int requiredRealm,
                                 long learnCostGold, long learnCostSpiritStones,
                                 int upgradeBaseCostGold, int upgradeBaseCostSpiritStones,
                                 String type, int maxLevel,
                                 int hpBonus, int mpBonus, int attackBonus, int defenseBonus,
                                 int speedBonus, int spiritBonus,
                                 double cultivationSpeedBonus, double expBonus,
                                 double combatDamageBonus, double damageReduction) {
        String sql = """
            INSERT INTO techniques (name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones,
                upgrade_base_cost_gold, upgrade_base_cost_spirit_stones, type, max_level,
                hp_bonus, mp_bonus, attack_bonus, defense_bonus, speed_bonus, spirit_bonus,
                cultivation_speed_bonus, exp_bonus, combat_damage_bonus, damage_reduction)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDefinition(ps, name, description, requiredRealm, learnCostGold, learnCostSpiritStones,
                    upgradeBaseCostGold, upgradeBaseCostSpiritStones, type, maxLevel,
                    hpBonus, mpBonus, attackBonus, defenseBonus, speedBonus, spiritBonus,
                    cultivationSpeedBonus, expBonus, combatDamageBonus, damageReduction);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("初始化功法失败", e); }
    }

    public void insertDefinition(Connection conn, String name, String description, int requiredRealm,
                                 long learnCostGold, long learnCostSpiritStones,
                                 int upgradeBaseCostGold, int upgradeBaseCostSpiritStones,
                                 String type, int maxLevel,
                                 int hpBonus, int mpBonus, int attackBonus, int defenseBonus,
                                 int speedBonus, int spiritBonus,
                                 double cultivationSpeedBonus, double expBonus,
                                 double combatDamageBonus, double damageReduction) throws SQLException {
        String sql = """
            INSERT INTO techniques (name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones,
                upgrade_base_cost_gold, upgrade_base_cost_spirit_stones, type, max_level,
                hp_bonus, mp_bonus, attack_bonus, defense_bonus, speed_bonus, spirit_bonus,
                cultivation_speed_bonus, exp_bonus, combat_damage_bonus, damage_reduction)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindDefinition(ps, name, description, requiredRealm, learnCostGold, learnCostSpiritStones,
                    upgradeBaseCostGold, upgradeBaseCostSpiritStones, type, maxLevel,
                    hpBonus, mpBonus, attackBonus, defenseBonus, speedBonus, spiritBonus,
                    cultivationSpeedBonus, expBonus, combatDamageBonus, damageReduction);
            ps.executeUpdate();
        }
    }

    private void bindDefinition(PreparedStatement ps, String name, String description, int requiredRealm,
                                long learnCostGold, long learnCostSpiritStones,
                                int upgradeBaseCostGold, int upgradeBaseCostSpiritStones,
                                String type, int maxLevel,
                                int hpBonus, int mpBonus, int attackBonus, int defenseBonus,
                                int speedBonus, int spiritBonus,
                                double cultivationSpeedBonus, double expBonus,
                                double combatDamageBonus, double damageReduction) throws SQLException {
        ps.setString(1, name);
        ps.setString(2, description);
        ps.setInt(3, requiredRealm);
        ps.setLong(4, learnCostGold);
        ps.setLong(5, learnCostSpiritStones);
        ps.setInt(6, upgradeBaseCostGold);
        ps.setInt(7, upgradeBaseCostSpiritStones);
        ps.setString(8, type);
        ps.setInt(9, maxLevel);
        ps.setInt(10, hpBonus);
        ps.setInt(11, mpBonus);
        ps.setInt(12, attackBonus);
        ps.setInt(13, defenseBonus);
        ps.setInt(14, speedBonus);
        ps.setInt(15, spiritBonus);
        ps.setDouble(16, cultivationSpeedBonus);
        ps.setDouble(17, expBonus);
        ps.setDouble(18, combatDamageBonus);
        ps.setDouble(19, damageReduction);
    }

    // ==================== 玩家的功法 ====================

    /** 玩家已学的全部功法（含 level/proficiency/is_equipped）。 */
    public List<Technique> findPlayerTechniques(long playerId) {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ?
            ORDER BY t.id
            """;
        List<Technique> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapTechniqueFull(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询玩家功法失败", e); }
        return result;
    }

    public List<Technique> findPlayerTechniques(Connection conn, long playerId) throws SQLException {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ?
            ORDER BY t.id
            """;
        List<Technique> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapTechniqueFull(rs));
            }
        }
        return result;
    }

    /** 玩家当前装备的功法。 */
    public List<Technique> findEquipped(long playerId) {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ? AND pt.is_equipped = 1
            ORDER BY t.id
            """;
        List<Technique> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapTechniqueFull(rs));
            }
        } catch (SQLException e) { throw new RuntimeException("查询已装备功法失败", e); }
        return result;
    }

    public List<Technique> findEquipped(Connection conn, long playerId) throws SQLException {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ? AND pt.is_equipped = 1
            ORDER BY t.id
            """;
        List<Technique> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapTechniqueFull(rs));
            }
        }
        return result;
    }

    /** 单个玩家功法的详情（含等级、熟练度、是否装备）。 */
    public Technique findPlayerTechniqueDetail(long playerId, long techniqueId) {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ? AND pt.technique_id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapTechniqueFull(rs);
            }
        } catch (SQLException e) { throw new RuntimeException("查询功法详情失败", e); }
        return null;
    }

    public Technique findPlayerTechniqueDetail(Connection conn, long playerId, long techniqueId) throws SQLException {
        String sql = """
            SELECT t.*, pt.level, pt.proficiency, pt.is_equipped FROM techniques t
            INNER JOIN players_techniques pt ON t.id = pt.technique_id
            WHERE pt.player_id = ? AND pt.technique_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapTechniqueFull(rs);
            }
        }
        return null;
    }

    /** 玩家是否已学这门功法。 */
    public boolean hasTechnique(long playerId, long techniqueId) {
        String sql = "SELECT COUNT(*) FROM players_techniques WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) { throw new RuntimeException("查询功法状态失败", e); }
        return false;
    }

    public boolean hasTechnique(Connection conn, long playerId, long techniqueId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players_techniques WHERE player_id = ? AND technique_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        }
        return false;
    }

    /** 玩家当前装备的功法数量（上限判断用）。 */
    public int countEquipped(long playerId) {
        String sql = "SELECT COUNT(*) FROM players_techniques WHERE player_id = ? AND is_equipped = 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException e) { throw new RuntimeException("查询装备功法数量失败", e); }
        return 0;
    }

    public int countEquipped(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players_techniques WHERE player_id = ? AND is_equipped = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        }
        return 0;
    }

    // ==================== 写操作（事务内一律用 Connection 重载） ====================

    /** 学会一门功法（初始 level 1 / proficiency 0 / 未装备）。 */
    public void insertPlayerTechnique(long playerId, long techniqueId) {
        String sql = "INSERT INTO players_techniques (player_id, technique_id, level, proficiency, is_equipped) VALUES (?, ?, 1, 0, 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("学习功法失败", e); }
    }

    public void insertPlayerTechnique(Connection conn, long playerId, long techniqueId) throws SQLException {
        String sql = "INSERT INTO players_techniques (player_id, technique_id, level, proficiency, is_equipped) VALUES (?, ?, 1, 0, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        }
    }

    /** 装备 / 卸下。 */
    public void setEquipped(long playerId, long techniqueId, boolean equipped) {
        String sql = equipped
                ? "UPDATE players_techniques SET is_equipped = 1 WHERE player_id = ? AND technique_id = ?"
                : "UPDATE players_techniques SET is_equipped = 0 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(equipped ? "装备功法失败" : "卸下功法失败", e); }
    }

    public void setEquipped(Connection conn, long playerId, long techniqueId, boolean equipped) throws SQLException {
        String sql = equipped
                ? "UPDATE players_techniques SET is_equipped = 1 WHERE player_id = ? AND technique_id = ?"
                : "UPDATE players_techniques SET is_equipped = 0 WHERE player_id = ? AND technique_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        }
    }

    /** 升级：等级 +1，熟练度清零。 */
    public void levelUp(long playerId, long techniqueId) {
        String sql = "UPDATE players_techniques SET level = level + 1, proficiency = 0 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("功法升级失败", e); }
    }

    public void levelUp(Connection conn, long playerId, long techniqueId) throws SQLException {
        String sql = "UPDATE players_techniques SET level = level + 1, proficiency = 0 WHERE player_id = ? AND technique_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        }
    }

    /**
     * 增加熟练度。
     *
     * <p>原实现里这条语句失败只记日志不抛异常（`addProficiency` 是旁路累积，
     * 不该让一次失败打断修炼主流程），所以这里单独保留"自取连接 + 只记日志"
     * 的语义：失败时不向上抛。</p>
     */
    public void addProficiency(long playerId, long techniqueId, int amount) {
        String sql = "UPDATE players_techniques SET proficiency = proficiency + ? WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.setLong(3, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) {
            com.mtxgdn.util.GameLogger.getLogger(TechniqueDAO.class)
                    .error("增加功法熟练度失败: " + e.getMessage(), e);
        }
    }

    public void addProficiency(Connection conn, long playerId, long techniqueId, int amount) throws SQLException {
        String sql = "UPDATE players_techniques SET proficiency = proficiency + ? WHERE player_id = ? AND technique_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.setLong(3, techniqueId);
            ps.executeUpdate();
        }
    }

    // ==================== ResultSet → 实体 ====================

    private Technique mapTechnique(ResultSet rs) throws SQLException {
        Technique t = new Technique();
        t.setId(rs.getLong("id"));
        t.setName(rs.getString("name"));
        t.setDescription(rs.getString("description"));
        t.setRequiredRealm(rs.getInt("required_realm"));
        t.setLearnCostGold(rs.getLong("learn_cost_gold"));
        t.setLearnCostSpiritStones(rs.getLong("learn_cost_spirit_stones"));
        t.setUpgradeBaseCostGold(rs.getInt("upgrade_base_cost_gold"));
        t.setUpgradeBaseCostSpiritStones(rs.getInt("upgrade_base_cost_spirit_stones"));
        t.setType(Technique.Type.valueOf(rs.getString("type")));
        t.setMaxLevel(rs.getInt("max_level"));
        t.setHpBonus(rs.getInt("hp_bonus"));
        t.setMpBonus(rs.getInt("mp_bonus"));
        t.setAttackBonus(rs.getInt("attack_bonus"));
        t.setDefenseBonus(rs.getInt("defense_bonus"));
        t.setSpeedBonus(rs.getInt("speed_bonus"));
        t.setSpiritBonus(rs.getInt("spirit_bonus"));
        t.setCultivationSpeedBonus(rs.getDouble("cultivation_speed_bonus"));
        t.setExpBonus(rs.getDouble("exp_bonus"));
        t.setCombatDamageBonus(rs.getDouble("combat_damage_bonus"));
        t.setDamageReduction(rs.getDouble("damage_reduction"));
        return t;
    }

    private Technique mapTechniqueFull(ResultSet rs) throws SQLException {
        Technique t = mapTechnique(rs);
        t.setLevel(rs.getInt("level"));
        t.setProficiency(rs.getInt("proficiency"));
        t.setEquipped(rs.getBoolean("is_equipped"));
        return t;
    }
}
