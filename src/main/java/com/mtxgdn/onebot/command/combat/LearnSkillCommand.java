package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.game.skill.LearnSkillResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class LearnSkillCommand extends Command {
    public LearnSkillCommand() {
        super(new String[]{"学习", "learn"}, "学习指定技能（支持名称或ID）", "/学习 <技能名称或ID>", "战斗", "game.skill.learn");
        
        setI18nKeys("cmd.learn_skill.desc", "cmd.learn_skill.usage");
    setModule("skill");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        if (!ctx.checkPermission("game.skill.learn")) return;
        String arg = ctx.getArg();
        if (arg == null || arg.trim().isEmpty()) { ctx.reply(I18n.t("combat.usage")); return; }
        long skillId;
        var skillService = ServiceRegistry.getSkillService();
        try {
            skillId = Long.parseLong(arg.trim());
        }
        catch (NumberFormatException e) {
            Skill found = skillService.findSkillByName(arg.trim());
            if (found == null) {
                ctx.reply(I18n.t("combat.skill_not_found", arg.trim()));
                return;
            }
            skillId = found.getId();
        }
        LearnSkillResult learnResult = skillService.learnSkill(p.getId(), skillId);
        if (learnResult.success()) {
            Skill skill = learnResult.skill();
            String skillName = skill != null ? skill.getName() : "技能ID:" + skillId;
            ctx.reply(I18n.t("combat.learned_successfully", skillName));
        } else {
            // 服务端已经给出了本地化的失败原因，这里只套一个前后缀；
            // 服务端消息本身也走 I18n，所以整句仍是当前语言
            ctx.reply(I18n.t("skill.learn.failed", learnResult.message()));
        }
    }
}
