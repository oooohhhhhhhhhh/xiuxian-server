package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;

public class CultivateBoostCommand extends Command {
    public CultivateBoostCommand() {
        super(new String[]{"修炼加速", "boost"}, "燃烧灵石加速修炼", "/修炼加速 <灵石数量> （每100灵石=1小时×1.5倍）", "经济", null);

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
            ctx.reply("⚡ 灵石修炼加速\n"
                    + "每 100 灵石 = 1 小时 ×1.5 倍修炼效率\n"
                    + "当前灵石: " + stones + "\n"
                    + "用法: /修炼加速 <灵石数量>\n"
                    + "注意: 必须先 /修炼 后才能使用");
            return;
        }

        try {
            int stonesToBurn = Integer.parseInt(arg);
            var eco = ServiceRegistry.getEconomyService();
            var result = eco.boostCultivation(p.getId(), stonesToBurn);
            ctx.reply((String) result.get("message"));
        } catch (NumberFormatException e) {
            ctx.reply("请输入有效的灵石数量");
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
                result.addProperty("message", "请提供有效的灵石数量(stones)");
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            Map<String, Object> data = eco.boostCultivation(ctx.playerId(), stonesToBurn);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
