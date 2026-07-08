package com.mtxgdn.onebot.command.item;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

public class UnequipCommand extends Command {
    public UnequipCommand() {
        super(new String[]{"卸下", "unequip"}, "卸下指定部位的装备", "/卸下 <部位>", "背包", "game.equipment.equip");
    }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        if (!ctx.checkPermission("game.equipment.equip")) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        String slot = ctx.getArg().trim().toLowerCase();
        if (slot.isEmpty()) { ctx.reply("用法: /卸下 <部位>\n部位: weapon | armor | accessory"); return; }
        var result = ServiceRegistry.getItemService().unequipItem((int) p.getId(), slot);
        String msg = (String) result.getOrDefault("message", "");
        if (msg.isEmpty()) msg = "卸下失败，请检查部位是否正确。";
        ctx.reply(msg);
    }
}
