package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class ListItemCommand extends Command {
    public ListItemCommand() { super(new String[]{"list", "上架"}, "将物品上架坊市", "/上架 <物品key> <数量> <灵石价格>", "世界", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        String[] parts = ctx.getArg().trim().split("\\s+", 3);
        if (parts.length < 3 || parts[0].isEmpty()) {
            ctx.reply("用法: /上架 <物品名称或key> <数量> <灵石价格>\n物品key可在 /背包 或 /物品列表 中查看\n例如: /上架 回血丹 5 100"); return;
        }
        int quantity, price;
        try { quantity = Integer.parseInt(parts[1]); price = Integer.parseInt(parts[2]); }
        catch (NumberFormatException e) { ctx.reply("数量和价格必须是整数"); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().listItem(p.getId(), parts[0], quantity, price);
        ctx.reply((String) result.getOrDefault("message", "上架失败"));
    }
}
