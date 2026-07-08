package com.mtxgdn.onebot.command.cultivation;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

public class BreakthroughCommand extends Command {

    public BreakthroughCommand() {
        super(new String[]{"突破", "breakthrough"},
                "突破当前境界",
                "/突破",
                "修炼",
                "game.realm.breakthrough");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.realm.breakthrough")) return;

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var realmService = ServiceRegistry.getRealmService();
        var playerService = ServiceRegistry.getPlayerService();
        var actionLog = PlayerActionLogger.getInstance();

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);
        actionLog.logBreakthrough(userId, p.getName(), result.isSuccess(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 境界突破 =====\n");
        if (result.isSuccess()) {
            sb.append(result.getMessage()).append("\n");
            sb.append("生命上限: +").append(result.getHpAdded()).append("\n");
            sb.append("法力上限: +").append(result.getMpAdded()).append("\n");
            sb.append("攻击力: +").append(result.getAttackAdded()).append("\n");
            sb.append("防御力: +").append(result.getDefenseAdded()).append("\n");
            sb.append("速度: +").append(result.getSpeedAdded()).append("\n");
            sb.append("神识: +").append(result.getSpiritAdded());

            PlayerInfo updated = playerService.getPlayerByUserId(userId);
            sb.append("\n\n").append(CommandContext.formatPlayerStatus(updated));

            NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                    .checkAndAdvance((int) updated.getId(), updated, "breakthrough");
            if (guide.message != null) sb.append("\n\n💡 ").append(guide.message);
        } else {
            sb.append(result.getMessage());
        }

        ctx.reply(sb.toString());
    }
}
