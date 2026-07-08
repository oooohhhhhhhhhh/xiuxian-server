package com.mtxgdn.onebot.command.combat;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.List;

public class SkillsCommand extends Command {
    public SkillsCommand() {
        super(new String[]{"技能", "skills"}, "查看可用技能列表", "/技能", "战斗", "game.player.info");
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
}
