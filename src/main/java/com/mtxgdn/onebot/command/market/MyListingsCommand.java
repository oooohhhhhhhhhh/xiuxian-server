package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

public class MyListingsCommand extends Command {
    public MyListingsCommand() { super(new String[]{"mylistings", "我的挂单"}, "查看我在坊市的挂单", "/我的挂单", "世界", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        var listings = ServiceRegistry.getTradeService().getPlayerListings(p.getId());
        if (listings.isEmpty()) { ctx.reply("===== 我的挂单 =====\n你还没有在坊市挂单。\n使用 /上架 <key> <数量> <灵石> 上架吧！"); return; }
        StringBuilder sb = new StringBuilder(); sb.append("===== 我的挂单 =====\n");
        for (var l : listings) {
            Item item = ItemRegistry.get(l.itemKey);
            String itemName = item != null ? item.getName() : l.itemKey;
            sb.append("[").append(l.id).append("] ").append(itemName).append(" x").append(l.quantity);
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石  手续费:").append(l.fee).append("灵石\n");
        }
        sb.append("使用 /撤单 <ID> 撤单");
        ctx.reply(sb.toString());
    }
}
