package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.db.SkillDAO;
import com.mtxgdn.game.config.SkillDefaults;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.skill.LearnSkillFromBookResult;
import com.mtxgdn.game.skill.LearnSkillResult;
import com.mtxgdn.game.skill.SkillProficiencyResult;
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

    public LearnSkillResult learnSkill(long playerId, long skillId) {
        Skill skill = getSkillById(skillId);
        if (skill == null) {
            return LearnSkillResult.fail(I18n.t("skill.skill_does_not_exist"), 6101);
        }

        if (hasSkill(playerId, skillId)) {
            return LearnSkillResult.fail(
                    I18n.t("skill.you_have_already_learned", skill.getName()), 6102);
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return LearnSkillResult.fail(I18n.t("skill.character_does_not_exist"), 3001);
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            return LearnSkillResult.fail(
                    I18n.t("skill.your_realm_is_too_low", skill.getName()), 6103);
        }

        if (player.getGold() < skill.getLearnCostGold()) {
            return LearnSkillResult.fail(I18n.t(
                    "skill.not_enough_gold", skill.getName(), skill.getLearnCostGold()), 6104);
        }

        if (skill.getLearnCostSpiritStones() > 0) {
            long spiritStoneCount = itemService.getSpiritStoneCount(playerId);
            if (spiritStoneCount < skill.getLearnCostSpiritStones()) {
                return LearnSkillResult.fail(I18n.t(
                        "skill.not_enough_spirit_stones",
                        skill.getName(), skill.getLearnCostSpiritStones()), 6104);
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
            return LearnSkillResult.fail(
                    I18n.t("skill.failed_to_learn_the_skill", e.getMessage()), 6106);
        }

        ServiceRegistry.getDailyService().onSkillLearn(playerId);

        // 学会第一个技能授予「剑道学徒」
        try {
            new com.mtxgdn.game.service.TitleService().grantTitle(playerId, "sword_student");
        } catch (Exception ignored) {
        }
        return LearnSkillResult.ok(
                I18n.t("skill.successfully_learned", skill.getName()), skill);
    }

    public LearnSkillFromBookResult learnSkillFromBook(long playerId, long skillId) {
        Skill skill = getSkillById(skillId);
        if (skill == null) {
            return LearnSkillFromBookResult.fail(I18n.t("skill.the_skill_recorded"));
        }

        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return LearnSkillFromBookResult.fail(I18n.t("skill.character_does_not_exist"));
        }

        if (player.getRealm() < skill.getRequiredRealm()) {
            return LearnSkillFromBookResult.fail(
                    I18n.t("skill.your_realm_is_too_low_2", skill.getName()));
        }

        Skill existingSkill = getPlayerSkill(playerId, skillId);
        if (existingSkill != null) {
            int currentLevel = existingSkill.getLevel();
            int maxLevel = skill.getMaxLevel();
            if (currentLevel >= maxLevel) {
                return LearnSkillFromBookResult.fail(
                        I18n.t("skill.has_reached_max_level_so", skill.getName(), maxLevel));
            }
            int newLevel = currentLevel + 1;
            SKILL_DAO.updateLevel(playerId, skillId, newLevel);
            return LearnSkillFromBookResult.leveledUp(
                    I18n.t("skill.comprehension_successful_2", skill.getName(), newLevel),
                    skill, newLevel);
        }

        SKILL_DAO.insertPlayerSkill(playerId, skillId);

        return LearnSkillFromBookResult.ok(
                I18n.t("skill.comprehension_successful", skill.getName()), skill);
    }

    public int getProficiencyForNextLevel(int currentLevel) {
        return currentLevel * 100;
    }

    public SkillProficiencyResult addProficiency(long playerId, long skillId, int amount) {
        Skill skill = getPlayerSkill(playerId, skillId);
        if (skill == null) {
            return SkillProficiencyResult.fail();
        }

        Player player = playerService.getPlayerById(playerId);
        if (player != null && player.getSpiritualRoot() != null
                && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.PROFICIENCY_BOOST)) {
            amount = (int)(amount * (1 + player.getSpiritualRoot().getEffectValue()));
        }

        int currentLevel = skill.getLevel();
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) {
            return SkillProficiencyResult.maxed(currentLevel);
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

        return SkillProficiencyResult.of(
                leveledUp, currentLevel, newProficiency,
                getProficiencyForNextLevel(currentLevel));
    }


    public void insertDefaultSkills() {
        SKILL_DAO.insertDefaults(SkillDefaults.INSERT_STATEMENTS);
        LOG.info("已初始化默认技能数据");
    }
}
