package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandRegistry;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

public class CultivateStopCommand extends Command {

    public CultivateStopCommand() {
        super(new String[]{"stop", "停止"},
                "结束闭关并结算灵力",
                "/停止",
                "修炼",
                "game.cultivate");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.cultivate")) return;

        var playerService = ServiceRegistry.getPlayerService();
        var heartDemonService = ServiceRegistry.getHeartDemonService();
        var guideService = ServiceRegistry.getGuideService();
        var actionLog = PlayerActionLogger.getInstance();

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        if (!p.isCultivating()) {
            ctx.reply("你还没有开始闭关，使用 /修炼 开始。");
            return;
        }

        CultivateCommand cultivateCmd = (CultivateCommand) CommandRegistry.get("cultivate");
        Long startTime = null;
        if (cultivateCmd != null) {
            startTime = cultivateCmd.removeStartTime(userId);
        }

        if (startTime == null) {
            playerService.setCultivating(p.getId(), false);
            ctx.reply("闭关状态异常，已强制结束。");
            return;
        }

        long elapsedMillis = System.currentTimeMillis() - startTime;
        if (elapsedMillis < 1000) {
            playerService.setCultivating(p.getId(), false);
            ctx.reply("闭关时间太短，未获得灵力。");
            return;
        }

        int elapsedSeconds = (int) (elapsedMillis / 1000);
        int currentRealm = p.getRealm();
        int cultivationPerSec = GameConfigLoader.getCultivationPerSecond(currentRealm);
        long expGained = (long) elapsedSeconds * cultivationPerSec;

        playerService.addExperience(userId, expGained);
        playerService.setCultivating(p.getId(), false);
        actionLog.logCultivateStop(userId, p.getName(), expGained, elapsedSeconds);

        StringBuilder msg = new StringBuilder();
        msg.append("===== 闭关结算 =====\n");
        msg.append("修炼时长: ").append(elapsedSeconds).append(" 秒\n");
        msg.append("获得灵力: ").append(expGained).append("\n");

        HeartDemonService.HeartDemonResult heartDemon = heartDemonService.processCultivation(p.getId(), expGained, elapsedSeconds);
        if (heartDemon.triggered) {
            msg.append("\n⚠ 心魔劫: ").append(heartDemon.narrative).append("\n");
            msg.append("灵力损失: ").append(heartDemon.expLost).append("\n");
            msg.append("实际获得: ").append(heartDemon.netExpChange);
        }

        PlayerInfo updated = playerService.getPlayerByUserId(userId);
        msg.append("\n\n").append(CommandContext.formatPlayerStatus(updated));

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance(
                (int) updated.getId(), updated, "cultivate_stop");
        if (guide.message != null) msg.append("\n\n💡 ").append(guide.message);

        ctx.reply(msg.toString());
    }
}
