package com.mtxgdn.onebot.command.admin;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.CommandUtils;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.permission.PermissionService;
import com.mtxgdn.onebot.QqBinding;
import com.mtxgdn.onebot.QqBindingService;

public class GiveCommand extends Command {
    public GiveCommand() {
        super(new String[]{"发放", "give"},
                "给玩家发放金币/灵力/灵石/物品（仅私聊，按玩家ID）",
                "/发放 <玩家ID> <金币|灵力|灵石>=<数量> [物品key]=<数量> ...",
                "管理", "admin.items.give", true);
    }

    @Override
    public void execute(CommandContext ctx) {
        QqBinding b = ctx.resolveBoundBinding();
        if (b == null) {
            ctx.reply("请先绑定账号。");
            return;
        }
        if (!PermissionService.hasPermission(b.getUserId(), "admin.items.give")) {
            ctx.reply("权限不足，你无权使用此功能。");
            return;
        }

        String arg = ctx.getArg();
        if (CommandUtils.isBlank(arg)) {
            ctx.reply("用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量>\n示例: /发放 1 金币=1000 灵石=500 SpiritStone=50");
            return;
        }

        String[] parts = CommandUtils.split(arg);
        if (!CommandUtils.hasEnoughArgs(parts, 2)) {
            ctx.reply("参数不足。用法: /发放 <玩家ID> 金币=<数量> 灵力=<数量> 灵石=<数量> [物品key]=<数量>");
            return;
        }

        Long playerId = CommandUtils.parseLong(parts[0], null);
        if (playerId == null) {
            ctx.reply("玩家ID必须为数字。");
            return;
        }

        PlayerService playerService = ServiceRegistry.getPlayerService();
        ItemService itemService = ServiceRegistry.getItemService();
        var p = playerService.getPlayerById(playerId);
        if (p == null) {
            ctx.reply("未找到玩家 ID:" + playerId);
            return;
        }

        StringBuilder result = new StringBuilder();
        result.append("发放给 ").append(p.getName()).append(" (ID:").append(playerId).append("):\n");
        boolean hasAny = false;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eqIdx = part.indexOf('=');
            if (eqIdx <= 0) continue;
            String key = part.substring(0, eqIdx);
            String valStr = part.substring(eqIdx + 1);
            Long value = CommandUtils.parseLong(valStr, null);
            if (value == null) continue;

            switch (key) {
                case "金币":
                case "gold":
                    playerService.addGold(playerId, value);
                    result.append("  金币 ").append(value > 0 ? "+" : "").append(value).append("\n");
                    hasAny = true;
                    break;
                case "灵力":
                case "exp":
                    playerService.addExperience(playerId, value);
                    result.append("  灵力 ").append(value > 0 ? "+" : "").append(value).append("\n");
                    hasAny = true;
                    break;
                case "灵石":
                case "spiritStones":
                case "spirit_stones":
                    if (value > 0) itemService.addSpiritStones(playerId, value);
                    else if (value < 0) itemService.removeSpiritStones(playerId, -value);
                    result.append("  灵石 ").append(value > 0 ? "+" : "").append(value).append("\n");
                    hasAny = true;
                    break;
                default:
                    Item item = ItemRegistry.get(key);
                    int qty = (int) Math.min(value, Integer.MAX_VALUE);
                    if (qty > 0) {
                        itemService.addItem(playerId, key, qty);
                        result.append("  ").append(item != null ? item.getName() : key).append(" x").append(qty).append("\n");
                        hasAny = true;
                    }
                    break;
            }
        }

        if (!hasAny) {
            result.append("  没有可发放的内容。");
        }
        ctx.reply(result.toString());
    }
}
