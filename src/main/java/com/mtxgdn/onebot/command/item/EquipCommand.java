package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class EquipCommand extends Command {
    public EquipCommand() {
        super(new String[]{"装备", "equip"}, "穿戴装备到指定部位", "/装备 <物品key> <部位>", "背包", "game.equipment.equip");
        setModule("inventory");
    }

    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;

        String[] parts = splitArgs(ctx.getArg(), 2);
        if (!CommandUtils.hasEnoughArgs(parts, 2) || CommandUtils.isBlank(parts[0]) || CommandUtils.isBlank(parts[1])) {
            ctx.reply("用法: /装备 <物品名称或key> <部位>\n部位: weapon(武器) | armor(防具) | accessory(饰品)\n物品名称或key 可在 /背包 或 /物品列表 中查看\n例如: /装备 铁剑 weapon");
            return;
        }

        var result = ServiceRegistry.getItemService().equipItem((int) p.getId(), parts[0].trim(), parts[1].trim().toLowerCase());
        String msg = (String) result.getOrDefault("message", "");
        if (msg.isEmpty()) msg = "装备失败，请检查物品key和部位是否正确。";
        String discTip = ServiceRegistry.getGuideService().checkDiscovery((int) p.getId(), p, "equip");
        if (discTip != null) msg += "\n\n" + discTip;
        ctx.reply(msg);
    }
}
