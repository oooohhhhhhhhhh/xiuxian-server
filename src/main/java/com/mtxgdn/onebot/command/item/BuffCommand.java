package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;
import java.util.Map;

public class BuffCommand extends Command {
    public BuffCommand() {
        super(new String[]{"buff", "状态", "增益"}, "查看当前增益状态", "/状态", "实用功能", null);
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        BuffService buffService = new BuffService();
        Map<String, Object> result = buffService.getActiveBuffs(p.getId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buffs = (List<Map<String, Object>>) result.get("buffs");
        int count = (int) result.get("count");

        StringBuilder sb = new StringBuilder();
        sb.append("=== 当前增益状态 ===\n");

        if (count == 0) {
            sb.append("暂无临时增益效果");
        } else {
            for (Map<String, Object> buff : buffs) {
                sb.append("ID: ").append(buff.get("id")).append("\n");
                if ((int) buff.get("attackBonus") != 0) sb.append("  攻击力: +").append(buff.get("attackBonus")).append("\n");
                if ((int) buff.get("defenseBonus") != 0) sb.append("  防御力: +").append(buff.get("defenseBonus")).append("\n");
                if ((int) buff.get("speedBonus") != 0) sb.append("  速度: +").append(buff.get("speedBonus")).append("\n");
                if ((int) buff.get("spiritBonus") != 0) sb.append("  灵力: +").append(buff.get("spiritBonus")).append("\n");
                sb.append("  剩余时间: ").append(buff.get("remainingSeconds")).append("秒\n\n");
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