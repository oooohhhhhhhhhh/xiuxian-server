package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.i18n.I18n;
public class MarketCommand extends Command {
    public MarketCommand() { super(new String[]{"坊市", "market"}, "查看坊市交易挂单", "/坊市", "坊市", "game.player.info"); 
        setI18nKeys("cmd.market.desc", "cmd.market.usage");
    setModule("trade"); }
    @Override
    public void execute(CommandContext ctx) {
        PlayerInfo p = requirePlayerInfo(ctx);
        if (p == null) return;
        var tradeService = ServiceRegistry.getTradeService();
        var guideService = ServiceRegistry.getGuideService();
        var listings = tradeService.getActiveListings();
        if (listings.isEmpty()) {
            ctx.reply(I18n.t("market.h7e9a27ac")); return;
        }
        StringBuilder sb = new StringBuilder(); sb.append("===== 坊市 =====\n");
        int shown = 0;
        for (var l : listings) {
            if (shown >= 15) { sb.append("\n...(仅显示前15条)"); break; }
            Item item = ItemRegistry.get(l.itemKey);
            String itemName = item != null ? (item.getName() + " (" + l.itemKey + ")") : l.itemKey;
            sb.append("[").append(l.id).append("] ").append(itemName).append(" x").append(l.quantity);
            sb.append("  售价:").append(l.priceSpiritStones).append("灵石");
            // 数量 >1 时补单价，便于横向比较不同挂单
            if (l.quantity > 1) sb.append("(单价 ").append(l.unitPrice()).append(")");
            long remain = l.remainingMs();
            if (remain >= 0) sb.append("  剩").append(formatRemaining(remain));
            sb.append("\n");
            shown++;
        }
        sb.append("\n使用 /行情 <物品名> 查看近期成交均价，辅助定价");
        ctx.reply(sb.toString());
        String discTip = guideService.checkDiscovery((int) p.getId(), p, "market");
        if (discTip != null) ctx.reply(discTip);
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
