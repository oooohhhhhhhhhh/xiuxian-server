package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
public class MarketCommand extends Command {
    public MarketCommand() { super(new String[]{"坊市", "market"}, "查看坊市交易挂单", "/坊市", "坊市", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        var tradeService = ServiceRegistry.getTradeService();
        var guideService = ServiceRegistry.getGuideService();
        var listings = tradeService.getActiveListings();
        if (listings.isEmpty()) {
            ctx.reply("===== 坊市 =====\n坊市上空空荡荡，还没有人挂单。\n使用 /上架 <物品key> <数量> <灵石价格> 来卖东西吧！"); return;
        }
        StringBuilder sb = new StringBuilder(); sb.append("===== 坊市 =====\n");
        int shown = 0;
        for (var l : listings) {
            if (shown >= 15) { sb.append("\n...(仅显示前15条)"); break; }
            Item item = ItemRegistry.get(l.itemKey);
            String itemName = item != null ? (item.getName() + " (" + l.itemKey + ")") : l.itemKey;
            sb.append("[").append(l.id).append("] ").append(itemName).append(" x").append(l.quantity);
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石\n");
            shown++;
        }
        ctx.reply(sb.toString());
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "market");
        if (discTip != null) ctx.reply(discTip);
    }
}
