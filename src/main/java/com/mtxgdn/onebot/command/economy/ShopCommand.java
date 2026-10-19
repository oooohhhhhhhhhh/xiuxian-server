package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

/**
 * 灵石商店：/商店 浏览商品，/商店 <编号> 购买。
 * 商店是灵石的主要消耗渠道之一，此前仅有 EconomyService 实现而无任何入口，此处补齐 QQ + REST 双端。
 */
public class ShopCommand extends Command {
    public ShopCommand() {
        super(new String[]{"商店", "shop"}, "浏览灵石商店并购买商品", "/商店 [编号]", "经济", null);
        
        setI18nKeys("cmd.shop.desc", "cmd.shop.usage");
    setModule("economy");

        addRoute(RouteDefinition.get("economy/shop", this::handleShopListHttp));
        addRoute(RouteDefinition.post("economy/shop/buy", this::handleShopBuyHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var eco = ServiceRegistry.getEconomyService();
        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(buildShopList());
            return;
        }

        try {
            int index = Integer.parseInt(arg.trim());
            var result = eco.buyFromShop(p.getId(), index);
            StringBuilder sb = new StringBuilder();
            if ((boolean) result.get("success")) {
                sb.append("✅ ").append(result.get("message"));
            } else {
                sb.append("❌ ").append(result.get("message"));
            }
            ctx.reply(sb.toString());
        } catch (NumberFormatException e) {
            ctx.reply(I18n.t("economy.h2ce09654"));
        }
    }

    private String buildShopList() {
        var eco = ServiceRegistry.getEconomyService();
        String[][] items = eco.getShopItems();
        StringBuilder sb = new StringBuilder("🏪 灵石商店\n");
        sb.append("（灵石是修仙界硬通货，回复 /商店 <编号> 购买）\n\n");
        for (int i = 0; i < items.length; i++) {
            sb.append(i + 1).append(". ").append(items[i][0]).append("\n");
            sb.append("   售价: ").append(items[i][2]).append(" 灵石 ｜ ").append(items[i][3]).append("\n");
        }
        return sb.toString();
    }

    // ==================== REST API ====================

    private JsonObject handleShopListHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        var eco = ServiceRegistry.getEconomyService();
        String[][] items = eco.getShopItems();
        JsonArray list = new JsonArray();
        for (int i = 0; i < items.length; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("id", i + 1);
            o.addProperty("name", items[i][0]);
            o.addProperty("price", Long.parseLong(items[i][2]));
            o.addProperty("description", items[i][3]);
            list.add(o);
        }
        result.addProperty("code", 200);
        result.addProperty("success", true);
        result.add("items", list);
        return result;
    }

    private JsonObject handleShopBuyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject body = com.google.gson.JsonParser.parseString(ctx.body()).getAsJsonObject();
            if (!body.has("id")) {
                result.addProperty("code", 400);
                result.addProperty("success", false);
                result.addProperty("message", I18n.t("economy.hb69140ac"));
                return result;
            }
            int index = body.get("id").getAsInt();
            var eco = ServiceRegistry.getEconomyService();
            var data = eco.buyFromShop(ctx.playerId(), index);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("economy.h429191c6"));
        }
        return result;
    }
}
