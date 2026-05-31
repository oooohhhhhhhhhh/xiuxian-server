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

public class TechniqueService {

    private static final GameLogger LOG = GameLogger.getLogger(TechniqueService.class);
    private static final int MAX_EQUIPPED = 3;
    private final PlayerService playerService = new PlayerService();

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

    public Map<String, Object> learnTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Technique technique = getTechniqueById(techniqueId);
        if (technique == null) {
            result.put("success", false); result.put("message", "功法不存在"); return result;
        }
        if (hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", "你已经学会了【" + technique.getName() + "】"); return result;
        }
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false); result.put("message", "角色不存在"); return result;
        }
        if (player.getRealm() < technique.getRequiredRealm()) {
            result.put("success", false); result.put("message", "境界不足，学习【" + technique.getName() + "】需更高境界"); return result;
        }
        if (player.getGold() < technique.getLearnCostGold()) {
            result.put("success", false); result.put("message", "金币不足，需要 " + technique.getLearnCostGold() + " 金币"); return result;
        }
        long ssCount = new ItemService().getSpiritStoneCount(playerId);
        if (ssCount < technique.getLearnCostSpiritStones()) {
            result.put("success", false); result.put("message", "灵石不足，需要 " + technique.getLearnCostSpiritStones() + " 灵石"); return result;
        }

        playerService.addGold(playerId, -technique.getLearnCostGold());
        new ItemService().addSpiritStones(playerId, -technique.getLearnCostSpiritStones());

        String sql = "INSERT INTO players_techniques (player_id, technique_id, level, proficiency, is_equipped) VALUES (?, ?, 1, 0, 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("学习功法失败", e); }

        applyTechniqueBonuses(player, technique);
        playerService.updatePlayer(playerId, player);

        result.put("success", true);
        result.put("message", "成功学会功法【" + technique.getName() + "】！");
        result.put("technique", technique);
        return result;
    }

    public Map<String, Object> equipTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", "你尚未学会此功法"); return result;
        }
        int equipped = countEquipped(playerId);
        if (equipped >= MAX_EQUIPPED) {
            result.put("success", false); result.put("message", "最多只能同时运转 " + MAX_EQUIPPED + " 门功法，请先卸下一门"); return result;
        }
        String sql = "UPDATE players_techniques SET is_equipped = 1 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("装备功法失败", e); }

        Player player = playerService.getPlayerById(playerId);
        Technique tech = getTechniqueById(techniqueId);
        tech = getPlayerTechniqueDetail(playerId, techniqueId);
        applyTechniqueBonuses(player, tech);
        playerService.updatePlayer(playerId, player);

        result.put("success", true);
        result.put("message", "开始运转功法【" + tech.getName() + "】");
        return result;
    }

    public Map<String, Object> unequipTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", "你尚未学会此功法"); return result;
        }
        String sql = "UPDATE players_techniques SET is_equipped = 0 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("卸下功法失败", e); }

        Technique tech = getTechniqueById(techniqueId);
        result.put("success", true);
        result.put("message", "停止运转功法【" + tech.getName() + "】");
        return result;
    }

    public Map<String, Object> upgradeTechnique(long playerId, long techniqueId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!hasTechnique(playerId, techniqueId)) {
            result.put("success", false); result.put("message", "你尚未学会此功法"); return result;
        }
        Technique tech = getPlayerTechniqueDetail(playerId, techniqueId);
        if (tech.getLevel() >= tech.getMaxLevel()) {
            result.put("success", false); result.put("message", "【" + tech.getName() + "】已达最高等级"); return result;
        }

        int costGold = tech.getUpgradeBaseCostGold() * tech.getLevel();
        int costSS = tech.getUpgradeBaseCostSpiritStones() * tech.getLevel();

        Player player = playerService.getPlayerById(playerId);
        if (player.getGold() < costGold) {
            result.put("success", false); result.put("message", "金币不足，升级需要 " + costGold + " 金币"); return result;
        }
        long ssCount = new ItemService().getSpiritStoneCount(playerId);
        if (ssCount < costSS) {
            result.put("success", false); result.put("message", "灵石不足，升级需要 " + costSS + " 灵石"); return result;
        }

        playerService.addGold(playerId, -costGold);
        new ItemService().addSpiritStones(playerId, -costSS);

        String sql = "UPDATE players_techniques SET level = level + 1, proficiency = 0 WHERE player_id = ? AND technique_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, techniqueId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException("升级功法失败", e); }

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
        if (getAllTechniques().size() > 0) return;

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
            {"混沌天经", "传说中的远古功法，混沌之力，无所不包", 5, 20000L, 5000L, 5000, 2000, "CULTIVATION", 10, 50, 50, 10, 10, 10, 10, 0.15, 0.10, 0.05, 0.05},
        };

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Object[] row : defaults) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setInt(3, (int) row[2]);
                ps.setLong(4, (long) row[3]);
                ps.setLong(5, (long) row[4]);
                ps.setInt(6, (int) row[5]);
                ps.setInt(7, (int) row[6]);
                ps.setString(8, (String) row[7]);
                ps.setInt(9, (int) row[8]);
                for (int i = 9; i < 17; i++) ps.setInt(i + 1, (int) row[i]);
                ps.setDouble(18, (double) row[17]);
                ps.setDouble(19, (double) row[18]);
                ps.setDouble(20, (double) row[19]);
                ps.setDouble(21, (double) row[20]);
                ps.executeUpdate();
            }
        } catch (SQLException e) { throw new RuntimeException("初始化功法失败", e); }
        LOG.info("已初始化 " + defaults.length + " 种默认功法");
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
