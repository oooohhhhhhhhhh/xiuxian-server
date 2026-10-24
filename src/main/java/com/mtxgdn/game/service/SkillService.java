package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SkillDAO;
import com.mtxgdn.game.config.SkillDefaults;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.util.GameLogger;
import java.sql.SQLException;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class SkillService {

    private static final GameLogger LOG = GameLogger.getLogger(SkillService.class);
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();

    /** {@code skills} / {@code players_skills} 的读写入口（无状态，可共享）。 */
    private static final SkillDAO SKILL_DAO = new SkillDAO();

    public Skill getSkillById(long skillId) {
        return SKILL_DAO.findById(skillId);
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
        return SKILL_DAO.findAll();
    }

    public List<Skill> getPlayerSkills(long playerId) {
        return SKILL_DAO.findPlayerSkills(playerId);
    }

    public Skill getPlayerSkill(long playerId, long skillId) {
        return SKILL_DAO.findPlayerSkill(playerId, skillId);
    }

    public boolean hasSkill(long playerId, long skillId) {
        return SKILL_DAO.hasSkill(playerId, skillId);
    }

    public Map<String, Object> learnSkill(long playerId, long skillId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Skill skill = getSkillById(skillId);
        if (skill == null) {
            result.put("success", false);
            result.put("message", I18n.t("skill.skill_does_not_exist"));
            result.put("code", 6101);
            return result;
        }

        if (hasSkill(playerId, skillId)) {
            result.put("success", false);
            result.put("message", I18n.t("skill.you_have_already_learned", skill.getName()));
            result.put("code", 6102);
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("skill.character_does_not_exist"));
            result.put("code", 3001);
            return result;
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            result.put("success", false);
            result.put("message", I18n.t("skill.your_realm_is_too_low", skill.getName()));
            result.put("code", 6103);
            return result;
        }

        if (player.getGold() < skill.getLearnCostGold()) {
            result.put("success", false);
            result.put("message", I18n.t("skill.not_enough_gold", skill.getName(), skill.getLearnCostGold()));
            result.put("code", 6104);
            return result;
        }

        if (skill.getLearnCostSpiritStones() > 0) {
            long spiritStoneCount = itemService.getSpiritStoneCount(playerId);
            if (spiritStoneCount < skill.getLearnCostSpiritStones()) {
                result.put("success", false);
                result.put("message", I18n.t("skill.not_enough_spirit_stones", skill.getName(), skill.getLearnCostSpiritStones()));
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
                    if (!itemService.removeSpiritStones(conn, playerId, skill.getLearnCostSpiritStones())) {
                        throw new SQLException("灵石扣除失败");
                    }
                }
                // 再插入技能记录
                SKILL_DAO.insertPlayerSkill(conn, playerId, skillId);
                return null;
            });
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", I18n.t("skill.failed_to_learn_the_skill", e.getMessage()));
            result.put("code", 6106);
            return result;
        }

        result.put("success", true);
        result.put("message", I18n.t("skill.successfully_learned", skill.getName()));
        result.put("skill", skill);
        ServiceRegistry.getDailyService().onSkillLearn(playerId);

        // 学会第一个技能授予「剑道学徒」
        try {
            new com.mtxgdn.game.service.TitleService().grantTitle(playerId, "sword_student");
        } catch (Exception ignored) {
        }
        return result;
    }

    public Map<String, Object> learnSkillFromBook(long playerId, long skillId) {
        Map<String, Object> result = new LinkedHashMap<>();

        Skill skill = getSkillById(skillId);
        if (skill == null) {
            result.put("success", false);
            result.put("message", I18n.t("skill.the_skill_recorded"));
            return result;
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("skill.character_does_not_exist"));
            return result;
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            result.put("success", false);
            result.put("message", I18n.t("skill.your_realm_is_too_low_2", skill.getName()));
            return result;
        }

        Skill existingSkill = getPlayerSkill(playerId, skillId);
        if (existingSkill != null) {
            int currentLevel = existingSkill.getLevel();
            int maxLevel = skill.getMaxLevel();
            if (currentLevel >= maxLevel) {
                result.put("success", false);
                result.put("message", I18n.t("skill.has_reached_max_level_so", skill.getName(), maxLevel));
                return result;
            }
            int newLevel = currentLevel + 1;
            SKILL_DAO.updateLevel(playerId, skillId, newLevel);
            result.put("success", true);
            result.put("message", I18n.t("skill.comprehension_successful_2", skill.getName(), newLevel));
            result.put("skill", skill);
            result.put("newLevel", newLevel);
            return result;
        }

        SKILL_DAO.insertPlayerSkill(playerId, skillId);

        result.put("success", true);
        result.put("message", I18n.t("skill.comprehension_successful", skill.getName()));
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

        SKILL_DAO.updateLevelAndProficiency(playerId, skillId, currentLevel, newProficiency);

        result.put("success", true);
        result.put("leveledUp", leveledUp);
        result.put("level", currentLevel);
        result.put("proficiency", newProficiency);
        result.put("proficiencyForNext", getProficiencyForNextLevel(currentLevel));
        return result;
    }


    public void insertDefaultSkills() {
        SKILL_DAO.insertDefaults(SkillDefaults.INSERT_STATEMENTS);
        LOG.info("已初始化默认技能数据");
    }
}
