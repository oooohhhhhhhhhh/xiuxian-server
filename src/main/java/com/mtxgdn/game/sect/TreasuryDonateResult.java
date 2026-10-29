package com.mtxgdn.game.sect;

/**
 * 向宗门金库捐献灵石的结果。
 *
 * @param success 是否捐献成功
 * @param message 给玩家看的提示
 * @param treasury 捐献后的金库余额；失败时为 null
 * @param contribution 获得的贡献值；失败时为 null
 */
public record TreasuryDonateResult(
        boolean success,
        String message,
        Long treasury,
        Long contribution) {

    public static TreasuryDonateResult fail(String message) {
        return new TreasuryDonateResult(false, message, null, null);
    }

    public static TreasuryDonateResult donated(String message, long treasury, long contribution) {
        return new TreasuryDonateResult(true, message, treasury, contribution);
    }
}
