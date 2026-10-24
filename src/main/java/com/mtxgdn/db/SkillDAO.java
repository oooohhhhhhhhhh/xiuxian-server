package com.mtxgdn.db;

import com.mtxgdn.game.entity.Skill;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能（{@code skills} / {@code players_skills}）的读写入口。
 *
 * <p>从 {@code SkillService} 抽出来的第 6 个 DAO。每个方法一对重载：</p>
 *
 * <ul>
 *   <li>{@code xxx(...)}：自己开一条短连接，失败时把 {@link SQLException}
 *       包成 {@link RuntimeException}（文案沿用原实现的"查询技能失败"等）；</li>
 *   <li>{@code xxx(Connection conn, ...)}：只用调用方传进来的连接，
 *       直接抛 {@link SQLException}，<b>绝不自己开连接、绝不碰事务</b>。
 *       事务里（{@code runTransaction} 的 lambda 内）必须用这一版，
 *       否则 SQLite 下会撞 {@code SQLITE_BUSY_SNAPSHOT}，
 *       连接池也会被借空导致 10 秒超时。</li>
 * </ul>
 *
 * <p>SQL 逐字搬自原 {@code SkillService}，包括 {@code INNER JOIN} 的写法、
 * {@code ORDER BY} 的顺序，以及 {@code skills} 表本来没有
 * {@code level}/{@code proficiency} 列、要靠 {@code getInt} 抛异常再退回默认值
 * 这个写法 —— 一律没有"顺手优化"。</p>
 */
public class SkillDAO {

    // ==================== 技能定义 ====================

