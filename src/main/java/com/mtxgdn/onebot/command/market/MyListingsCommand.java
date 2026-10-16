package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

public class MyListingsCommand extends Command {
    public MyListingsCommand() { super(new String[]{"我的挂单", "mylistings"}, "查看我在坊市的挂单", "/我的挂单", "坊市", "game.player.info"); setModule("trade"); }
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
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石  手续费:").append(l.fee).append("灵石");
            // 剩余时间：让卖家知道挂单什么时候会自动下架（物品退回背包）
            long remain = l.remainingMs();
            if (remain >= 0) sb.append("  剩").append(formatRemaining(remain));
            else sb.append("  永不过期");
            sb.append("\n");
        }
        sb.append("使用 /撤单 <ID> 撤单");
        sb.append("\n挂单到期后将自动下架，物品退回背包（手续费不退还）");
        ctx.reply(sb.toString());
    }

    /** 剩余毫秒 → 简短可读文本。 */
    private static String formatRemaining(long ms) {
        if (ms <= 0) return "已到期";
        long totalMinutes = ms / 60000;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) return days + "天" + hours + "时";
        if (hours > 0) return hours + "时" + minutes + "分";
        return Math.max(1, minutes) + "分";
    }
}
