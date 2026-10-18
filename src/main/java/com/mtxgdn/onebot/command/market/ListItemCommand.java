package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;
import com.mtxgdn.i18n.I18n;

public class ListItemCommand extends Command {
    public ListItemCommand() { super(new String[]{"上架", "list"}, "将物品上架坊市", "/上架 <物品key> <数量> <灵石价格>", "坊市", "game.player.info"); setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;
        String[] parts = ctx.getArg().trim().split("\\s+", 3);
        if (parts.length < 3 || parts[0].isEmpty()) {
            ctx.reply(I18n.t("market.h0d78dba4")); return;
        }
        int quantity;
        long price;
        // 价格用 long 解析：服务端上限是 999,999,999，用 int 解析会让超限输入
        // 报出"必须是整数"这种误导性错误，而不是服务端准确的超限提示。
        try { quantity = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.he5b5db70")); return; }
        try { price = Long.parseLong(parts[2]); }
        catch (NumberFormatException e) { ctx.reply(I18n.t("market.h102b9b7c")); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().listItem(p.getId(), parts[0], quantity, price);
        ctx.reply((String) result.getOrDefault("message", I18n.t("market.list_failed")));
    }
}