    /** 按 id 查技能定义。 */
    public Skill findById(long skillId) {
        String sql = "SELECT * FROM skills WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkill(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询技能失败", e);
        }
        return null;
    }

    public Skill findById(Connection conn, long skillId) throws SQLException {
        String sql = "SELECT * FROM skills WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkill(rs);
                }
            }
        }
        return null;
    }

    /** 全部技能定义，按 required_realm, id 排序。 */
    public List<Skill> findAll() {
        String sql = "SELECT * FROM skills ORDER BY required_realm, id";
        List<Skill> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapSkill(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询技能列表失败", e);
        }
        return result;
    }

    public List<Skill> findAll(Connection conn) throws SQLException {
        String sql = "SELECT * FROM skills ORDER BY required_realm, id";
        List<Skill> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapSkill(rs));
            }
        }
        return result;
    }

    // ==================== 玩家的技能 ====================

    /** 玩家已学的全部技能（含 level/proficiency）。 */
    public List<Skill> findPlayerSkills(long playerId) {
        String sql = """
            SELECT s.*, ps.level, ps.proficiency FROM skills s
            INNER JOIN players_skills ps ON s.id = ps.skill_id
            WHERE ps.player_id = ?
            ORDER BY s.id
            """;
        List<Skill> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapSkill(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询玩家技能失败", e);
        }
        return result;
    }

    public List<Skill> findPlayerSkills(Connection conn, long playerId) throws SQLException {
        String sql = """
            SELECT s.*, ps.level, ps.proficiency FROM skills s
            INNER JOIN players_skills ps ON s.id = ps.skill_id
            WHERE ps.player_id = ?
            ORDER BY s.id
            """;
        List<Skill> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapSkill(rs));
                }
            }
        }
        return result;
    }

    /** 单个玩家技能的详情（含等级、熟练度）。 */
    public Skill findPlayerSkill(long playerId, long skillId) {
        String sql = """
            SELECT s.*, ps.level, ps.proficiency FROM skills s
            INNER JOIN players_skills ps ON s.id = ps.skill_id
            WHERE ps.player_id = ? AND ps.skill_id = ?
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkill(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询技能详情失败", e);
        }
        return null;
    }

    public Skill findPlayerSkill(Connection conn, long playerId, long skillId) throws SQLException {
        String sql = """
            SELECT s.*, ps.level, ps.proficiency FROM skills s
            INNER JOIN players_skills ps ON s.id = ps.skill_id
            WHERE ps.player_id = ? AND ps.skill_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkill(rs);
                }
            }
        }
        return null;
    }

    /** 玩家是否已学这门技能。 */
    public boolean hasSkill(long playerId, long skillId) {
        String sql = "SELECT COUNT(*) FROM players_skills WHERE player_id = ? AND skill_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询技能状态失败", e);
        }
        return false;
    }

    public boolean hasSkill(Connection conn, long playerId, long skillId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM players_skills WHERE player_id = ? AND skill_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    // ==================== 写操作（事务内一律用 Connection 重载） ====================

    /** 学会一门技能（初始 level 1 / proficiency 0）。 */
    public void insertPlayerSkill(long playerId, long skillId) {
        String sql = "INSERT INTO players_skills (player_id, skill_id, level, proficiency) VALUES (?, ?, 1, 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("学习技能失败", e);
        }
    }

    public void insertPlayerSkill(Connection conn, long playerId, long skillId) throws SQLException {
        String sql = "INSERT INTO players_skills (player_id, skill_id, level, proficiency) VALUES (?, ?, 1, 0)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            ps.executeUpdate();
        }
    }

    /** 只改等级（技能书升阶走这条）。 */
    public void updateLevel(long playerId, long skillId, int level) {
        String sql = "UPDATE players_skills SET level = ? WHERE player_id = ? AND skill_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setLong(2, playerId);
            ps.setLong(3, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("升级技能失败", e);
        }
    }

    public void updateLevel(Connection conn, long playerId, long skillId, int level) throws SQLException {
        String sql = "UPDATE players_skills SET level = ? WHERE player_id = ? AND skill_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setLong(2, playerId);
            ps.setLong(3, skillId);
            ps.executeUpdate();
        }
    }

    /** 等级与熟练度一起改（熟练度结算走这条）。 */
    public void updateLevelAndProficiency(long playerId, long skillId, int level, int proficiency) {
        String sql = "UPDATE players_skills SET level = ?, proficiency = ? WHERE player_id = ? AND skill_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setInt(2, proficiency);
            ps.setLong(3, playerId);
            ps.setLong(4, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加熟练度失败", e);
        }
    }

    public void updateLevelAndProficiency(Connection conn, long playerId, long skillId,
                                          int level, int proficiency) throws SQLException {
        String sql = "UPDATE players_skills SET level = ?, proficiency = ? WHERE player_id = ? AND skill_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setInt(2, proficiency);
            ps.setLong(3, playerId);
            ps.setLong(4, skillId);
            ps.executeUpdate();
        }
    }

    /**
     * 批量执行出厂技能数据。
     *
     * <p>原实现用 {@link Statement#executeUpdate(String)} 逐条灌整段 SQL 字面量，
     * 而且 <b>逐条 catch 住异常继续</b> —— 因为重复初始化时 id 主键冲突是常态。
     * 这里照搬这个语义：一条失败不影响其余，也不往上抛。</p>
     *
     * @return 成功插入的条数（供日志/测试用）
     */
    public int insertDefaults(List<String> insertStatements) {
        int inserted = 0;
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String skillSql : insertStatements) {
                try {
                    stmt.executeUpdate(skillSql);
                    inserted++;
                } catch (SQLException e) {
                    // 重复初始化时主键冲突，跳过
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("初始化默认技能失败", e);
        }
        return inserted;
    }

    public int insertDefaults(Connection conn, List<String> insertStatements) throws SQLException {
        int inserted = 0;
        try (Statement stmt = conn.createStatement()) {
            for (String skillSql : insertStatements) {
                try {
                    stmt.executeUpdate(skillSql);
                    inserted++;
                } catch (SQLException e) {
                    // 重复初始化时主键冲突，跳过
                }
            }
        }
        return inserted;
    }

    // ==================== ResultSet → 实体 ====================

    /**
     * {@code skills} 表没有 {@code level}/{@code proficiency} 列，
     * 查定义时 {@code getInt} 会抛 {@link SQLException}，
     * 这时退回"1 级 / 0 熟练度"的默认值 —— 原实现就是这个行为。
     */
    private Skill mapSkill(ResultSet rs) throws SQLException {
        Skill skill = new Skill();
        skill.setId(rs.getLong("id"));
        skill.setName(rs.getString("name"));
        skill.setDescription(rs.getString("description"));
        skill.setRequiredRealm(rs.getInt("required_realm"));
        skill.setLearnCostGold(rs.getLong("learn_cost_gold"));
        skill.setLearnCostSpiritStones(rs.getLong("learn_cost_spirit_stones"));
        skill.setDamage(rs.getInt("damage"));
        skill.setMpCost(rs.getInt("mp_cost"));
        skill.setCooldownSeconds(rs.getInt("cooldown_seconds"));
        skill.setSkillType(rs.getInt("skill_type"));
        skill.setHealAmount(rs.getInt("heal_amount"));
        skill.setMaxLevel(rs.getInt("max_level"));
        try {
            skill.setLevel(rs.getInt("level"));
            skill.setProficiency(rs.getInt("proficiency"));
        } catch (SQLException ignored) {
            skill.setLevel(1);
            skill.setProficiency(0);
        }
        return skill;
    }
}
