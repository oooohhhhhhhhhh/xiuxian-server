package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class EquippedCommand extends Command {
    public EquippedCommand() {
        super(new String[]{"已装备", "equipped"}, "查看当前装备", "/已装备", "背包", "game.inventory.view");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var itemService = ServiceRegistry.getItemService();
        var guideService = ServiceRegistry.getGuideService();
        Map<String, String> equipment = itemService.getEquipment((int) p.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("===== 已装备 =====\n");
        boolean hasAny = false;
        String[] slots = {"weapon", "armor", "accessory"};
        String[] labels = {"武器", "防具", "饰品"};
        for (int i = 0; i < slots.length; i++) {
            String itemKey = equipment.get(slots[i]);
            if (itemKey != null) {
                Item item = ItemRegistry.get(itemKey);
                sb.append(labels[i]).append(": ").append(item != null ? item.getName() : itemKey).append("\n");
                hasAny = true;
            } else { sb.append(labels[i]).append(": (空)\n"); }
        }
        if (!hasAny) sb.append("\n暂无装备");
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "equipped");
        if (discTip != null) sb.append("\n").append(discTip);
        ctx.reply(sb.toString());
    }
}
