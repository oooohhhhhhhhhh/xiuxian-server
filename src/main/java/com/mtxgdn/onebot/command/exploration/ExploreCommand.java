package com.mtxgdn.onebot.command.exploration;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

public class ExploreCommand extends Command {

    public ExploreCommand() {
        super(new String[]{"游历", "explore"},
                "外出游历探索",
                "/游历",
                "探索",
                "game.explore");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.explore")) return;

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var explorationService = ServiceRegistry.getExplorationService();
        var actionLog = PlayerActionLogger.getInstance();

        ExplorationResult result = explorationService.explore(userId);
        actionLog.logExploration(userId, p.getName(),
                result.getEventType() != null ? result.getEventType() : "未知", result.getMessage());

        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("===== 游历探索 =====\n");
            if (result.getLog() != null) for (String l : result.getLog()) sb.append(l).append("\n");
            sb.append("\n").append(result.getMessage());
            if (result.getExpGained() > 0) sb.append("\n灵力: +").append(result.getExpGained());
            if (result.getGoldGained() > 0) sb.append("\n金币: +").append(result.getGoldGained());
            if (result.getSpiritStonesGained() > 0) sb.append("\n灵石: +").append(result.getSpiritStonesGained());
            if (result.getItemGained() != null)
                sb.append("\n获得物品: ").append(CommandContext.itemName(result.getItemGained()))
                        .append(" x").append(result.getItemQuantity());
            if (result.getHpLost() > 0) sb.append("\n损失生命: -").append(result.getHpLost());
        } else { sb.append(result.getMessage()); }
        ctx.reply(sb.toString());

        NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                .checkAndAdvance((int) p.getId(), p, "explore");
        if (guide.message != null) ctx.reply(guide.message);
    }
}
