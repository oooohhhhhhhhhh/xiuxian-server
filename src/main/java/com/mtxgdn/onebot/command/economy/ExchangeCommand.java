package com.mtxgdn.onebot.command.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mtxgdn.plugin.BuiltInPluginRegistry;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.EnergyService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.i18n.I18n;

public class ExchangeCommand extends Command {

    /** 实验性插件名：能量转化/等价交换（开关见 config/plugins.yml 的 plugins.experimental_energy_exchange）。 */
    private static final String ENERGY_EXCHANGE_PLUGIN = "experimental_energy_exchange";

    public ExchangeCommand() {
        super(new String[]{"转化", "exchange", "convert"},
                "将物品转化为能量，或将能量兑换为物品",
                "/转化 <物品> [数量]\n/转化 兑换 <物品> [数量]\n/转化 列表",
                "实用功能", null);
        setModule("energy");
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
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.h6ac429d0"));
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
                ctx.reply(I18n.t("economy.h076e926e"));
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply(I18n.t("economy.h5afa12b2"));
            return;
        }

        doConvertItemToEnergy(ctx, p, itemInput, quantity);
    }

    private void doConvertItemToEnergy(CommandContext ctx, PlayerInfo p, String itemInput, int quantity) {
        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply(I18n.t("economy.h2fd99a58", itemInput));
            return;
        }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(p.getId(), fullKey, quantity)) {
            long have = itemService.getItemCount(p.getId(), fullKey);
            ctx.reply(I18n.t("economy.h9bf61f18", item.getName(), quantity, have));
            return;
        }

        long energyPerItem = EnergyService.resolveConvertGain(fullKey);
        if (energyPerItem <= 0) {
            ctx.reply(I18n.t("economy.h5837e388", item.getName()));
            return;
        }

        long totalEnergy = energyPerItem * quantity;

        itemService.removeItem(p.getId(), fullKey, quantity);
        energyService.addEnergy(p.getId(), totalEnergy);

        ctx.reply(I18n.t("economy.h598550a0", item.getName(), quantity, totalEnergy, item.getName(), energyPerItem, energyService.getEnergy(p.getId())));
    }

    private void doConvertEnergyToItem(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.h6ac429d0"));
            return;
        }
        if (parts.length < 2) {
            ctx.reply(I18n.t("economy.hf8f3e890"));
            return;
        }

        String itemInput = parts[1];
        int quantity = 1;
        if (parts.length >= 3) {
            try {
                quantity = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                ctx.reply(I18n.t("economy.h076e926e"));
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply(I18n.t("economy.h5afa12b2"));
            return;
        }

        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply(I18n.t("economy.h75ee4eb8", itemInput));
            return;
        }

        long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
        if (energyPerItem <= 0) {
            ctx.reply(I18n.t("economy.h8bf2c41e", item.getName()));
            return;
        }

        long totalCost = energyPerItem * quantity;
        long currentEnergy = energyService.getEnergy(p.getId());

        if (currentEnergy < totalCost) {
            ctx.reply(I18n.t("economy.hdb421330", totalCost, currentEnergy));
            return;
        }

        if (!energyService.removeEnergy(p.getId(), totalCost)) {
            ctx.reply(I18n.t("economy.h64014920"));
            return;
        }

        itemService.addItem(p.getId(), item.getFullKey(), quantity);

        ctx.reply(I18n.t("economy.hbefa0def", totalCost, item.getName(), quantity, item.getName(), energyPerItem, energyService.getEnergy(p.getId())));
    }

    private void doList(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.h6ac429d0"));
            return;
        }
        var items = ItemRegistry.getAll();
        if (items.isEmpty()) {
            ctx.reply(I18n.t("economy.he88d4f66"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("可转化的物品列表（按价值排序）:\n");
        boolean hasItems = false;

        items.stream()
                .filter(item -> EnergyService.resolveConvertGain(item.getFullKey()) > 0)
                .sorted((a, b) -> Long.compare(EnergyService.resolveConvertGain(b.getFullKey()), EnergyService.resolveConvertGain(a.getFullKey())))
                .forEach(item -> {
                    long value = EnergyService.resolveConvertGain(item.getFullKey());
                    sb.append("  ")
                            .append(item.getName())
                            .append(" (").append(item.getFullKey()).append(")")
                            .append(" - 转化获得: ").append(value).append(" 能量\n");
                });
        hasItems = !sb.toString().equals("可转化的物品列表（按价值排序）:\n");

        if (!hasItems) {
            ctx.reply(I18n.t("economy.he88d4f66"));
            return;
        }

        sb.append("\n使用 /转化 <物品> [数量] 将物品转化为能量\n");
        sb.append("使用 /转化 兑换 <物品> [数量] 用能量兑换物品");
        ctx.reply(sb.toString());
    }

    private void doHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.h6ac429d0"));
            return;
        }
        EnergyService energyService = ServiceRegistry.getEnergyService();
        long energy = energyService.getEnergy(p.getId());

        ctx.reply(I18n.t("economy.hc9dbf890", energy));
    }

    // ==================== REST API 处理器 ====================

    /**
     * POST /game/energy/convert
     * Body: { "itemKey": "mtxgdn:spirit_stone", "quantity": 10 }
     */
    private JsonObject handleConvertHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
                result.addProperty("code", 403);
                result.addProperty("message", I18n.t("economy.h4aa0a0f2"));
                return result;
            }

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
                result.addProperty("message", I18n.t("economy.hc0d8a9c4"));
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("economy.h3c2a9282", itemKey));
                return result;
            }

            String fullKey = item.getFullKey();
            if (!itemService.hasItem(playerId, fullKey, quantity)) {
                long have = itemService.getItemCount(playerId, fullKey);
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h366e16c0", quantity, have));
                return result;
            }

            long energyPerItem = EnergyService.resolveConvertGain(fullKey);
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h3a017ec4"));
                return result;
            }

            long totalEnergy = energyPerItem * quantity;
            itemService.removeItem(playerId, fullKey, quantity);
            energyService.addEnergy(playerId, totalEnergy);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("economy.h863a8dc5"));
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", fullKey);
            result.addProperty("quantity", quantity);
            result.addProperty("energyGained", totalEnergy);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
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
            if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
                result.addProperty("code", 403);
                result.addProperty("message", I18n.t("economy.h4aa0a0f2"));
                return result;
            }

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
                result.addProperty("message", I18n.t("economy.hc0d8a9c4"));
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("economy.h3c2a9282", itemKey));
                return result;
            }

            long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h99e42a98"));
                return result;
            }

            long totalCost = energyPerItem * quantity;
            long currentEnergy = energyService.getEnergy(playerId);

            if (currentEnergy < totalCost) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.h22ad3990", totalCost, currentEnergy));
                return result;
            }

            if (!energyService.removeEnergy(playerId, totalCost)) {
                result.addProperty("code", 500);
                result.addProperty("message", I18n.t("economy.h66110c74"));
                return result;
            }

            itemService.addItem(playerId, item.getFullKey(), quantity);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("economy.h88ec9794"));
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", item.getFullKey());
            result.addProperty("quantity", quantity);
            result.addProperty("energyCost", totalCost);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
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
            if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
                result.addProperty("code", 403);
                result.addProperty("message", I18n.t("economy.h4aa0a0f2"));
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
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
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
            if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
                result.addProperty("code", 403);
                result.addProperty("message", I18n.t("economy.h4aa0a0f2"));
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
            result.addProperty("message", I18n.t("economy.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
