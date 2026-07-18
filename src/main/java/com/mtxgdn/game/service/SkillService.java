package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SkillService {

    private static final GameLogger LOG = GameLogger.getLogger(SkillService.class);
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();

    public Skill getSkillById(long skillId) {
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

    public Skill findSkillByName(String name) {
        for (Skill skill : getAllSkills()) {
            if (skill.getName().equals(name) || skill.getName().contains(name)) {
                return skill;
            }
        }
        return null;
    }

    public List<Skill> getAllSkills() {
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

    public List<Skill> getPlayerSkills(long playerId) {
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

    public Skill getPlayerSkill(long playerId, long skillId) {
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

    public Map<String, Object> learnSkill(long playerId, long skillId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Skill skill = getSkillById(skillId);
        if (skill == null) {
            result.put("success", false);
            result.put("message", "技能不存在");
            result.put("code", 6101);
            return result;
        }

        if (hasSkill(playerId, skillId)) {
            result.put("success", false);
            result.put("message", "你已经学会了【" + skill.getName() + "】");
            result.put("code", 6102);
            return result;
        }

        Player player = playerService.getPlayerRaw(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            result.put("code", 3001);
            return result;
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            result.put("success", false);
            result.put("message", "境界不足，学习【" + skill.getName() + "】需要达到更高境界");
            result.put("code", 6103);
            return result;
        }

        if (player.getGold() < skill.getLearnCostGold()) {
            result.put("success", false);
            result.put("message", "金币不足，学习【" + skill.getName() + "】需要 " + skill.getLearnCostGold() + " 金币");
            result.put("code", 6104);
            return result;
        }

        if (skill.getLearnCostSpiritStones() > 0) {
            long spiritStoneCount = itemService.getSpiritStoneCount(playerId);
            if (spiritStoneCount < skill.getLearnCostSpiritStones()) {
                result.put("success", false);
                result.put("message", "灵石不足，学习【" + skill.getName() + "】需要 " + skill.getLearnCostSpiritStones() + " 灵石");
                result.put("code", 6104);
                return result;
            }
        }

        try {
            DatabaseManager.runTransaction(conn -> {
                // 先扣除成本
                if (skill.getLearnCostGold() > 0) {
                    playerService.addGold(conn, playerId, -skill.getLearnCostGold());
                }
                if (skill.getLearnCostSpiritStones() > 0) {
                    if (!itemService.removeItem(conn, playerId, com.mtxgdn.game.item.CurrencyEffect.SPIRIT_STONE_KEY, skill.getLearnCostSpiritStones())) {
                        throw new SQLException("灵石扣除失败");
                    }
                }
                // 再插入技能记录
                String insertSql = "INSERT INTO players_skills (player_id, skill_id, level, proficiency) VALUES (?, ?, 1, 0)";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setLong(1, playerId);
                    ps.setLong(2, skillId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "学习技能失败: " + e.getMessage());
            result.put("code", 6106);
            return result;
        }

        result.put("success", true);
        result.put("message", "成功学会了【" + skill.getName() + "】！");
        result.put("skill", skill);
        new DailyService().onSkillLearn(playerId);
        return result;
    }

    public Map<String, Object> learnSkillFromBook(long playerId, long skillId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Skill skill = getSkillById(skillId);
        if (skill == null) {
            result.put("success", false);
            result.put("message", "秘籍记载的技能已经失传了...");
            return result;
        }

        Player player = playerService.getPlayerRaw(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            result.put("success", false);
            result.put("message", "境界不足，无法参悟【" + skill.getName() + "】，需要达到更高境界");
            return result;
        }

        Skill existingSkill = getPlayerSkill(playerId, skillId);
        if (existingSkill != null) {
            int currentLevel = existingSkill.getLevel();
            int maxLevel = skill.getMaxLevel();
            if (currentLevel >= maxLevel) {
                result.put("success", false);
                result.put("message", "【" + skill.getName() + "】已达满级 " + maxLevel + " 级，这本秘籍对你已无用处");
                return result;
            }
            int newLevel = currentLevel + 1;
            String updSql = "UPDATE players_skills SET level = ? WHERE player_id = ? AND skill_id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updSql)) {
                ps.setInt(1, newLevel);
                ps.setLong(2, playerId);
                ps.setLong(3, skillId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("升级技能失败", e);
            }
            result.put("success", true);
            result.put("message", "参悟成功！【" + skill.getName() + "】升至 " + newLevel + " 级！");
            result.put("skill", skill);
            result.put("newLevel", newLevel);
            return result;
        }

        String insertSql = "INSERT INTO players_skills (player_id, skill_id, level, proficiency) VALUES (?, ?, 1, 0)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, playerId);
            ps.setLong(2, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("学习技能失败", e);
        }

        result.put("success", true);
        result.put("message", "参悟成功！学会了【" + skill.getName() + "】！");
        result.put("skill", skill);
        return result;
    }

    public int getProficiencyForNextLevel(int currentLevel) {
        return currentLevel * 100;
    }

    public Map<String, Object> addProficiency(long playerId, long skillId, int amount) {
        Map<String, Object> result = new LinkedHashMap<>();
        Skill skill = getPlayerSkill(playerId, skillId);
        if (skill == null) {
            result.put("success", false);
            result.put("leveledUp", false);
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player != null && player.getSpiritualRoot() != null
                && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.PROFICIENCY_BOOST)) {
            amount = (int)(amount * (1 + player.getSpiritualRoot().getEffectValue()));
        }

        int currentLevel = skill.getLevel();
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) {
            result.put("success", true);
            result.put("leveledUp", false);
            result.put("level", currentLevel);
            return result;
        }

        int newProficiency = skill.getProficiency() + amount;
        boolean leveledUp = false;

        while (currentLevel < maxLevel) {
            int needed = getProficiencyForNextLevel(currentLevel);
            if (newProficiency >= needed) {
                newProficiency -= needed;
                currentLevel++;
                leveledUp = true;
            } else {
                break;
            }
        }

        if (currentLevel > maxLevel) {
            currentLevel = maxLevel;
            newProficiency = 0;
        }

        String updSql = "UPDATE players_skills SET level = ?, proficiency = ? WHERE player_id = ? AND skill_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(updSql)) {
            ps.setInt(1, currentLevel);
            ps.setInt(2, newProficiency);
            ps.setLong(3, playerId);
            ps.setLong(4, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("增加熟练度失败", e);
        }

        result.put("success", true);
        result.put("leveledUp", leveledUp);
        result.put("level", currentLevel);
        result.put("proficiency", newProficiency);
        result.put("proficiencyForNext", getProficiencyForNextLevel(currentLevel));
        return result;
    }

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

    public void insertDefaultSkills() {
        if (getAllSkills().isEmpty()) {
            String[] skills = {
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (1, '烈火掌', '以灵力催动烈焰，掌风如火', 1, 100, 50, 30, 10, 3, 0, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (2, '玄冰刺', '凝聚寒气成冰刺，锐不可当', 2, 500, 200, 60, 20, 5, 0, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (3, '天雷咒', '引九天神雷，威势惊人', 3, 2000, 500, 100, 35, 8, 0, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (4, '万剑诀', '剑气万千，遮天蔽日', 4, 5000, 1000, 180, 50, 10, 0, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (5, '回春术', '以灵力治愈自身伤势', 0, 200, 100, 0, 15, 10, 1, 60, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (6, '甘霖普降', '天降甘霖，大量恢复生命', 2, 800, 300, 0, 30, 15, 1, 150, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (7, '风驰电掣', '身法如电，闪避致命一击', 1, 300, 150, 0, 10, 20, 2, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (8, '金刚护体', '灵气化盾，坚如金刚', 2, 600, 250, 0, 20, 30, 2, 0, 10)",
                "INSERT INTO skills (id, name, description, required_realm, learn_cost_gold, learn_cost_spirit_stones, damage, mp_cost, cooldown_seconds, skill_type, heal_amount, max_level) VALUES (9, '基础剑诀', '记载了基础剑法的口诀，适合初学者练习', 0, 50, 20, 20, 8, 2, 0, 0, 10)",
            };
            try (Connection conn = DatabaseManager.getConnection();
                 var stmt = conn.createStatement()) {
                for (String skillSql : skills) {
                    stmt.executeUpdate(skillSql);
                }
                LOG.info("已插入默认技能数据");
            } catch (SQLException e) {
                throw new RuntimeException("插入默认技能失败", e);
            }
        }
    }
}
