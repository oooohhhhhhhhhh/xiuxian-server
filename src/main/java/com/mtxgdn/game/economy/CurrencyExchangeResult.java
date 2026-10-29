package com.mtxgdn.game.economy;

/**
 * 金币/灵石互换的结果。
 *
 * <p>两个方向共用同一形状，但字段互斥：换灵石时带 {@code goldSpent}，
 * 换金币时带 {@code stonesSpent}。用可空字段保住这个区别。</p>
 *
 * @param success 是否兑换成功
 * @param message 给玩家看的提示
 * @param goldSpent 花掉的金币；仅金币换灵石时有值
 * @param stonesSpent 花掉的灵石；仅灵石换金币时有值
 * @param stonesGained 得到的灵石；仅金币换灵石时有值
 * @param goldGained 得到的金币；仅灵石换金币时有值
 */
public record CurrencyExchangeResult(
        boolean success,
        String message,
        Long goldSpent,
        Long stonesSpent,
        Long stonesGained,
        Long goldGained) {

    public static CurrencyExchangeResult rejected(String message) {
        return new CurrencyExchangeResult(false, message, null, null, null, null);
    }

    public static CurrencyExchangeResult goldToStones(
            String message, long goldSpent, long stonesGained) {
        return new CurrencyExchangeResult(true, message, goldSpent, null, stonesGained, null);
    }

    public static CurrencyExchangeResult stonesToGold(
            String message, long stonesSpent, long goldGained) {
        return new CurrencyExchangeResult(true, message, null, stonesSpent, null, goldGained);
    }
}
