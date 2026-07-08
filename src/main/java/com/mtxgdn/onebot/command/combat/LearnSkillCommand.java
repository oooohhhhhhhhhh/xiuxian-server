package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class LearnSkillCommand extends Command {
    public LearnSkillCommand() {
        super(new String[]{"学习", "learn"}, "学习指定技能（支持名称或ID）", "/学习 <技能名称或ID>", "战斗", "game.skill.learn");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        if (!ctx.checkPermission("game.skill.learn")) return;
        String arg = ctx.getArg();
        if (arg == null || arg.trim().isEmpty()) { ctx.reply("用法: /学习 <技能名称或ID>\n先用 /技能 查看可用技能列表。"); return; }
        long skillId;
        var skillService = ServiceRegistry.getSkillService();
        try {
            skillId = Long.parseLong(arg.trim());
        }
        catch (NumberFormatException e) {
            Skill found = skillService.findSkillByName(arg.trim());
            if (found == null) {
                ctx.reply("找不到技能: " + arg.trim() + "\n请使用 /技能 查看可用技能列表。");
                return;
            }
            skillId = found.getId();
        }
        Map<String, Object> learnResult = skillService.learnSkill(p.getId(), skillId);
        boolean success = (boolean) learnResult.getOrDefault("success", false);
        if (success) {
            Skill skill = (Skill) learnResult.get("skill");
            String skillName = skill != null ? skill.getName() : "技能ID:" + skillId;
            ctx.reply("学习成功！\n学会了【" + skillName + "】！");
        } else {
            ctx.reply("学习失败: " + (String) learnResult.getOrDefault("message", ""));
        }
    }
}
