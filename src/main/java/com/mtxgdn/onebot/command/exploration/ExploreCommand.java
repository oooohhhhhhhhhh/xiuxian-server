package com.mtxgdn.onebot.command.exploration;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

public class ExploreCommand extends Command {

    private static final long COOLDOWN_MS = 60_000;

    public ExploreCommand() {
        super(new String[]{"游历", "explore"},
                "外出游历探索",
                "/游历",
                "探索",
                "game.explore");

        registerSub(new String[]{"状态", "status"}, this::showStatus);
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

    private void showStatus(CommandContext ctx, PlayerInfo p, String[] parts) {
        long now = System.currentTimeMillis();
        long effectiveCd = COOLDOWN_MS;
        if (p.getSpiritualRoot() != null && p.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.EXPLORATION_CD)) {
            effectiveCd = (long) (COOLDOWN_MS * (1 - p.getSpiritualRoot().getEffectValue()));
        }

        long lastTime = p.getLastExplorationTime();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 游历状态 ===\n");
        sb.append("冷却时间: ").append(effectiveCd / 1000).append(" 秒\n");

        if (lastTime <= 0) {
            sb.append("尚未游历过，可以立即游历！");
        } else {
            long elapsed = now - lastTime;
            if (elapsed >= effectiveCd) {
                sb.append("冷却已结束，可以游历！");
            } else {
                long remaining = (effectiveCd - elapsed) / 1000;
                sb.append("冷却中，还需等待 ").append(remaining).append(" 秒");
            }
        }

        ctx.reply(sb.toString());
    }
}
