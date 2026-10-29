package com.mtxgdn.game.economy;

/**
 * 灵庄取款的结果。
 *
 * @param success 是否取款成功
 * @param message 给玩家看的提示
 * @param principal 本金；失败时为 null
 * @param interest 利息；失败时为 null
 * @param total 本息合计；失败时为 null
 */
public record BankWithdrawResult(
        boolean success,
        String message,
        Long principal,
        Long interest,
        Long total) {

    public static BankWithdrawResult rejected(String message) {
        return new BankWithdrawResult(false, message, null, null, null);
    }

    public static BankWithdrawResult withdrawn(
            String message, long principal, long interest, long total) {
        return new BankWithdrawResult(true, message, principal, interest, total);
    }
}
