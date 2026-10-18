package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.service.EconomyService;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

/**
 * /提纯 — 提纯灵石，将低级灵石升为高级灵石。
 * 需要境界、消耗洞府灵气与大量低阶灵石，有概率失败。
 */
public class PurifyCommand extends Command {

    private EconomyService economyService() {
        return ServiceRegistry.getEconomyService();
    }

    public PurifyCommand() {
        super(new String[]{"提纯", "purify", "灵石提纯"},
                "提纯灵石，将低级灵石升为高级灵石（消耗灵气，有概率失败，有境界需求）",
                "/提纯 <下品|中品|上品>",
                "经济",
                "game.stone.purify");
        setModule("economy");

        addRoute(RouteDefinition.post("economy/purify", this::handlePurifyHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) { ctx.reply(I18n.t("economy.hde5e9ffe")); return; }

        String args = ctx.getArg();
        if (args == null || args.trim().isEmpty()) {
            ctx.reply(economyService().getPurifyHelp(p.getId()));
            return;
        }

        String gradeName = args.trim().split("\\s+")[0];
        int grade = parseGrade(gradeName);
        if (grade < 0) {
            ctx.reply(I18n.t("economy.hdf2641a0", gradeName, economyService().getPurifyHelp(p.getId())));
            return;
        }

        Map<String, Object> result = economyService().purifySpiritStone(p.getId(), grade);
        ctx.reply((String) result.get("message"));
    }

    private int parseGrade(String name) {
        return switch (name) {
            case "下品", "low", "0" -> 0;
            case "中品", "mid", "1" -> 1;
            case "上品", "high", "2" -> 2;
            default -> -1;
        };
    }

    // ==================== REST API ====================

    /** POST /api/game/economy/purify — body: {grade: 0|1|2} */
    private JsonObject handlePurifyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            int grade = req.has("grade") ? req.get("grade").getAsInt() : -1;
            if (grade < 0 || grade > 2) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h41e94abc"));
                return result;
            }
            Map<String, Object> data = ServiceRegistry.getEconomyService().purifySpiritStone(ctx.playerId(), grade);
            result.addProperty("code", Boolean.TRUE.equals(data.get("success")) ? 200 : 400);
            result.addProperty("success", Boolean.TRUE.equals(data.get("success")));
            result.addProperty("purifySuccess", data.containsKey("purifySuccess") && Boolean.TRUE.equals(data.get("purifySuccess")));
            result.addProperty("message", (String) data.get("message"));
            if (data.containsKey("targetName")) {
                result.addProperty("sourceName", (String) data.get("sourceName"));
                result.addProperty("targetName", (String) data.get("targetName"));
                result.addProperty("successRate", ((Number) data.get("successRate")).doubleValue());
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
