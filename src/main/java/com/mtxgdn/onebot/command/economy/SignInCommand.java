package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class SignInCommand extends Command {
    public SignInCommand() {
        super(new String[]{"签到", "signin"}, "每日签到领取灵石奖励", "/签到", "经济", null);

        addRoute(RouteDefinition.post("economy/signin", this::handleSignInHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.signIn(p.getId());

        StringBuilder sb = new StringBuilder();
        if ((boolean) result.get("success")) {
            int streak = (int) result.get("streak");
            sb.append("☀ 签到成功！\n");
            sb.append("连续签到第 ").append(streak).append(" 天\n");
            sb.append(result.get("reward"));

            // 展示签到进度条
            sb.append("\n\n📅 本周进度: ");
            for (int i = 1; i <= 7; i++) {
                if (i <= streak) sb.append("●");
                else sb.append("○");
            }
        } else {
            sb.append(result.get("message"));
        }
        ctx.reply(sb.toString());
    }

    // ==================== REST API ====================

    private JsonObject handleSignInHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var eco = ServiceRegistry.getEconomyService();
            var data = eco.signIn(ctx.playerId());
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
            if (data.containsKey("day")) result.addProperty("day", (int) data.get("day"));
            if (data.containsKey("streak")) result.addProperty("streak", (int) data.get("streak"));
            if (data.containsKey("reward")) result.addProperty("reward", (String) data.get("reward"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
