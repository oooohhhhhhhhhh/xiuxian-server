package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

public class CancelListingCommand extends Command {
    public CancelListingCommand() { super(new String[]{"撤单", "cancel"}, "撤回坊市挂单", "/撤单 <挂单ID>", "坊市", "game.player.info"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (ctx.getArg().trim().isEmpty()) { ctx.reply("用法: /撤单 <挂单ID>\n使用 /我的挂单 查看你的挂单"); return; }
        long listingId;
        try { listingId = Long.parseLong(ctx.getArg().trim()); }
        catch (NumberFormatException e) { ctx.reply("挂单ID必须是数字"); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().cancelListing(p.getId(), listingId);
        ctx.reply((String) result.getOrDefault("message", "撤单失败"));
    }
}
