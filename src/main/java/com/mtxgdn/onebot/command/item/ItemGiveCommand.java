package com.mtxgdn.onebot.command.item;

import com.google.gson.JsonObject;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import java.util.List;

public class ItemGiveCommand extends Command {

    public ItemGiveCommand() {
        super(new String[]{"发放", "give", "giveitem", "give_item"},
                "[管理] 向指定玩家发放物品",
                "/发放 <玩家名> <物品Key> [数量]",
                "管理",
                "admin.items.give");

        addRoute(RouteDefinition.post("admin/items/give", "admin.items.give", this::handleGiveHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("admin.items.give")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg().trim();
        if (arg.isEmpty()) {
            ctx.reply("用法: /发放 <玩家名> <物品Key> [数量=1]\n例: /发放 李白 spirit_stone 100");
            return;
        }

        String[] parts = arg.split("\\s+", 3);
        if (parts.length < 2) {
            ctx.reply("参数不足。用法: /发放 <玩家名> <物品Key> [数量]");
            return;
        }

        String targetName = parts[0];
        String itemKey = parts[1];
        int quantity = 1;
        if (parts.length >= 3) {
            try {
                quantity = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                ctx.reply("数量无效");
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply("数量必须大于 0");
            return;
        }

        // 查找目标玩家
        var playerService = ServiceRegistry.getPlayerService();
        List<PlayerInfo> found = playerService.searchPlayersByName(targetName, 1, 0);
        if (found.isEmpty()) {
            ctx.reply("未找到玩家: " + targetName);
            return;
        }
        PlayerInfo target = found.get(0);

        // 校验物品
        Item item = ItemRegistry.resolve(itemKey);
        if (item == null) {
            ctx.reply("物品不存在: " + itemKey + "\n使用 /物品列表 查看可发放的物品");
            return;
        }

        // 发放
        var itemService = ServiceRegistry.getItemService();
        itemService.addItem(target.getId(), item.getFullKey(), quantity);

        ctx.reply("已向 " + target.getName() + " 发放 " + item.getName() + " x" + quantity);
    }

    // ==================== REST API ====================

    private JsonObject handleGiveHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            JsonObject req = ctx.bodyJson();
            String targetName = req.has("playerName") ? req.get("playerName").getAsString() : "";
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (targetName.isBlank() || itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供玩家名(playerName)和物品键(itemKey)");
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "数量必须大于0");
                return result;
            }

            var playerService = ServiceRegistry.getPlayerService();
            PlayerInfo target;
            List<PlayerInfo> found = playerService.searchPlayersByName(targetName, 1, 0);
            if (found.isEmpty()) {
                result.addProperty("code", 404);
                result.addProperty("message", "未找到玩家: " + targetName);
                return result;
            }
            target = found.get(0);

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "物品不存在: " + itemKey);
                return result;
            }

            var itemService = ServiceRegistry.getItemService();
            itemService.addItem(target.getId(), item.getFullKey(), quantity);

            result.addProperty("code", 200);
            result.addProperty("message", "成功向 " + targetName + " 发放 " + item.getName() + " x" + quantity);
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", item.getFullKey());
            result.addProperty("quantity", quantity);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
