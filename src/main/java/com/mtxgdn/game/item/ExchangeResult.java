package com.mtxgdn.game.item;

/**
 * 灵石兑换的结果。
 *
 * <p>成功时带"换了多少、从哪级到哪级"，前端据此刷新钱包展示；
 * 失败时只有提示。</p>
 *
 * @param success 是否兑换成功
 * @param message 给玩家看的提示
 * @param fromAmount 扣除的源等级灵石数量；失败时为 null
 * @param fromGrade 源等级；失败时为 null
 * @param toAmount 得到的目标等级灵石数量；失败时为 null
 * @param toGrade 目标等级；失败时为 null
 */
public record ExchangeResult(
        boolean success,
        String message,
        Long fromAmount,
        Integer fromGrade,
        Long toAmount,
        Integer toGrade) {

    public static ExchangeResult rejected(String message) {
        return new ExchangeResult(false, message, null, null, null, null);
    }

    public static ExchangeResult exchanged(
            String message, long fromAmount, int fromGrade, long toAmount, int toGrade) {
        return new ExchangeResult(true, message, fromAmount, fromGrade, toAmount, toGrade);
    }
}
