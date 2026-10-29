package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.game.economy.BoostResult;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class CultivateBoostCommand extends Command {
    public CultivateBoostCommand() {
        super(new String[]{"修炼加速", "boost"}, "燃烧灵石加速修炼", "/修炼加速 <灵石数量> （每100灵石=1小时×1.5倍）", "经济", null);
        
        setI18nKeys("cmd.cultivate_boost.desc", "cmd.cultivate_boost.usage");
    setModule("economy");

        addRoute(RouteDefinition.post("economy/cultivate-boost", this::handleBoostHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg().trim();
        if (arg.isEmpty()) {
            long stones = ServiceRegistry.getItemService().getSpiritStoneCount(p.getId());
            ctx.reply(I18n.t("economy.spirit_stone_cultivation_boost_nevery", stones));
            return;
        }

        try {
            int stonesToBurn = Integer.parseInt(arg);
            if (stonesToBurn <= 0) {
                ctx.reply(I18n.t("economy.the_amount_of_spirit_stones"));
                return;
            }
            var eco = ServiceRegistry.getEconomyService();
            BoostResult result = eco.boostCultivation(p.getId(), stonesToBurn);
            ctx.reply(result.message());
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.please_enter_a_valid_number"));
        }
    }

    // ==================== REST API ====================

    private JsonObject handleBoostHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            int stonesToBurn = req.has("stones") ? req.get("stones").getAsInt() : 0;
            if (stonesToBurn <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.please_provide_a_valid_number"));
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            BoostResult data = eco.boostCultivation(ctx.playerId(), stonesToBurn);
            result.addProperty("code", data.success() ? 200 : 400);
            result.addProperty("success", data.success());
            result.addProperty("message", data.message());
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
        }
        return result;
    }
}
