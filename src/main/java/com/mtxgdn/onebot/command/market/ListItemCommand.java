package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.common.service.ServiceRegistry;
import java.util.Map;

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
            ctx.reply("用法: /上架 <物品名称或key> <数量> <灵石价格>\n物品key可在 /背包 或 /物品列表 中查看\n例如: /上架 回血丹 5 100"); return;
        }
        int quantity;
        long price;
        // 价格用 long 解析：服务端上限是 999,999,999，用 int 解析会让超限输入
        // 报出"必须是整数"这种误导性错误，而不是服务端准确的超限提示。
        try { quantity = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) { ctx.reply("数量必须是整数"); return; }
        try { price = Long.parseLong(parts[2]); }
        catch (NumberFormatException e) { ctx.reply("价格必须是整数（单位：灵石）"); return; }
        Map<String, Object> result = ServiceRegistry.getTradeService().listItem(p.getId(), parts[0], quantity, price);
        ctx.reply((String) result.getOrDefault("message", "上架失败"));
    }
}
