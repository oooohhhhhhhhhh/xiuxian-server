package com.mtxgdn.game.economy;

/**
 * 灵庄存款的结果。
 *
 * @param success 是否存入成功
 * @param message 给玩家看的提示
 * @param principal 本金；失败时为 null
 * @param depositType 存款类型 key；失败时为 null
 * @param depositId 存款单号；失败时为 null
 */
public record BankDepositResult(
        boolean success,
        String message,
        Long principal,
        String depositType,
        Long depositId) {

    public static BankDepositResult rejected(String message) {
        return new BankDepositResult(false, message, null, null, null);
    }

    public static BankDepositResult deposited(
            String message, long principal, String depositType, long depositId) {
        return new BankDepositResult(true, message, principal, depositType, depositId);
    }
}
