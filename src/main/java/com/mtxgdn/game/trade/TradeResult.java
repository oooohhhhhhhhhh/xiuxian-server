package com.mtxgdn.game.trade;

/**
 * 坊市操作（上架 / 购买 / 撤回）的结果。
 *
 * <p>三个操作共用同一个形状，但携带的附加字段不同：上架带手续费与有效时长，
 * 购买带成交价与手续费，撤回两者都不带。用可空字段保住这个区别，
 * 让序列化结果与原 Map 逐字一致。</p>
 *
 * @param success 操作是否成功
 * @param message 给玩家看的提示
 * @param fee 手续费；撤回时为 null
 * @param expiresInHours 挂单有效小时数；仅上架时有值
 * @param cost 成交总价；仅购买成功时有值
 */
public record TradeResult(
        boolean success,
        String message,
        Long fee,
        Integer expiresInHours,
        Long cost) {

    /** 失败：只有 success 与 message。 */
    public static TradeResult failed(String message) {
        return new TradeResult(false, message, null, null, null);
    }

    /** 上架成功。 */
    public static TradeResult listed(String message, long fee, int expiresInHours) {
        return new TradeResult(true, message, fee, expiresInHours, null);
    }

    /** 购买成功。 */
    public static TradeResult bought(String message, long cost, long fee) {
        return new TradeResult(true, message, fee, null, cost);
    }

    /** 撤回成功。 */
    public static TradeResult cancelled(String message) {
        return new TradeResult(true, message, null, null, null);
    }
}
