package com.mtxgdn.onebot.command.market;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.common.command.RouteDefinition;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.game.entity.PlayerInfo;
import com.google.gson.JsonObject;

import java.util.Map;
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
            ctx.reply("用法: /行情 <物品名或ID> [天数]\n例如: /行情 灵草  或  /行情 灵草 30\n天数默认 7 天，最多 90 天");
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
            ctx.reply("请指定物品名或物品ID");
            return;
        }

        Map<String, Object> quote = ServiceRegistry.getTradeService().getPriceHistory(itemKey, days);
        if (!Boolean.TRUE.equals(quote.get("success"))) {
            ctx.reply(String.valueOf(quote.getOrDefault("message", "查询失败")));
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("===== 【").append(quote.get("itemName")).append("】行情 =====\n");
        sb.append("统计窗口: 近 ").append(quote.get("days")).append(" 天\n");
        long tradeCount = ((Number) quote.get("tradeCount")).longValue();
        if (tradeCount > 0) {
            sb.append("成交笔数: ").append(tradeCount).append("\n");
            sb.append("成交总量: ").append(quote.get("totalQuantity")).append("\n");
            sb.append("均价: ").append(quote.get("avgUnitPrice")).append(" 灵石/个\n");
            sb.append("最低: ").append(quote.get("minUnitPrice")).append(" 灵石/个\n");
            sb.append("最高: ").append(quote.get("maxUnitPrice")).append(" 灵石/个\n");
        } else {
            sb.append("近期无成交记录\n");
        }
        Object lowest = quote.get("lowestActiveUnitPrice");
        if (lowest != null) {
            sb.append("当前在售最低: ").append(lowest).append(" 灵石/个\n");
            sb.append("\n💡 定价建议: 略低于 ").append(lowest).append(" 灵石/个更容易卖出");
        } else if (tradeCount > 0) {
            sb.append("\n💡 定价建议: 参考均价 ").append(quote.get("avgUnitPrice")).append(" 灵石/个");
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
                result.addProperty("message", I18n.t("market.h4011186c"));
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
            Map<String, Object> quote = ServiceRegistry.getTradeService().getPriceHistory(itemKey, days);
            boolean ok = Boolean.TRUE.equals(quote.get("success"));
            result.addProperty("code", ok ? 200 : 400);
            result.addProperty("success", ok);
            for (Map.Entry<String, Object> e : quote.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Number n) result.addProperty(e.getKey(), n);
                else if (v instanceof Boolean b) result.addProperty(e.getKey(), b);
                else result.addProperty(e.getKey(), String.valueOf(v));
            }
        } catch (Exception e) {
            result.addProperty("code", 500);
            result.addProperty("success", false);
            result.addProperty("message", I18n.t("market.hf186bd38", e.getMessage()));
        }
        return result;
    }
}
