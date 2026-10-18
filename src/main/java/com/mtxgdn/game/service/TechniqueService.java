package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Technique;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class TechniqueService {

    private static final GameLogger LOG = GameLogger.getLogger(TechniqueService.class);
    private static final int MAX_EQUIPPED = 3;
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    public Technique getTechniqueById(long techniqueId) {
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

    public List<Technique> getAllTechniques() {
        String sql = "SELECT * FROM techniques ORDER BY required_realm, id";
        List<Technique> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(mapTechnique(rs));
        } catch (SQLException e) { throw new RuntimeException("查询功法列表失败", e); }
        return result;
    }

    public List<Technique> getPlayerTechniques(long playerId) {
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

    public List<Technique> getEquippedTechniques(long playerId) {
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

    /** 已装备功法的修炼速度加成之和（供修炼结算使用）。 */
    public double getEquippedCultivationSpeedBonus(long playerId) {
        double total = 0;
        List<Technique> equipped = getEquippedTechniques(playerId);
        for (Technique t : equipped) {
            total += t.getScaledCultivationSpeedBonus();
        }
        return total;
    }

    /** 已装备功法的经验加成之和（exp_bonus，供 addExperience 全链路结算使用）。 */
    public double getEquippedExpBonus(long playerId) {
        double total = 0;
        List<Technique> equipped = getEquippedTechniques(playerId);
        for (Technique t : equipped) {
            total += t.getScaledExpBonus();
        }
        return total;
    }

    /** 已装备功法的战斗伤害加成之和（combat_damage_bonus，供 PVE/PVP/团队 Boss 结算使用）。 */
    public double getEquippedCombatDamageBonus(long playerId) {
        double total = 0;
        List<Technique> equipped = getEquippedTechniques(playerId);
        for (Technique t : equipped) {
            total += t.getScaledCombatDamageBonus();
        }
        return total;
    }

    /** 已装备功法的减伤之和（damage_reduction，供 PVE/PVP/团队 Boss 结算使用）。 */
    public double getEquippedDamageReduction(long playerId) {
        double total = 0;
        List<Technique> equipped = getEquippedTechniques(playerId);
        for (Technique t : equipped) {
            total += t.getScaledDamageReduction();
        }
        return total;
    }

    public Map<String, Object> learnTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Technique technique = getTechniqueById(techniqueId);
        if (technique == null) {
            result.put("success", false); result.put("message", I18n.t("technique.ha59e2658")); return result;
        }
        if (hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", I18n.t("technique.h013e2d0b", technique.getName())); return result;
        }
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", I18n.t("technique.h59a76b2c")); return result;
        }
        if (player.getRealm() < technique.getRequiredRealm()) {
            result.put("success", false); result.put("message", I18n.t("technique.h3215dc94", technique.getName())); return result;
        }
        if (player.getGold() < technique.getLearnCostGold()) {
            result.put("success", false); result.put("message", I18n.t("technique.ha28ab727", technique.getLearnCostGold())); return result;
        }
        long ssCount = itemService.getSpiritStoneCount(playerId);
        if (ssCount < technique.getLearnCostSpiritStones()) {
            result.put("success", false); result.put("message", I18n.t("technique.h0aad1a38", technique.getLearnCostSpiritStones())); return result;
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                playerService.addGold(conn, playerId, -technique.getLearnCostGold());
                if (!itemService.removeSpiritStones(conn, playerId, technique.getLearnCostSpiritStones())) {
                    throw new SQLException("灵石扣除失败");
                }

                String sql = "INSERT INTO players_techniques (player_id, technique_id, level, proficiency, is_equipped) VALUES (?, ?, 1, 0, 0)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, techniqueId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("technique.h67eca6dc", e.getMessage()));
            return result;
        }

        // 学习不直接加属性；属性只在「装备」时生效（避免重复叠加）

        result.put("success", true);
        result.put("message", I18n.t("technique.h021d2cec", technique.getName()));
        result.put("technique", technique);
        return result;
    }

    public Map<String, Object> equipTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", I18n.t("technique.hec1e8dc4")); return result;
        }
        Technique tech = getPlayerTechniqueDetail(playerId, techniqueId);
        if (tech.isEquipped()) {
            result.put("success", false); result.put("message", I18n.t("technique.hb769477b", tech.getName())); return result;
        }
        int equipped = countEquipped(playerId);
        if (equipped >= MAX_EQUIPPED) {
            result.put("success", false); result.put("message", I18n.t("technique.hc6a9b2dc", MAX_EQUIPPED)); return result;
        }
        String sql = "UPDATE players_techniques SET is_equipped = 1 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("装备功法失败", e); }

        Player player = playerService.getPlayerById(playerId);
        applyTechniqueBonuses(player, tech);
        playerService.updatePlayer(playerId, player);

        result.put("success", true);
        result.put("message", I18n.t("technique.h6dd6706f", tech.getName()));
        return result;
    }

    public Map<String, Object> unequipTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", I18n.t("technique.hec1e8dc4")); return result;
        }
        Technique tech = getPlayerTechniqueDetail(playerId, techniqueId);
        if (!tech.isEquipped()) {
            result.put("success", false); result.put("message", I18n.t("technique.h98bb7ad4", tech.getName())); return result;
        }
        String sql = "UPDATE players_techniques SET is_equipped = 0 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("卸下功法失败", e); }

        // 卸下时回退属性加成，防止反复装卸无限叠加
        Player player = playerService.getPlayerById(playerId);
        removeTechniqueBonuses(player, tech);
        playerService.updatePlayer(playerId, player);

        result.put("success", true);
        result.put("message", I18n.t("technique.h16bd4a40", tech.getName()));
        return result;
    }

    public Map<String, Object> upgradeTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", I18n.t("technique.hec1e8dc4")); return result;
        }
        Technique tech = getPlayerTechniqueDetail(playerId, techniqueId);
        if (tech.getLevel() >= tech.getMaxLevel()) {
            result.put("success", false); result.put("message", I18n.t("technique.hdf5811f9", tech.getName())); return result;
        }

        int costGold = tech.getUpgradeBaseCostGold() * tech.getLevel();
        int costSS = tech.getUpgradeBaseCostSpiritStones() * tech.getLevel();

        Player player = playerService.getPlayerById(playerId);
        if (player.getGold() < costGold) {
            result.put("success", false); result.put("message", I18n.t("technique.hbafa0757", costGold)); return result;
        }
        long ssCount = itemService.getSpiritStoneCount(playerId);
        if (ssCount < costSS) {
            result.put("success", false); result.put("message", I18n.t("technique.h9e908e81", costSS)); return result;
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                playerService.addGold(conn, playerId, -costGold);
                if (!itemService.removeSpiritStones(conn, playerId, costSS)) {
                    throw new SQLException("灵石扣除失败");
                }

                String sql = "UPDATE players_techniques SET level = level + 1, proficiency = 0 WHERE player_id = ? AND technique_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, techniqueId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("technique.h771cdb74", e.getMessage()));
            return result;
        }

        // 已装备的功法升级后，按新旧等级补差属性（先回退旧加成，再施加新加成）
        if (tech.isEquipped()) {
            removeTechniqueBonuses(player, tech);
            Technique upgraded = getPlayerTechniqueDetail(playerId, techniqueId);
            applyTechniqueBonuses(player, upgraded);
            playerService.updatePlayer(playerId, player);
        }

        result.put("success", true);
        result.put("message", "功法【" + tech.getName() + "】升至 Lv." + (tech.getLevel() + 1));
        return result;
    }

    public void addProficiency(long playerId, long techniqueId, int amount) {
        String sql = "UPDATE players_techniques SET proficiency = proficiency + ? WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setLong(2, playerId);
            ps.setLong(3, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { LOG.error("增加功法熟练度失败: " + e.getMessage(), e); }
    }

    public void insertDefaultTechniques() {
        String sql = """
            INSERT INTO techniques (name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones,
                upgrade_base_cost_gold, upgrade_base_cost_spirit_stones, type, max_level,
                hp_bonus, mp_bonus, attack_bonus, defense_bonus, speed_bonus, spirit_bonus,
                cultivation_speed_bonus, exp_bonus, combat_damage_bonus, damage_reduction)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        Object[][] defaults = {
            {"吐纳术", "最基础的修炼法门，引天地灵气入体，缓慢提升修为", 0, 0L, 0L, 200, 50, "CULTIVATION", 10, 0, 0, 0, 0, 0, 0, 0.05, 0.0, 0.0, 0.0},
            {"铁骨功", "以灵气淬炼筋骨，增强体魄和防御力", 1, 500L, 100L, 300, 80, "DEFENSE", 10, 30, 0, 0, 10, 0, 0, 0.0, 0.0, 0.0, 0.03},
            {"锐金诀", "金系入门功法，锋锐之气贯穿经脉，增强攻击", 1, 500L, 100L, 300, 80, "ATTACK", 10, 0, 0, 8, 0, 0, 0, 0.0, 0.0, 0.03, 0.0},
            {"长春功", "木属性养生功法，生生不息，延年益寿", 2, 1500L, 300L, 500, 150, "CULTIVATION", 10, 50, 20, 0, 0, 0, 0, 0.08, 0.05, 0.0, 0.0},
            {"烈焰心经", "火系攻伐功法，焚尽八荒，威势滔天", 2, 2000L, 400L, 600, 200, "ATTACK", 10, 0, 0, 15, 0, 0, 5, 0.0, 0.0, 0.05, 0.0},
            {"玄水真解", "水系功法，上善若水，法力如海", 3, 3000L, 600L, 800, 300, "DEFENSE", 10, 0, 40, 0, 5, 0, 0, 0.03, 0.0, 0.0, 0.03},
            {"太虚经", "上古丹道功法，提升炼丹效率和灵石获取", 3, 4000L, 800L, 1000, 400, "UTILITY", 8, 0, 0, 0, 0, 0, 10, 0.02, 0.02, 0.0, 0.0},
            {"紫霄天诀", "天阶功法，引九霄紫气淬体，全面提升", 4, 8000L, 1500L, 1500, 600, "CULTIVATION", 10, 30, 30, 5, 5, 5, 5, 0.10, 0.05, 0.02, 0.02},
            {"大衍剑诀", "剑修至高功法，人剑合一，无坚不摧", 4, 10000L, 2000L, 2000, 800, "ATTACK", 10, 0, 0, 25, 0, 0, 8, 0.0, 0.0, 0.08, 0.0},
            {"金刚不坏功", "佛门护体神功，金刚怒目，万法不侵", 5, 15000L, 3000L, 3000, 1200, "DEFENSE", 10, 80, 0, 0, 20, 0, 0, 0.0, 0.0, 0.0, 0.06},
            {"混沌天经", "传说中的远古功法，混沌之力，无所不包", 5, 20000L, 5000L, 5000, 2000, "CULTIVATION", 10, 50, 50, 10, 10, 10, 10, 0, 0.15, 0.10, 0.05, 0.05},
            // ===== 高境界功法（补全合体~渡劫的内容天花板） =====
            {"混元一气诀", "混元之力生生不息，修为突飞猛进", 6, 40000L, 10000L, 8000, 3000, "CULTIVATION", 10, 100, 100, 15, 15, 15, 15, 0.12, 0.08, 0.03, 0.03},
            {"无极剑典", "无极生太极，一剑化万剑", 6, 50000L, 12000L, 8000, 3200, "ATTACK", 10, 0, 0, 50, 0, 0, 20, 0.0, 0.0, 0.12, 0.0},
            {"九转玄功", "九转成圣，肉身不朽", 7, 100000L, 30000L, 12000, 5000, "DEFENSE", 10, 200, 0, 0, 40, 0, 0, 0.0, 0.0, 0.0, 0.10},
            {"鸿蒙紫气经", "鸿蒙初辟，紫气东来，大道之基", 8, 250000L, 80000L, 20000, 8000, "CULTIVATION", 10, 150, 150, 25, 25, 25, 25, 0.15, 0.12, 0.06, 0.06},
        };

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : defaults) {
                // 按名称幂等：已存在的功法跳过（支持旧库增量补齐新功法）
                String name = (String) row[0];
                String checkSql = "SELECT COUNT(*) FROM techniques WHERE name = ?";
                try (PreparedStatement cps = conn.prepareStatement(checkSql)) {
                    cps.setString(1, name);
                    try (ResultSet rs = cps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            continue;
                        }
                    }
                }
                ps.setString(1, name);
                ps.setString(2, (String) row[1]);
                ps.setInt(3, ((Number) row[2]).intValue());
                ps.setLong(4, ((Number) row[3]).longValue());
                ps.setLong(5, ((Number) row[4]).longValue());
                ps.setInt(6, ((Number) row[5]).intValue());
                ps.setInt(7, ((Number) row[6]).intValue());
                ps.setString(8, (String) row[7]);
                ps.setInt(9, ((Number) row[8]).intValue());
                for (int i = 0; i < 6; i++) ps.setInt(10 + i, ((Number) row[9 + i]).intValue());
                ps.setDouble(16, ((Number) row[15]).doubleValue());
                ps.setDouble(17, ((Number) row[16]).doubleValue());
                ps.setDouble(18, ((Number) row[17]).doubleValue());
                ps.setDouble(19, ((Number) row[18]).doubleValue());
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException("初始化功法失败", e); }
        LOG.info("已初始化默认功法数据");
    }

    private int countEquipped(long playerId) {
        String sql = "SELECT COUNT(*) FROM players_techniques WHERE player_id = ? AND is_equipped = 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException e) { throw new RuntimeException("查询装备功法数量失败", e); }
        return 0;
    }

    private Technique getPlayerTechniqueDetail(long playerId, long techniqueId) {
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

    private void applyTechniqueBonuses(Player player, Technique technique) {
        if (technique == null) return;
        player.setMaxHp(player.getMaxHp() + technique.getScaledHpBonus());
        player.setHp(Math.min(player.getHp() + technique.getScaledHpBonus(), player.getMaxHp()));
        player.setMaxMp(player.getMaxMp() + technique.getScaledMpBonus());
        player.setMp(Math.min(player.getMp() + technique.getScaledMpBonus(), player.getMaxMp()));
        player.setAttack(player.getAttack() + technique.getScaledAttackBonus());
        player.setDefense(player.getDefense() + technique.getScaledDefenseBonus());
        player.setSpeed(player.getSpeed() + technique.getScaledSpeedBonus());
        player.setSpirit(player.getSpirit() + technique.getScaledSpiritBonus());
    }

    /** 卸下功法时回退对应属性加成（与 applyTechniqueBonuses 严格互逆）。 */
    private void removeTechniqueBonuses(Player player, Technique technique) {
        if (technique == null) return;
        player.setMaxHp(Math.max(0, player.getMaxHp() - technique.getScaledHpBonus()));
        player.setMaxMp(Math.max(0, player.getMaxMp() - technique.getScaledMpBonus()));
        player.setAttack(Math.max(0, player.getAttack() - technique.getScaledAttackBonus()));
        player.setDefense(Math.max(0, player.getDefense() - technique.getScaledDefenseBonus()));
        player.setSpeed(Math.max(0, player.getSpeed() - technique.getScaledSpeedBonus()));
        player.setSpirit(Math.max(0, player.getSpirit() - technique.getScaledSpiritBonus()));
    }

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