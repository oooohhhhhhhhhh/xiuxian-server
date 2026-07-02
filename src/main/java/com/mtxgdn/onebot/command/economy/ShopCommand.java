package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class ShopCommand extends Command {
    public ShopCommand() {
        super(new String[]{"商店", "shop", "灵石商店"}, "灵石商店购买物品", "/商店 [编号] — 无编号列出商品，带编号购买", "经济", null);

        addRoute(RouteDefinition.get("economy/shop/items", this::handleShopListHttp));
        addRoute(RouteDefinition.post("economy/shop/buy", this::handleShopBuyHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        var eco = ServiceRegistry.getEconomyService();
        String arg = ctx.getArg().trim();

        if (arg.isEmpty()) {
            // 列出所有商品
            String[][] items = eco.getShopItems();
            long stones = ServiceRegistry.getItemService().getSpiritStoneCount(p.getId());

            StringBuilder sb = new StringBuilder();
            sb.append("🏪 灵石商店\n");
            sb.append("你的灵石: ").append(stones).append("\n\n");

            for (int i = 0; i < items.length; i++) {
                sb.append(i + 1).append(". ")
                  .append(items[i][0]).append(" — ")
                  .append(items[i][2]).append(" 灵石");
                if (i < items.length - 1) sb.append("\n");
            }
            sb.append("\n\n购买: /商店 <编号>");
            ctx.reply(sb.toString());
            return;
        }

        try {
            int index = Integer.parseInt(arg);
            var result = eco.buyFromShop(p.getId(), index);
            ctx.reply((String) result.get("message"));
        } catch (NumberFormatException e) {
            ctx.reply("请输入商品编号，如 /商店 1");
        }
    }

    // ==================== REST API ====================

    private JsonObject handleShopListHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            var eco = ServiceRegistry.getEconomyService();
            String[][] items = eco.getShopItems();
            JsonArray arr = new JsonArray();
            for (int i = 0; i < items.length; i++) {
                JsonObject io = new JsonObject();
                io.addProperty("index", i + 1);
                io.addProperty("name", items[i][0]);
                io.addProperty("itemKey", items[i][1]);
                io.addProperty("price", Integer.parseInt(items[i][2]));
                io.addProperty("description", items[i][3]);
                arr.add(io);
            }
            long stones = ServiceRegistry.getItemService().getSpiritStoneCount(ctx.playerId());
            result.addProperty("code", 200);
            result.addProperty("spiritStones", stones);
            result.add("items", arr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    private JsonObject handleShopBuyHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            int index = req.has("index") ? req.get("index").getAsInt() : 0;
            if (index < 1) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供有效的商品编号(index)");
                return result;
            }
            var eco = ServiceRegistry.getEconomyService();
            var data = eco.buyFromShop(ctx.playerId(), index);
            result.addProperty("code", (boolean) data.get("success") ? 200 : 400);
            result.addProperty("success", (boolean) data.get("success"));
            result.addProperty("message", (String) data.get("message"));
            if (data.containsKey("item")) result.addProperty("item", (String) data.get("item"));
            if (data.containsKey("cost")) result.addProperty("cost", (int) data.get("cost"));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
