package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CultivateCommand extends Command {

    private final Map<Long, Long> meditateStartTimes = new ConcurrentHashMap<>();

    public CultivateCommand() {
        super(new String[]{"打坐", "闭关", "meditate", "cultivate"},
                "开始打坐修炼",
                "/打坐",
                "打坐",
                "game.cultivate");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.cultivate")) return;

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var playerService = ServiceRegistry.getPlayerService();
        var actionLog = PlayerActionLogger.getInstance();

        if (p.isCultivating()) {
            ctx.reply("你已经在打坐中了，使用 /停止 来结束。");
            return;
        }

        playerService.setCultivating(p.getId(), true);
        meditateStartTimes.put(userId, System.currentTimeMillis());
        actionLog.logCultivateStart(userId, p.getName(), p.getRealm());

        int ratePerSec = GameConfigLoader.getCultivationPerSecond(p.getRealm());
        int ratePerMin = ratePerSec * 60;
        String msg = "开始打坐！\n每分钟获得 " + ratePerMin + " 灵力\n使用 /停止 结束打坐并结算灵力";

        NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                .checkAndAdvance((int) p.getId(), p, "cultivate_start");
        if (guide.message != null) msg += "\n\n💡 " + guide.message;

        ctx.reply(msg);
    }

    public Long getStartTime(Long userId) {
        return meditateStartTimes.get(userId);
    }

    public Long removeStartTime(Long userId) {
        return meditateStartTimes.remove(userId);
    }
}
