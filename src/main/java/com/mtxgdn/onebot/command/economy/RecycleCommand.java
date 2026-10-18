package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class RecycleCommand extends Command {
    public RecycleCommand() {
        super(new String[]{"回收", "recycle"}, "将物品回收为灵石", "/回收 <物品名> [数量]", "经济", null);
        setModule("economy");

        // REST API
        addRoute(RouteDefinition.post("economy/recycle", this::handleRecycleHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String[] parts = ctx.getArg().trim().split("\\s+", 2);
        if (parts[0].isEmpty()) {
            ctx.reply("用法: /回收 <物品名> [数量]\n提示: 回收价为原价的 30%");
            return;
        }

        String itemName = parts[0];
        int quantity = 1;
        if (parts.length > 1) {
            try { quantity = Integer.parseInt(parts[1]); } catch (NumberFormatException e) {
                ctx.reply("数量无效"); return;
            }
        }
        if (quantity <= 0) { ctx.reply("数量必须大于 0"); return; }

        var item = ItemRegistry.resolve(itemName);
        if (item == null) { ctx.reply("物品不存在，请使用 /物品列表 查看可回收物品"); return; }

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.recycleItem(p.getId(), itemName, quantity);
        ctx.reply((String) result.get("message"));
    }

    // ==================== REST API ====================

    private JsonObject handleRecycleHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.he3b69f3c"));
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h893a9a8c"));
                return result;
            }

            var item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("economy.h3c2a9282", itemKey));
                return result;
            }

            var eco = ServiceRegistry.getEconomyService();
            Map<String, Object> data = eco.recycleItem(ctx.playerId(), itemKey, quantity);
            boolean success = Boolean.TRUE.equals(data.get("success"));
            result.addProperty("code", success ? 200 : 400);
            result.addProperty("success", success);
            result.addProperty("message", String.valueOf(data.getOrDefault("message", "")));
            if (data.containsKey("stonesGained")) result.addProperty("stonesGained", ((Number) data.get("stonesGained")).longValue());
            if (data.containsKey("recycled")) result.addProperty("recycled", String.valueOf(data.get("recycled")));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
