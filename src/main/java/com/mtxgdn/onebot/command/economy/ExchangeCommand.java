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
        
        setI18nKeys("cmd.exchange.desc", "cmd.exchange.usage");
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
            ctx.reply(I18n.t("economy.the_energy_conversion_system_2"));
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
                ctx.reply(I18n.t("economy.quantity_must_be_a_whole"));
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply(I18n.t("economy.quantity_must_be_greater_than"));
            return;
        }

        doConvertItemToEnergy(ctx, p, itemInput, quantity);
    }

    private void doConvertItemToEnergy(CommandContext ctx, PlayerInfo p, String itemInput, int quantity) {
        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply(I18n.t("economy.item_not_found", itemInput));
            return;
        }

        String fullKey = item.getFullKey();
        if (!itemService.hasItem(p.getId(), fullKey, quantity)) {
            long have = itemService.getItemCount(p.getId(), fullKey);
            ctx.reply(I18n.t("economy.not_enough_in_your_backpack_2", item.getName(), quantity, have));
            return;
        }

        long energyPerItem = EnergyService.resolveConvertGain(fullKey);
        if (energyPerItem <= 0) {
            ctx.reply(I18n.t("economy.has_no_conversion_value", item.getName()));
            return;
        }

        long totalEnergy = energyPerItem * quantity;

        itemService.removeItem(p.getId(), fullKey, quantity);
        energyService.addEnergy(p.getId(), totalEnergy);

        ctx.reply(I18n.t("economy.conversion_successful", item.getName(), quantity, totalEnergy, item.getName(), energyPerItem, energyService.getEnergy(p.getId())));
    }

    private void doConvertEnergyToItem(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.the_energy_conversion_system_2"));
            return;
        }
        if (parts.length < 2) {
            ctx.reply(I18n.t("economy.usage_8"));
            return;
        }

        String itemInput = parts[1];
        int quantity = 1;
        if (parts.length >= 3) {
            try {
                quantity = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                ctx.reply(I18n.t("economy.quantity_must_be_a_whole"));
                return;
            }
        }
        if (quantity <= 0) {
            ctx.reply(I18n.t("economy.quantity_must_be_greater_than"));
            return;
        }

        ItemService itemService = ServiceRegistry.getItemService();
        EnergyService energyService = ServiceRegistry.getEnergyService();

        Item item = ItemRegistry.resolve(itemInput);
        if (item == null) {
            ctx.reply(I18n.t("economy.item_not_found_4", itemInput));
            return;
        }

        long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
        if (energyPerItem <= 0) {
            ctx.reply(I18n.t("economy.has_no_conversion_value_2", item.getName()));
            return;
        }

        long totalCost = energyPerItem * quantity;
        long currentEnergy = energyService.getEnergy(p.getId());

        if (currentEnergy < totalCost) {
            ctx.reply(I18n.t("economy.not_enough_energy_2", totalCost, currentEnergy));
            return;
        }

        if (!energyService.removeEnergy(p.getId(), totalCost)) {
            ctx.reply(I18n.t("economy.failed_to_deduct_energy"));
            return;
        }

        itemService.addItem(p.getId(), item.getFullKey(), quantity);

        ctx.reply(I18n.t("economy.exchange_successful_3", totalCost, item.getName(), quantity, item.getName(), energyPerItem, energyService.getEnergy(p.getId())));
    }

    private void doList(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.the_energy_conversion_system_2"));
            return;
        }
        var items = ItemRegistry.getAll();
        if (items.isEmpty()) {
            ctx.reply(I18n.t("economy.there_are_no_convertible_items"));
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
            ctx.reply(I18n.t("economy.there_are_no_convertible_items"));
            return;
        }

        sb.append("\n使用 /转化 <物品> [数量] 将物品转化为能量\n");
        sb.append("使用 /转化 兑换 <物品> [数量] 用能量兑换物品");
        ctx.reply(sb.toString());
    }

    private void doHelp(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (!BuiltInPluginRegistry.isModuleEnabled(ENERGY_EXCHANGE_PLUGIN)) {
            ctx.reply(I18n.t("economy.the_energy_conversion_system_2"));
            return;
        }
        EnergyService energyService = ServiceRegistry.getEnergyService();
        long energy = energyService.getEnergy(p.getId());

        ctx.reply(I18n.t("economy.energy_conversion_system_ncurrent_energy", energy));
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
                result.addProperty("message", I18n.t("economy.the_energy_conversion_system"));
                return result;
            }

            JsonObject req = ctx.bodyJson();
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.please_provide_an_item_key"));
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.quantity_must_be_greater_than_3"));
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("economy.item_not_found_2", itemKey));
                return result;
            }

            String fullKey = item.getFullKey();
            if (!itemService.hasItem(playerId, fullKey, quantity)) {
                long have = itemService.getItemCount(playerId, fullKey);
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.not_enough_items", quantity, have));
                return result;
            }

            long energyPerItem = EnergyService.resolveConvertGain(fullKey);
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.this_item_has_no_conversion"));
                return result;
            }

            long totalEnergy = energyPerItem * quantity;
            itemService.removeItem(playerId, fullKey, quantity);
            energyService.addEnergy(playerId, totalEnergy);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("economy.conversion_successful_2"));
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", fullKey);
            result.addProperty("quantity", quantity);
            result.addProperty("energyGained", totalEnergy);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("economy.the_energy_conversion_system"));
                return result;
            }

            JsonObject req = ctx.bodyJson();
            String itemKey = req.has("itemKey") ? req.get("itemKey").getAsString() : "";
            int quantity = req.has("quantity") ? req.get("quantity").getAsInt() : 1;

            if (itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.please_provide_an_item_key"));
                return result;
            }
            if (quantity <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.quantity_must_be_greater_than_3"));
                return result;
            }

            int playerId = ctx.playerId();
            ItemService itemService = ServiceRegistry.getItemService();
            EnergyService energyService = ServiceRegistry.getEnergyService();

            Item item = ItemRegistry.resolve(itemKey);
            if (item == null) {
                result.addProperty("code", 404);
                result.addProperty("message", I18n.t("economy.item_not_found_2", itemKey));
                return result;
            }

            long energyPerItem = EnergyService.resolveEnergyValue(item.getFullKey());
            if (energyPerItem <= 0) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.this_item_has_no_conversion_2"));
                return result;
            }

            long totalCost = energyPerItem * quantity;
            long currentEnergy = energyService.getEnergy(playerId);

            if (currentEnergy < totalCost) {
                result.addProperty("code", 400);
                result.addProperty("message", I18n.t("economy.not_enough_energy", totalCost, currentEnergy));
                return result;
            }

            if (!energyService.removeEnergy(playerId, totalCost)) {
                result.addProperty("code", 500);
                result.addProperty("message", I18n.t("economy.failed_to_deduct_energy_2"));
                return result;
            }

            itemService.addItem(playerId, item.getFullKey(), quantity);

            result.addProperty("code", 200);
            result.addProperty("message", I18n.t("economy.exchange_successful"));
            result.addProperty("itemName", item.getName());
            result.addProperty("itemKey", item.getFullKey());
            result.addProperty("quantity", quantity);
            result.addProperty("energyCost", totalCost);
            result.addProperty("currentEnergy", energyService.getEnergy(playerId));
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("economy.the_energy_conversion_system"));
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
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
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
                result.addProperty("message", I18n.t("economy.the_energy_conversion_system"));
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
            result.addProperty("message", I18n.t("economy.server_error", e.getMessage()));
        }
        return result;
    }
}
