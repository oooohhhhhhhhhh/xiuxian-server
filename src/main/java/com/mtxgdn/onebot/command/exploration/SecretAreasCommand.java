package com.mtxgdn.onebot.command.exploration;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.service.NewbieGuideService;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.List;

public class SecretAreasCommand extends Command {

    public SecretAreasCommand() {
        super(new String[]{"secret", "秘境"},
                "查看可用秘境",
                "/秘境",
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

        var secretRealmService = ServiceRegistry.getSecretRealmService();
        List<SecretRealm> areas = secretRealmService.getAvailableAreas(userId);

        if (areas.isEmpty()) {
            ctx.reply("当前没有可用的秘境。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 可用秘境 =====\n");
        for (SecretRealm area : areas) {
            sb.append("【").append(area.getName()).append("】\n");
            sb.append("  所需境界: ").append(CommandContext.realmName(area.getRequiredRealm())).append("\n");
            sb.append("  冷却: ").append(area.getCooldownMs() / 1000).append(" 秒\n");
            sb.append("  ").append(area.getDescription()).append("\n\n");
        }
        sb.append("使用 /进入秘境 <秘境名称> 进入");
        ctx.reply(sb.toString());

        NewbieGuideService.GuideResult guide = ServiceRegistry.getGuideService()
                .checkAndAdvance((int) p.getId(), p, "secret_areas");
        if (guide.message != null) ctx.reply(guide.message);
    }
}
