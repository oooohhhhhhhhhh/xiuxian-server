package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.List;

public class BackpackCommand extends Command {
    public BackpackCommand() {
        super(new String[]{"背包", "backpack"}, "查看背包中的物品", "/背包", "背包", "game.inventory.view");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.inventory.view")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var guideService = ServiceRegistry.getGuideService();
        var itemService = ServiceRegistry.getItemService();
        List<ItemService.InventoryEntry> inventory = itemService.getInventory((int) p.getId());
        if (inventory.isEmpty()) {
            ctx.reply("===== 背包 =====\n空空如也...");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("===== 背包 =====\n");
        for (ItemService.InventoryEntry entry : inventory) {
            Item item = entry.getItem();
            sb.append(item.getName()).append(" (").append(item.getFullKey()).append(") x").append(entry.getQuantity()).append("\n");
        }
        ctx.reply(sb.toString());
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "backpack");
        if (discTip != null) ctx.reply(discTip);
    }
}
