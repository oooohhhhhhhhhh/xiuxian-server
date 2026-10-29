package com.mtxgdn.game.trade;

/**
 * 坊市行情查询结果。
 *
 * <p>无成交记录时没有 {@code minUnitPrice}/{@code maxUnitPrice}/{@code avgUnitPrice}，
 * 无在售挂单时没有 {@code lowestActiveUnitPrice}；用可空字段保住这两个区别，
 * 避免前端把「没有数据」当成「价格是 0」。</p>
 *
 * @param success 查询是否成功
 * @param message 给玩家看的提示
 * @param itemKey 物品全名；物品不存在时为 null
 * @param itemName 物品显示名；物品不存在时为 null
 * @param days 实际统计窗口天数；物品不存在时为 null
 * @param tradeCount 成交笔数；物品不存在时为 null
 * @param totalQuantity 成交总量；物品不存在时为 null
 * @param minUnitPrice 最低单价；无成交时为 null
 * @param maxUnitPrice 最高单价；无成交时为 null
 * @param avgUnitPrice 平均单价；无成交时为 null
 * @param lowestActiveUnitPrice 当前在售最低单价；无在售时为 null
 */
public record MarketQuote(
        boolean success,
        String message,
        String itemKey,
        String itemName,
        Integer days,
        Long tradeCount,
        Long totalQuantity,
        Long minUnitPrice,
        Long maxUnitPrice,
        Long avgUnitPrice,
        Long lowestActiveUnitPrice) {

    /** 物品不存在。 */
    public static MarketQuote notFound(String message) {
        return new MarketQuote(false, message, null, null, null, null, null,
                null, null, null, null);
    }

    /**
     * 查询成功。
     *
     * @param count 成交笔数；为 0 时 min/max/avg 必须传 null
     * @param min 最低单价；无成交传 null
     * @param max 最高单价；无成交传 null
     * @param avg 平均单价；无成交传 null
     * @param lowest 当前在售最低单价；无在售传 null
     */
    public static MarketQuote of(String message, String itemKey, String itemName,
                                 int days, long count, long totalQuantity,
                                 Long min, Long max, Long avg, Long lowest) {
        return new MarketQuote(true, message, itemKey, itemName, days, count,
                totalQuantity, min, max, avg, lowest);
    }
}
