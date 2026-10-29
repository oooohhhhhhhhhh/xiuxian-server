package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.game.cultivation.FinishCultivationResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.HeartDemonService;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.game.service.SeasonService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class CultivateStopCommand extends Command {

    public CultivateStopCommand() {
        super(new String[]{"停止", "stop"},
                "结束打坐并结算灵力",
                "/停止",
                "修炼",
                "game.cultivate");
        
        setI18nKeys("cmd.cultivate_stop.desc", "cmd.cultivate_stop.usage");
    setModule("cultivation");
    }

    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        var playerService = ServiceRegistry.getPlayerService();
        var heartDemonService = ServiceRegistry.getHeartDemonService();
        var guideService = ServiceRegistry.getGuideService();
        var actionLog = PlayerActionLogger.getInstance();
        Long userId = p.getUserId();

        FinishCultivationResult result = playerService.finishCultivation(p.getId());
        if (!result.success()) {
            ctx.reply(result.message());
            return;
        }

        int elapsedSeconds = result.elapsedSeconds().intValue();
        long expGained = result.rawExpGained();

        if (expGained <= 0) {
            actionLog.logCultivateStop(userId, p.getName(), 0, elapsedSeconds);
            ctx.reply(I18n.t("cultivation.the_meditation_was_too_short"));
            return;
        }

        // 心魔判定：直接按净收益发放，避免 QQ 端与 REST/WS 端行为不一致
        HeartDemonService.HeartDemonResult heartDemon = heartDemonService.processCultivation(p.getId(), expGained, elapsedSeconds);
        long netExp = heartDemon.netExpChange;
        if (netExp != 0) {
            playerService.addExperience(p.getId(), netExp);
        }
        actionLog.logCultivateStop(userId, p.getName(), netExp, elapsedSeconds);

        StringBuilder msg = new StringBuilder();
        msg.append("===== 打坐结算 =====\n");
        msg.append("打坐时长: ").append(elapsedSeconds).append(" 秒\n");
        double seasonMultiplier = result.seasonMultiplier();
        double locationMultiplier = result.locationMultiplier();
        msg.append("季节: ").append(SeasonService.getCurrentSeason().getDisplayName())
                .append("（×").append(seasonMultiplier).append("）\n");
        msg.append("地点: ").append(result.locationName())
                .append("（灵气 ×").append(locationMultiplier).append("）\n");
        msg.append("获得灵力: ").append(netExp).append("\n");

        if (heartDemon.triggered) {
            msg.append("\n⚠ 心魔劫: ").append(heartDemon.narrative).append("\n");
            msg.append("灵力损失: ").append(heartDemon.expLost);
        }

        PlayerInfo updated = playerService.getPlayerByUserId(userId);
        msg.append("\n\n").append(CommandContext.formatPlayerStatus(updated));

        NewbieGuideService.GuideResult guide = guideService.checkAndAdvance(
                (int) updated.getId(), updated, "cultivate_stop");
        if (guide.message != null) msg.append("\n\n💡 ").append(guide.message);

        ctx.reply(msg.toString());
    }
}
