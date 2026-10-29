package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.game.economy.SignInResult;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class SignInCommand extends Command {
    public SignInCommand() {
        super(new String[]{"签到", "signin"}, "每日签到领取灵石奖励", "/签到", "经济", null);
        
        setI18nKeys("cmd.sign_in.desc", "cmd.sign_in.usage");
    setModule("economy");

        addRoute(RouteDefinition.post("economy/signin", this::handleSignInHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var eco = ServiceRegistry.getEconomyService();
        SignInResult result = eco.signIn(p.getId());

        StringBuilder sb = new StringBuilder();
        if (result.success()) {
            int streak = result.streak() != null ? result.streak() : 0;
            sb.append("☀ 签到成功！\n");
            sb.append("连续签到第 ").append(streak).append(" 天\n");
            sb.append(result.reward());

            // 展示签到进度条
            sb.append("\n\n📅 本周进度: ");
            for (int i = 1; i <= 7; i++) {
                if (i <= streak) sb.append("●");
                else sb.append("○");
            }
        } else {
            sb.append(result.message());
        }
        ctx.reply(sb.toString());
    }

    // ==================== REST API ====================

    private JsonObject handleSignInHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var eco = ServiceRegistry.getEconomyService();
            SignInResult data = eco.signIn(ctx.playerId());
            boolean success = data.success();
            result.addProperty("code", success ? 200 : 400);
            result.addProperty("success", success);
            result.addProperty("message", String.valueOf(data.message()));
            if (data.day() != null) result.addProperty("day", data.day());
            if (data.streak() != null) result.addProperty("streak", data.streak());
            if (data.reward() != null) result.addProperty("reward", data.reward());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
        }
        return result;
    }
}
