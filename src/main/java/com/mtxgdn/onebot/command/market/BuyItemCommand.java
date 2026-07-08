package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class BuyItemCommand extends Command {
    public BuyItemCommand() { super(new String[]{"购买", "buy"}, "购买坊市中的物品", "/购买 <挂单ID>", "坊市", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (ctx.getArg().trim().isEmpty()) { ctx.reply("用法: /购买 <挂单ID>\n先使用 /坊市 查看坊市中的挂单ID"); return; }
        long listingId;
        try { listingId = Long.parseLong(ctx.getArg().trim()); }
        catch (NumberFormatException e) { ctx.reply("挂单ID必须是数字，请使用 /坊市 查看"); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().buyItem(p.getId(), listingId);
        ctx.reply((String) result.getOrDefault("message", "购买失败"));
    }
}
