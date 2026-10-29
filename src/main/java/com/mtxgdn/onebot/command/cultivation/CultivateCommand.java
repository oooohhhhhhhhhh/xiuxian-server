package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.game.cultivation.BeginCultivationResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.SeasonService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.Map;

public class CultivateCommand extends Command {

    public CultivateCommand() {
        super(new String[]{"打坐", "闭关", "meditate", "cultivate"},
                "开始打坐修炼",
                "/打坐",
                "修炼",
                "game.cultivate");
        
        setI18nKeys("cmd.cultivate.desc", "cmd.cultivate.usage");
    setModule("cultivation");
    }

    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        var playerService = ServiceRegistry.getPlayerService();
        var actionLog = PlayerActionLogger.getInstance();
        Long userId = p.getUserId();

        BeginCultivationResult result = playerService.beginCultivation(p.getId());
        if (!result.success()) {
            ctx.reply(result.message());
            return;
        }

        actionLog.logCultivateStart(userId, p.getName(), p.getRealm());

        int ratePerSec = result.cultivationPerSecond();
        int ratePerMin = ratePerSec * 60;
        double seasonMultiplier = result.seasonMultiplier();
        double locationMultiplier = result.locationMultiplier();

        String msg = "开始打坐！\n"
                + "每分钟获得约 " + ratePerMin + " 灵力\n"
                + "当前季节: " + SeasonService.getCurrentSeason().getDisplayName()
                + "（×" + seasonMultiplier + "）\n"
                + "闭关地点: " + result.locationName() + "（灵气 ×" + locationMultiplier + "）\n"
                + "使用 /停止 结束打坐并结算灵力";

        NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                .checkAndAdvance((int) p.getId(), p, "cultivate_start");
        if (guide.message != null) msg += "\n\n💡 " + guide.message;

        ctx.reply(msg);
    }
}
