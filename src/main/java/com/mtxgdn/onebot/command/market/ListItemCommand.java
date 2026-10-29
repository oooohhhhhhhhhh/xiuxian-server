package com.mtxgdn.onebot.command.market;

import com.mtxgdn.game.trade.TradeResult;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;

public class ListItemCommand extends Command {
    public ListItemCommand() { super(new String[]{"上架", "list"}, "将物品上架坊市", "/上架 <物品key> <数量> <灵石价格>", "坊市", "game.player.info"); 
        setI18nKeys("cmd.list_item.desc", "cmd.list_item.usage");
    setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        String[] parts = ctx.getArg().trim().split("\\s+", 3);
        if (parts.length < 3 || parts[0].isEmpty()) {
            ctx.reply(I18n.t("market.usage_2")); return;
        }
        int quantity;
        long price;
        // 价格用 long 解析：服务端上限是 999,999,999，用 int 解析会让超限输入
        // 报出"必须是整数"这种误导性错误，而不是服务端准确的超限提示。
        try { quantity = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.the_amount_must")); return; }
        try { price = Long.parseLong(parts[2]); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.the_price_must")); return; }
        TradeResult result = ServiceRegistry.getTradeService().listItem(p.getId(), parts[0], quantity, price);
        ctx.reply(result.message() != null ? result.message() : I18n.t("market.list_failed"));
    }
}
