package com.mtxgdn.onebot.command.market;

import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class CancelListingCommand extends Command {
    public CancelListingCommand() { super(new String[]{"撤单", "cancellisting"}, "撤回坊市挂单", "/撤单 <挂单ID>", "坊市", "game.player.info"); 
        setI18nKeys("cmd.cancel_listing.desc", "cmd.cancel_listing.usage");
    setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding(); if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId); if (p == null) return;
        if (ctx.getArg().trim().isEmpty()) { ctx.reply(I18n.t("market.usage")); return; }
        long listingId;
        try { listingId = Long.parseLong(ctx.getArg().trim()); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.the_listing_id_must_2")); return; }
        TradeResult result = ServiceRegistry.getTradeService().cancelListing(p.getId(), listingId);
        ctx.reply(result.message() != null ? result.message() : I18n.t("trade.cancel_failed"));
    }
}
