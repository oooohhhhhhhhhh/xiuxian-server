package com.mtxgdn.onebot.command.market;

import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class BuyItemCommand extends Command {
    public BuyItemCommand() { super(new String[]{"购买", "buy"}, "购买坊市中的物品", "/购买 <挂单ID>", "坊市", "game.player.info"); 
        setI18nKeys("cmd.buy_item.desc", "cmd.buy_item.usage");
    setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (ctx.getArg().trim().isEmpty()) { ctx.reply(I18n.t("market.usage_4")); return; }
        long listingId;
        try { listingId = Long.parseLong(ctx.getArg().trim()); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.the_listing_id_must")); return; }
        TradeResult result = ServiceRegistry.getTradeService().buyItem(p.getId(), listingId);
        ctx.reply(result.message() != null ? result.message() : I18n.t("market.buy_failed"));
    }
}
