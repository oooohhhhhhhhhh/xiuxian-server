package com.mtxgdn.onebot.command.market;

import com.mtxgdn.game.trade.MarketQuote;
import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.google.gson.JsonObject;

import com.mtxgdn.i18n.I18n;

/**
 * 坊市行情：展示某物品的近期成交均价/最低/最高，并在没有成交记录时
 * 给出当前在售最低价作为定价锚点。
 * <p>
 * 此前玩家挂单完全靠猜（没有任何历史参考），这是坊市价格失序的主因之一。
 */
public class MarketPriceCommand extends Command {

    public MarketPriceCommand() {
        super(new String[]{"行情", "price"},
                "查看坊市成交行情，辅助定价",
                "/行情 <物品名或ID> [天数]", "坊市", "game.player.info");
        
        setI18nKeys("cmd.market_price.desc", "cmd.market_price.usage");
    setModule("trade");

        addRoute(RouteDefinition.get("market/price", "game.market.view", this::handleHttp));
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg() == null ? "" : ctx.getArg().trim();
        if (arg.isEmpty()) {
            ctx.reply(I18n.t("market.usage_3"));
            return;
        }

        // 解析可选的末尾天数
        String itemKey = arg;
        int days = 0;
        int lastSpace = arg.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = arg.substring(lastSpace + 1).trim();
            try {
                days = Integer.parseInt(tail);
                itemKey = arg.substring(0, lastSpace).trim();
            } catch (NumberFormatException ignored) {
                // 末尾不是数字 → 整体都是物品名（物品名可能含空格）
            }
        }
        if (itemKey.isEmpty()) {
            ctx.reply(I18n.t("market.please_specify_an_item_name"));
            return;
        }

        MarketQuote quote = ServiceRegistry.getTradeService().getPriceHistory(itemKey, days);
        if (!quote.success()) {
            ctx.reply(quote.message() != null ? quote.message() : I18n.t("market.quote_failed"));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 【").append(quote.itemName()).append("】行情 =====\n");
        sb.append("统计窗口: 近 ").append(quote.days()).append(" 天\n");
        long tradeCount = quote.tradeCount();
        if (tradeCount > 0) {
            sb.append("成交笔数: ").append(tradeCount).append("\n");
            sb.append("成交总量: ").append(quote.totalQuantity()).append("\n");
            sb.append("均价: ").append(quote.avgUnitPrice()).append(" 灵石/个\n");
            sb.append("最低: ").append(quote.minUnitPrice()).append(" 灵石/个\n");
            sb.append("最高: ").append(quote.maxUnitPrice()).append(" 灵石/个\n");
        } else {
            sb.append("近期无成交记录\n");
        }
        Long lowest = quote.lowestActiveUnitPrice();
        if (lowest != null) {
            sb.append("当前在售最低: ").append(lowest).append(" 灵石/个\n");
            sb.append("\n💡 定价建议: 略低于 ").append(lowest).append(" 灵石/个更容易卖出");
        } else if (tradeCount > 0) {
            sb.append("\n💡 定价建议: 参考均价 ").append(quote.avgUnitPrice()).append(" 灵石/个");
        } else {
            sb.append("坊市当前没有该物品在售，你可以自由定价");
        }
        ctx.reply(sb.toString());
    }

    private JsonObject handleHttp(RouteDefinition.RestContext ctx) {
        JsonObject result = new JsonObject();
        try {
            String itemKey = ctx.queryParam("itemKey");
            if (itemKey == null || itemKey.isBlank()) itemKey = ctx.queryParam("item");
            if (itemKey == null || itemKey.isBlank()) {
                result.addProperty("code", 400);
                result.addProperty("success", false);
                result.addProperty("message", I18n.t("market.please_provide_the_itemkey_query"));
                return result;
            }
            int days = 0;
            String d = ctx.queryParam("days");
            if (d != null && !d.isBlank()) {
                try {
                    days = Integer.parseInt(d.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            MarketQuote quote = ServiceRegistry.getTradeService().getPriceHistory(itemKey, days);
            result.addProperty("code", quote.success() ? 200 : 400);
            result.addProperty("success", quote.success());
            result.addProperty("message", quote.message());
            if (quote.itemKey() != null) result.addProperty("itemKey", quote.itemKey());
            if (quote.itemName() != null) result.addProperty("itemName", quote.itemName());
            if (quote.days() != null) result.addProperty("days", quote.days());
            if (quote.tradeCount() != null) result.addProperty("tradeCount", quote.tradeCount());
            if (quote.totalQuantity() != null) result.addProperty("totalQuantity", quote.totalQuantity());
            // 无成交时这三个字段为 null：原实现也是"没有就不写"，不能补 0
            if (quote.minUnitPrice() != null) result.addProperty("minUnitPrice", quote.minUnitPrice());
            if (quote.maxUnitPrice() != null) result.addProperty("maxUnitPrice", quote.maxUnitPrice());
            if (quote.avgUnitPrice() != null) result.addProperty("avgUnitPrice", quote.avgUnitPrice());
            if (quote.lowestActiveUnitPrice() != null) {
                result.addProperty("lowestActiveUnitPrice", quote.lowestActiveUnitPrice());
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("market.server_error", e.getMessage()));
        }
        return result;
    }
}
