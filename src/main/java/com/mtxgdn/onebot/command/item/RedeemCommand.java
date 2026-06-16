package com.mtxgdn.onebot.command.item;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.service.RedeemCodeService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class RedeemCommand extends Command {

    private static final RedeemCodeService redeemCodeService = new RedeemCodeService();
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();

    public RedeemCommand() {
        super(new String[]{"redeem", "兑换"},
                "使用兑换码领取奖励",
                "/兑换 <兑换码>",
                "背包",
                "game.redeem.code",
                false);

        // 注册 HTTP POST 路由
        addRoute(RouteDefinition.post("redeem", "game.redeem.code", this::handleRedeemHttp));
    }

    // ===== 不显示在 /help =====
    @Override
    public boolean shouldShowInHelp(Long userId) {
        return false;
    }

    // ===== OneBot 处理 =====
    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = new QqBindingService().findByQq(ctx.getSenderId());
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "game.redeem.code")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        var p = playerService.getPlayerByUserId(b.getUserId());
        if (p == null) {
            ctx.reply("请先创建角色。");
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply("用法: /兑换 <兑换码>\n请输入要使用的兑换码。");
            return;
        }

        String result = redeemCodeService.doRedeem(arg.trim(), p.getId());
        if (result == null) {
            ctx.reply("兑换成功！");
        } else if (result.startsWith("SUCCESS:")) {
            String rewards = result.substring("SUCCESS:".length());
            ctx.reply("兑换成功！获得: " + rewards);
        } else {
            ctx.reply(result);
        }
    }

    // ===== HTTP 处理 =====
    private JsonObject handleRedeemHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            String body = ctx.body();
            if (body == null || body.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", "请求体不能为空");
                return result;
            }
            JsonObject req = ctx.bodyJson();
            String code = req.has("code") ? req.get("code").getAsString() : "";

            if (code.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供兑换码");
                return result;
            }

            int playerId = ctx.playerId();
            if (playerId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "请先进入游戏");
                return result;
            }

            String redeemResult = redeemCodeService.doRedeem(code.trim(), playerId);
            if (redeemResult == null) {
                result.addProperty("code", 200);
                result.addProperty("message", "兑换成功！");
            } else if (redeemResult.startsWith("SUCCESS:")) {
                String rewards = redeemResult.substring("SUCCESS:".length());
                result.addProperty("code", 200);
                result.addProperty("message", "兑换成功！获得: " + rewards);
            } else {
                result.addProperty("code", 400);
                result.addProperty("message", redeemResult);
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
