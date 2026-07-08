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

public class SkillsCommand extends Command {
    public SkillsCommand() {
        super(new String[]{"技能", "skills"}, "查看可用技能列表", "/技能", "战斗", "game.player.info");

        registerSub(new String[]{"升级", "upgrade"}, this::upgradeSkill);
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var skillService = ServiceRegistry.getSkillService();
        var guideService = ServiceRegistry.getGuideService();
        List<Skill> allSkills = skillService.getAllSkills();
        if (allSkills.isEmpty()) { ctx.reply("暂无可用技能。"); return; }
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
            ctx.reply("用法: /技能 升级 <技能ID>");
            return;
        }

        long skillId;
        try {
            skillId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            ctx.reply("技能ID无效，请输入数字");
            return;
        }

        var skillService = ServiceRegistry.getSkillService();
        Skill skill = skillService.getPlayerSkill(p.getId(), skillId);
        if (skill == null) {
            ctx.reply("你尚未学习该技能，无法升级");
            return;
        }

        int currentLevel = skill.getLevel();
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) {
            ctx.reply("技能【" + skill.getName() + "】已达满级 " + maxLevel + "，无法继续升级");
            return;
        }

        int proficiencyNeeded = 100 * (currentLevel + 1);
        int currentProficiency = skill.getProficiency();
        if (currentProficiency < proficiencyNeeded) {
            int remaining = proficiencyNeeded - currentProficiency;
            ctx.reply("熟练度不足！技能【" + skill.getName() + "】当前等级 " + currentLevel +
                    "，升级需要 " + proficiencyNeeded + " 熟练度，当前拥有 " + currentProficiency +
                    "，还需 " + remaining + " 熟练度");
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
            ctx.reply("升级失败: " + e.getMessage());
            return;
        }

        ctx.reply("升级成功！技能【" + skill.getName() + "】从 " + currentLevel + " 级升至 " + newLevel +
                " 级！消耗 " + proficiencyNeeded + " 熟练度，剩余 " + newProficiency + " 熟练度");
    }
}
