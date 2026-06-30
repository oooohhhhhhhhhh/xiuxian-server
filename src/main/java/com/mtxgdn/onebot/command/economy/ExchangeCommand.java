package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.common.ExperimentalConfig;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.EnergyService;
import com.mtxgdn.game.service.ItemService;

public class ExchangeCommand extends Command {
    public ExchangeCommand() {
        super(new String[]{"转化", "exchange", "convert"},
                "将物品转化为能量，或将能量兑换为物品",
                "/转化 <物品> [数量]\n/转化 兑换 <物品> [数量]\n/转化 列表",
                "实用功能", null);
        registerSub("兑换", this::doConvertEnergyToItem);
        registerSub("列表", this::doList);
        registerSub(new String[]{"help", "帮助"}, this::doHelp);

        // REST API 路由
        addRoute(RouteDefinition.post("energy/convert", this::handleConvertHttp));
        addRoute(RouteDefinition.post("energy/exchange", this::handleExchangeHttp));
        addRoute(RouteDefinition.get("energy/list", this::handleListHttp));
        addRoute(RouteDefinition.get("energy/status", this::handleStatusHttp));
    }

    @Override
    protected void onDefault(CommandContext ctx, PlayerInfo p) {
        if (!ExperimentalConfig.isEnabled("energy_exchange")) {
            ctx.reply("能量转化系统暂未开放。");
            return;
        }
        // 默认行为：物品转化为能量
        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            doHelp(ctx, p, new String[]{});
            return;
        }
        String[] parts = arg.trim().split("\\s+");
        if (parts.length == 0) {
            doHelp(ctx, p, new String[]{});
            return;
        }

        String itemInput = parts[0];
        int quantity = 1;
        if (parts.length >= 2) {
            try {
                quantity = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                ctx.reply("数量必须为整数。");
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply("数量必须大于0。");
            return;
        }

        doConvertItemToEnergy(ctx, p, itemInput, quantity);
    }

