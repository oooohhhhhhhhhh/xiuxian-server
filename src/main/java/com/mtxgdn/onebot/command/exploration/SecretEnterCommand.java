package com.mtxgdn.onebot.command.exploration;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.PlayerActionLogger;

public class SecretEnterCommand extends Command {

    public SecretEnterCommand() {
        super(new String[]{"进入秘境", "secret_enter"},
                "进入指定秘境",
                "/进入秘境 <秘境名称>",
                "探索",
                "game.secret_realm");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.secret_realm")) return;

        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String areaName = ctx.getArg().trim();
        if (areaName.isEmpty()) {
            ctx.reply("用法: /进入秘境 <秘境名称>\n使用 /秘境 查看可用秘境");
            return;
        }

        var secretRealmService = ServiceRegistry.getSecretRealmService();
        var actionLog = PlayerActionLogger.getInstance();

        SecretRealmResult result = secretRealmService.enterSecretRealm(userId, areaName);
        actionLog.logSecretRealmEnter(userId, p.getName(), areaName, result.isSuccess(), result.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("===== 秘境探索 =====\n");
        if (result.isSuccess()) {
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
                .checkAndAdvance((int) p.getId(), p, "secret_enter");
        if (guide.message != null) ctx.reply(guide.message);
    }
}
