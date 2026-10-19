package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import com.mtxgdn.i18n.I18n;

public class SkillsCommand extends Command {
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
        if (allSkills.isEmpty()) { ctx.reply(I18n.t("combat.hc0c642ae")); return; }
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
            ctx.reply(I18n.t("combat.h5ff9c1a2"));
            return;
        }

        long skillId;
        try {
            skillId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("combat.hc411208c"));
            return;
        }

        var skillService = ServiceRegistry.getSkillService();
        Skill skill = skillService.getPlayerSkill(p.getId(), skillId);
        if (skill == null) {
            ctx.reply(I18n.t("combat.h940932f1"));
            return;
        }

        int currentLevel = skill.getLevel();
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) {
            ctx.reply(I18n.t("combat.h9805e04c", skill.getName(), maxLevel));
            return;
        }

        int proficiencyNeeded = 100 * (currentLevel + 1);
        int currentProficiency = skill.getProficiency();
        if (currentProficiency < proficiencyNeeded) {
            int remaining = proficiencyNeeded - currentProficiency;
            ctx.reply(I18n.t("combat.he295e0a4", skill.getName(), currentLevel, proficiencyNeeded, currentProficiency, remaining));
            return;
        }

        int newProficiency = currentProficiency - proficiencyNeeded;
        int newLevel = currentLevel + 1;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE players_skills SET level = ?, proficiency = ? WHERE player_id = ? AND skill_id = ?")) {
            ps.setInt(1, newLevel);
            ps.setInt(2, newProficiency);
            ps.setLong(3, p.getId());
            ps.setLong(4, skillId);
            ps.executeUpdate();
        } catch (SQLException e) {
            ctx.reply(I18n.t("combat.h585194df", e.getMessage()));
            return;
        }

        ctx.reply(I18n.t("combat.ha04c1ada", skill.getName(), currentLevel, newLevel, proficiencyNeeded, newProficiency));
    }
}
