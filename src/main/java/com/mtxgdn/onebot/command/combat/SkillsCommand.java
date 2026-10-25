package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.db.SkillDAO;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import com.mtxgdn.i18n.I18n;

public class SkillsCommand extends Command {

    private static final SkillDAO SKILL_DAO = new SkillDAO();
    public SkillsCommand() {
        super(new String[]{"技能", "skills"}, "查看可用技能列表", "/技能", "战斗", "game.player.info");
        
        setI18nKeys("cmd.skills.desc", "cmd.skills.usage");
    setModule("skill");

        registerSub(new String[]{"升级", "upgrade"}, this::upgradeSkill);
    }
    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;
        var skillService = ServiceRegistry.getSkillService();
        var guideService = ServiceRegistry.getGuideService();
        List<Skill> allSkills = skillService.getAllSkills();
        if (allSkills.isEmpty()) { ctx.reply(I18n.t("combat.no_skills_available")); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 技能列表 =====\n");
        for (Skill s : allSkills) {
            sb.append("[ID:").append(s.getId()).append("] ").append(s.getName());
            sb.append(" 境界要求:").append(CommandContext.realmName(s.getRequiredRealm()));
            sb.append(" 金币:").append(s.getLearnCostGold()).append("\n");
        }
        sb.append("\n使用 /学习 <技能ID或名称> 学习");
        ctx.reply(sb.toString());
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "skills");
        if (discTip != null) ctx.reply(discTip);
    }

    private void upgradeSkill(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 2) {
            ctx.reply(I18n.t("combat.usage_3"));
            return;
        }

        long skillId;
        try {
            skillId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("combat.invalid_skill_id"));
            return;
        }

        var skillService = ServiceRegistry.getSkillService();
        Skill skill = skillService.getPlayerSkill(p.getId(), skillId);
        if (skill == null) {
            ctx.reply(I18n.t("combat.you_have_not_learned"));
            return;
        }

        int currentLevel = skill.getLevel();
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) {
            ctx.reply(I18n.t("combat.skill_is_already_at_max", skill.getName(), maxLevel));
            return;
        }

        int proficiencyNeeded = 100 * (currentLevel + 1);
        int currentProficiency = skill.getProficiency();
        if (currentProficiency < proficiencyNeeded) {
            int remaining = proficiencyNeeded - currentProficiency;
            ctx.reply(I18n.t("combat.not_enough_proficiency", skill.getName(), currentLevel, proficiencyNeeded, currentProficiency, remaining));
            return;
        }

        int newProficiency = currentProficiency - proficiencyNeeded;
        int newLevel = currentLevel + 1;

        try {
            SKILL_DAO.updateLevelAndProficiency(p.getId(), skillId, newLevel, newProficiency);
        } catch (RuntimeException e) {
            ctx.reply(I18n.t("combat.upgrade_failed", e.getMessage()));
            return;
        }

        ctx.reply(I18n.t("combat.upgrade_successful", skill.getName(), currentLevel, newLevel, proficiencyNeeded, newProficiency));
    }
}
