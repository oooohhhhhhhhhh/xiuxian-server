package com.mtxgdn.onebot.command.economy;

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
            ctx.reply(I18n.t("economy.hc6b261e0", stones));
            return;
        }

        try {
            int stonesToBurn = Integer.parseInt(arg);
            if (stonesToBurn <= 0) {
                ctx.reply(I18n.t("economy.h8cde9058"));
                return;
            }
            var eco = ServiceRegistry.getEconomyService();
            var result = eco.boostCultivation(p.getId(), stonesToBurn);
            ctx.reply((String) result.get("message"));
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.h85187339"));
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
                result.addProperty("message", I18n.t("economy.h648cf5ac"));
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            Map<String, Object> data = eco.boostCultivation(ctx.playerId(), stonesToBurn);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
