package com.mtxgdn.onebot.command.daily;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class MorningCommand extends Command {
    public MorningCommand() { super(new String[]{"晨修", "morning"}, "每日晨修，获取天象加成", "/晨修", "修炼", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var result = ServiceRegistry.getDailyService().doMorningCultivation(p.getId());
        String msg = (String) result.getOrDefault("message", "晨修出现异常");
        if (Boolean.TRUE.equals(result.get("success"))) {
            long exp = ((Number) result.getOrDefault("expGained", 0)).longValue();
            long ss = ((Number) result.getOrDefault("spiritStonesGained", 0)).longValue();
            msg += "\n获得灵力: +" + exp;
            if (ss > 0) msg += "\n获得灵石: +" + ss;
        }
        ctx.reply(msg);
    }
}
