package com.mtxgdn.onebot.command.daily;

import com.mtxgdn.game.daily.MorningCultivationResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class MorningCommand extends Command {
    public MorningCommand() { super(new String[]{"晨修", "morning"}, "每日晨修，获取天象加成", "/晨修", "修炼", "game.player.info"); 
        setI18nKeys("cmd.morning.desc", "cmd.morning.usage");
    setModule("cultivation"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        MorningCultivationResult result = ServiceRegistry.getDailyService().doMorningCultivation(p.getId());
        String msg = result.message() != null ? result.message() : I18n.t("daily.morning_error");
        if (result.success()) {
            long exp = result.expGained() != null ? result.expGained() : 0L;
            long ss = result.spiritStonesGained() != null ? result.spiritStonesGained() : 0L;
            msg += "\n获得灵力: +" + exp;
            if (ss > 0) msg += "\n获得灵石: +" + ss;
        }
        ctx.reply(msg);
    }
}