    private void doConvertItemToEnergy(CommandContext ctx, PlayerInfo p, String itemInput, int quantity) {
        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply("物品不存在: " + itemInput + "\n使用 /转化 列表 查看可转化的物品");
            return;
        }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(p.getId(), fullKey, quantity)) {
            long have = itemService.getItemCount(p.getId(), fullKey);
            ctx.reply("背包中【" + item.getName() + "】数量不足，需要 " + quantity + " 个，当前拥有 " + have + " 个。");
            return;
        }

        long energyPerItem = EnergyService.resolveEnergyValue(fullKey);
        if (energyPerItem <= 0) {
            ctx.reply("【" + item.getName() + "】没有转化价值，无法转化为能量。");
            return;
        }

        long totalEnergy = energyPerItem * quantity;

        itemService.removeItem(p.getId(), fullKey, quantity);
        energyService.addEnergy(p.getId(), totalEnergy);

        ctx.reply("转化成功！\n" +
                "消耗: " + item.getName() + " x" + quantity + "\n" +
                "获得能量: " + totalEnergy + "\n" +
                "（每个 " + item.getName() + " = " + energyPerItem + " 能量）\n" +
                "当前能量: " + energyService.getEnergy(p.getId()));
    }

    private void doConvertEnergyToItem(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!ExperimentalConfig.isEnabled("energy_exchange")) {
            ctx.reply("能量转化系统暂未开放。");
            return;
        }
        if (parts.length < 2) {
            ctx.reply("用法: /转化 兑换 <物品> [数量]\n示例: /转化 兑换 mtxgdn:spirit_stone 10");
            return;
        }

        String itemInput = parts[1];
        int quantity = 1;
        if (parts.length >= 3) {
            try {
                quantity = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                ctx.reply("数量必须为整数。");
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply("数量必须大于0。");
            return;
        }

        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply("物品不存在: " + itemInput + "\n使用 /转化 列表 查看可兑换的物品");
            return;
        }

        long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
        if (energyPerItem <= 0) {
            ctx.reply("【" + item.getName() + "】没有转化价值，无法用能量兑换。");
            return;
        }

        long totalCost = energyPerItem * quantity;
        long currentEnergy = energyService.getEnergy(p.getId());

        if (currentEnergy < totalCost) {
            ctx.reply("能量不足！需要 " + totalCost + " 能量，当前只有 " + currentEnergy + " 能量。");
            return;
        }

        if (!energyService.removeEnergy(p.getId(), totalCost)) {
            ctx.reply("能量扣除失败，请稍后重试。");
            return;
        }

        itemService.addItem(p.getId(), item.getFullKey(), quantity);

        ctx.reply("兑换成功！\n" +
                "消耗能量: " + totalCost + "\n" +
                "获得: " + item.getName() + " x" + quantity + "\n" +
                "（每个 " + item.getName() + " = " + energyPerItem + " 能量）\n" +
                "剩余能量: " + energyService.getEnergy(p.getId()));
    }

    private void doList(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!ExperimentalConfig.isEnabled("energy_exchange")) {
            ctx.reply("能量转化系统暂未开放。");
            return;
        }
        var items = ItemRegistry.getAll();
        if (items.isEmpty()) {
            ctx.reply("当前没有可转化的物品。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("可转化的物品列表（按价值排序）:\n");

        items.stream()
                .filter(item -> EnergyService.resolveEnergyValue(item.getFullKey()) > 0)
                .sorted((a, b) -> Long.compare(EnergyService.resolveEnergyValue(b.getFullKey()), EnergyService.resolveEnergyValue(a.getFullKey())))
                .forEach(item -> {
                    long value = EnergyService.resolveEnergyValue(item.getFullKey());
                    sb.append("  ")
                            .append(item.getName())
                            .append(" (").append(item.getFullKey()).append(")")
                            .append(" - 价值: ").append(value).append(" 能量\n");
                });

        if (sb.length() == 0 || sb.indexOf("价值:") == -1) {
            ctx.reply("当前没有可转化的物品。");
            return;
        }

        sb.append("\n使用 /转化 <物品> [数量] 将物品转化为能量\n");
        sb.append("使用 /转化 兑换 <物品> [数量] 用能量兑换物品");
        ctx.reply(sb.toString());
    }

    private void doHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!ExperimentalConfig.isEnabled("energy_exchange")) {
            ctx.reply("能量转化系统暂未开放。");
            return;
        }
        EnergyService energyService = ServiceRegistry.getEnergyService();
        long energy = energyService.getEnergy(p.getId());

        ctx.reply("=== 能量转化系统 ===\n" +
                "当前能量: " + energy + "\n\n" +
                "用法:\n" +
                "/转化 <物品> [数量] - 将物品转化为能量\n" +
                "/转化 兑换 <物品> [数量] - 用能量兑换物品\n" +
                "/转化 列表 - 查看所有可转化物品\n\n" +
                "物品转化价值 = 物品原价");
    }

    // ==================== REST API 处理器 ====================

    /**
     * POST /game/energy/convert
     * Body: { "itemKey": "mtxgdn:spirit_stone", "quantity": 10 }
     */
    private JsonObject handleConvertHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            if (!ExperimentalConfig.isEnabled("energy_exchange")) {
                result.addProperty("code", 403);
                result.addProperty("message", "能量转化系统暂未开放");
                return result;
            }

            JsonObject req = ctx.bodyJson();
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供物品键(itemKey)");
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "数量必须大于0");
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "物品不存在: " + itemKey);
                return result;
            }

            String fullKey = item.getFullKey();
            if (!itemService.hasItem(playerId, fullKey, quantity)) {
                long have = itemService.getItemCount(playerId, fullKey);
                result.addProperty("code", 400);
                result.addProperty("message", "物品数量不足，需要 " + quantity + " 个，拥有 " + have + " 个");
                return result;
            }

            long energyPerItem = EnergyService.resolveEnergyValue(fullKey);
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "该物品没有转化价值");
                return result;
            }

            long totalEnergy = energyPerItem * quantity;
            itemService.removeItem(playerId, fullKey, quantity);
            energyService.addEnergy(playerId, totalEnergy);

            result.addProperty("code", 200);
            result.addProperty("message", "转化成功");
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", fullKey);
            result.addProperty("quantity", quantity);
            result.addProperty("energyGained", totalEnergy);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * POST /game/energy/exchange
     * Body: { "itemKey": "mtxgdn:spirit_stone", "quantity": 10 }
     */
    private JsonObject handleExchangeHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            if (!ExperimentalConfig.isEnabled("energy_exchange")) {
                result.addProperty("code", 403);
                result.addProperty("message", "能量转化系统暂未开放");
                return result;
            }

            JsonObject req = ctx.bodyJson();
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", "请提供物品键(itemKey)");
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "数量必须大于0");
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", "物品不存在: " + itemKey);
                return result;
            }

            long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", "该物品没有转化价值，无法兑换");
                return result;
            }

            long totalCost = energyPerItem * quantity;
            long currentEnergy = energyService.getEnergy(playerId);

            if (currentEnergy < totalCost) {
                result.addProperty("code", 400);
                result.addProperty("message", "能量不足，需要 " + totalCost + "，当前 " + currentEnergy);
                return result;
            }

            if (!energyService.removeEnergy(playerId, totalCost)) {
                result.addProperty("code", 500);
                result.addProperty("message", "能量扣除失败");
                return result;
            }

            itemService.addItem(playerId, item.getFullKey(), quantity);

            result.addProperty("code", 200);
            result.addProperty("message", "兑换成功");
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", item.getFullKey());
            result.addProperty("quantity", quantity);
            result.addProperty("energyCost", totalCost);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * GET /game/energy/list
     * 返回可转化物品列表
     */
    private JsonObject handleListHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            if (!ExperimentalConfig.isEnabled("energy_exchange")) {
                result.addProperty("code", 403);
                result.addProperty("message", "能量转化系统暂未开放");
                return result;
            }

            JsonArray itemsArr = new JsonArray();
            ItemRegistry.getAll().stream()
                    .filter(item -> EnergyService.resolveEnergyValue(item.getFullKey()) > 0)
                    .sorted((a, b) -> Long.compare(EnergyService.resolveEnergyValue(b.getFullKey()), EnergyService.resolveEnergyValue(a.getFullKey())))
                    .forEach(item -> {
                        JsonObject io = new JsonObject();
                        io.addProperty("itemKey", item.getFullKey());
                        io.addProperty("itemName", item.getName());
                        io.addProperty("energyValue", EnergyService.resolveEnergyValue(item.getFullKey()));
                        itemsArr.add(io);
                    });

            result.addProperty("code", 200);
            result.add("items", itemsArr);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }

    /**
     * GET /game/energy/status
     * 查询当前玩家的能量值
     */
    private JsonObject handleStatusHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            if (!ExperimentalConfig.isEnabled("energy_exchange")) {
                result.addProperty("code", 403);
                result.addProperty("message", "能量转化系统暂未开放");
                return result;
            }

            int playerId = ctx.playerId();
            EnergyService energyService = ServiceRegistry.getEnergyService();
            long energy = energyService.getEnergy(playerId);

            result.addProperty("code", 200);
            result.addProperty("playerId", playerId);
            result.addProperty("energy", energy);
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", "服务器错误: " + e.getMessage());
        }
        return result;
    }
}
