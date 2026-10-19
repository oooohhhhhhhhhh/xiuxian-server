package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class BuyItemCommand extends Command {
    public BuyItemCommand() { super(new String[]{"购买", "buy"}, "购买坊市中的物品", "/购买 <挂单ID>", "坊市", "game.player.info"); 
        setI18nKeys("cmd.buy_item.desc", "cmd.buy_item.usage");
    setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (ctx.getArg().trim().isEmpty()) { ctx.reply(I18n.t("market.h6a23d51c")); return; }
        long listingId;
        try { listingId = Long.parseLong(ctx.getArg().trim()); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.h321fe128")); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().buyItem(p.getId(), listingId);
        ctx.reply((String) result.getOrDefault("message", I18n.t("market.buy_failed")));
    }
}
