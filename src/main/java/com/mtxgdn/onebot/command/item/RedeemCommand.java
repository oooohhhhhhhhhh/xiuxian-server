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
import com.mtxgdn.i18n.I18n;

public class RedeemCommand extends Command {

    private static final com.mtxgdn.util.GameLogger LOG =
            com.mtxgdn.util.GameLogger.getLogger(RedeemCommand.class);

    private static final RedeemCodeService redeemCodeService = ServiceRegistry.getRedeemCodeService();
    private static final PlayerService playerService = ServiceRegistry.getPlayerService();

    public RedeemCommand() {
        super(new String[]{"兑换", "redeem"},
                "使用兑换码领取奖励",
                "/兑换 <兑换码>",
                "背包",
                "game.redeem.code",
                false);

        
        setI18nKeys("cmd.redeem.desc", "cmd.redeem.usage");
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
        try {
            doExecute(ctx);
        } catch (Exception e) {
            ctx.reply(I18n.t("redeem.h4943b29e"));
            LOG.error("兑换指令执行异常: " + e.getMessage(), e);
            // 不再 printStackTrace：异常要进日志文件，也不能把堆栈刷到控制台
        }
    }

    private void doExecute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply(I18n.t("redeem.hd852a51e"));
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "game.redeem.code")) {
            ctx.reply(I18n.t("redeem.ha43d576a"));
            return;
        }

        var p = playerService.getPlayerByUserId(b.getUserId());
        if (p == null) {
            ctx.reply(I18n.t("redeem.h0b0f09b4"));
            return;
        }

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(I18n.t("redeem.h5468348e"));
            return;
        }

        String result = redeemCodeService.doRedeem(arg.trim(), p.getId());
        if (result == null) {
            ctx.reply(I18n.t("redeem.h2128a290"));
        } else if (result.startsWith("SUCCESS:")) {
            String rewards = result.substring("SUCCESS:".length());
            ctx.reply(I18n.t("redeem.h660db410", rewards));
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
                result.addProperty("message", I18n.t("redeem.h3a7bedb4"));
                return result;
            }
            JsonObject req = ctx.bodyJson();
            String code = req.has("code") ? req.get("code").getAsString() : "";

            if (code.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("redeem.h4616a2ab"));
                return result;
            }

            int playerId = ctx.playerId();
            if (playerId <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("redeem.h9e82cff4"));
                return result;
            }

            String redeemResult = redeemCodeService.doRedeem(code.trim(), playerId);
            if (redeemResult == null) {
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("redeem.h2128a290"));
            } else if (redeemResult.startsWith("SUCCESS:")) {
                String rewards = redeemResult.substring("SUCCESS:".length());
                result.addProperty("code", 200);
                result.addProperty("message", I18n.t("redeem.h660db410", rewards));
            } else {
                result.addProperty("code", 400);
                result.addProperty("message", redeemResult);
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("redeem.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
