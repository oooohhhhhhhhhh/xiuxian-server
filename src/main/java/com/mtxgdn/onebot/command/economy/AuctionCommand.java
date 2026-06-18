package com.mtxgdn.onebot.command.economy;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;

/**
 * 竞拍行 — 限时竞价交易。
 * /拍卖 — 查看竞拍列表
 * /拍卖 出售 <物品名> <数量> <起价> [小时=24] — 上架竞拍
 * /拍卖 出价 <编号> <价格> — 出价竞拍
 * /拍卖 我的 — 查看我的拍卖
 */
public class AuctionCommand extends Command {

    public AuctionCommand() {
        super(new String[]{"拍卖", "auction"}, "竞拍行 — 限时竞价交易", "/拍卖 [出售|出价|我的]", "经济", null);

        registerSub("出售", (ctx, p, parts) -> createAuction(ctx, p, parts));
        registerSub("出价", (ctx, p, parts) -> placeBid(ctx, p, parts));
        registerSub("我的", (ctx, p, parts) -> myAuctions(ctx, p));
    }

    @Override
    public void execute(CommandContext ctx) {
        // 有参数时走基类的子命令分发（出售/出价/我的）
        String arg = ctx.getArg();
        if (arg != null && !arg.trim().isEmpty()) {
            super.execute(ctx);
            return;
        }

        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        listActiveAuctions(ctx, p);
    }

    private void listActiveAuctions(CommandContext ctx, PlayerInfo p) {
        var eco = ServiceRegistry.getEconomyService();
        var items = eco.getActiveAuctionItems();

        StringBuilder sb = new StringBuilder();
        sb.append("🔨 竞拍行\n");
        if (items.isEmpty()) {
            sb.append("当前无竞拍商品");
        } else {
            for (var row : items) {
                sb.append("#").append(row.get("id")).append(" ")
                  .append(row.get("itemName")).append(" x").append(row.get("quantity"))
                  .append(" 起价:").append(row.get("startPrice"))
                  .append(" 当前:").append(row.get("currentBid") != null ? row.get("currentBid") : "无人出价")
                  .append(" 剩余:").append(row.get("remaining"))
                  .append("\n");
            }
        }
        long stones = ServiceRegistry.getItemService().getSpiritStoneCount(p.getId());
        sb.append("\n你的灵石: ").append(stones);
        sb.append("\n\n出售: /拍卖 出售 <物品名> <数量> <起价>");
        sb.append("\n出价: /拍卖 出价 <编号> <价格>");
        sb.append("\n我的: /拍卖 我的");
        ctx.reply(sb.toString());
    }

    private void createAuction(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 4) { ctx.reply("用法: /拍卖 出售 <物品名> <数量> <起价> [小时默认24]"); return; }

        String itemName = parts[1];
        int qty;
        long startPrice;
        try {
            qty = Integer.parseInt(parts[2]);
            startPrice = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) { ctx.reply("数量和价格无效"); return; }

        int hours = 24;
        if (parts.length > 4) {
            try { hours = Integer.parseInt(parts[4]); } catch (NumberFormatException ignored) {}
        }

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.createAuction(p.getId(), itemName, qty, startPrice, hours);
        ctx.reply((String) result.get("message"));
    }

    private void placeBid(CommandContext ctx, PlayerInfo p, String[] parts) {
        if (parts.length < 3) { ctx.reply("用法: /拍卖 出价 <编号> <价格>"); return; }

        long listingId;
        long amount;
        try {
            listingId = Long.parseLong(parts[1]);
            amount = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) { ctx.reply("编号和价格无效"); return; }

        var eco = ServiceRegistry.getEconomyService();
        var result = eco.placeBid(p.getId(), listingId, amount);
        ctx.reply((String) result.get("message"));
    }

    private void myAuctions(CommandContext ctx, PlayerInfo p) {
        var eco = ServiceRegistry.getEconomyService();
        var items = eco.getPlayerAuctionItems(p.getId());

        StringBuilder sb = new StringBuilder();
        sb.append("📋 你的竞拍\n");
        if (items.isEmpty()) {
            sb.append("你还没有在竞拍行出售物品");
        } else {
            for (var row : items) {
                sb.append("#").append(row.get("id")).append(" ")
                  .append(row.get("itemName")).append(" x").append(row.get("quantity"))
                  .append(" 当前:").append(row.get("currentBid") != null ? row.get("currentBid") : "无人出价")
                  .append(" 状态:").append(row.get("status"))
                  .append("\n");
            }
        }
        sb.append("\n注意: 竞拍结束后物品自动发放给出价最高者");
        ctx.reply(sb.toString());
    }
}
