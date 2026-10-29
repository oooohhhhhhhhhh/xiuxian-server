package com.mtxgdn.onebot.command.item;

import com.mtxgdn.game.buff.ActiveBuffView;
import com.mtxgdn.game.buff.ActiveBuffsResult;
import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.BuffService;

import java.util.List;
import java.util.Map;

public class BuffCommand extends Command {
    public BuffCommand() {
        super(new String[]{"增益", "buff"}, "查看当前增益状态", "/增益", "实用功能", null);
        
        setI18nKeys("cmd.buff.desc", "cmd.buff.usage");
    setModule("buff");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        BuffService buffService = ServiceRegistry.getBuffService();
        ActiveBuffsResult result = buffService.getActiveBuffs(p.getId());
        List<ActiveBuffView> buffs = result.buffs();
        int count = result.count();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 当前增益状态 ===\n");

        if (count == 0) {
            sb.append("暂无临时增益效果");
        } else {
            for (ActiveBuffView buff : buffs) {
                sb.append("ID: ").append(buff.id()).append("\n");
                if (buff.attackBonus() != 0) sb.append("  攻击力: +").append(buff.attackBonus()).append("\n");
                if (buff.defenseBonus() != 0) sb.append("  防御力: +").append(buff.defenseBonus()).append("\n");
                if (buff.speedBonus() != 0) sb.append("  速度: +").append(buff.speedBonus()).append("\n");
                if (buff.spiritBonus() != 0) sb.append("  灵力: +").append(buff.spiritBonus()).append("\n");
                sb.append("  剩余时间: ").append(buff.remainingSeconds()).append("秒\n\n");
            }
        }

        int totalAtk = buffService.getTotalAttackBonus(p.getId());
        int totalDef = buffService.getTotalDefenseBonus(p.getId());
        int totalSpd = buffService.getTotalSpeedBonus(p.getId());
        int totalSpi = buffService.getTotalSpiritBonus(p.getId());

        sb.append("\n【总增益】\n");
        sb.append("攻击力 +").append(totalAtk).append(" | ");
        sb.append("防御力 +").append(totalDef).append(" | ");
        sb.append("速度 +").append(totalSpd).append(" | ");
        sb.append("灵力 +").append(totalSpi);

        ctx.reply(sb.toString());
    }
}