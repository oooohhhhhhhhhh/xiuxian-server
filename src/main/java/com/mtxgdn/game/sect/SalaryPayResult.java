package com.mtxgdn.game.sect;

/**
 * 手动发放工资的结果。
 *
 * @param success 是否发放成功
 * @param message 给玩家看的提示
 * @param paidCount 实际发放人数；失败时为 null
 * @param paidTotal 实际发放总额；失败时为 null
 * @param scaled 是否因金库不足而按比例缩减；失败时为 null
 */
public record SalaryPayResult(
        boolean success,
        String message,
        Integer paidCount,
        Long paidTotal,
        Boolean scaled) {

    public static SalaryPayResult fail(String message) {
        return new SalaryPayResult(false, message, null, null, null);
    }

    public static SalaryPayResult paid(String message, int paidCount, long paidTotal, boolean scaled) {
        return new SalaryPayResult(true, message, paidCount, paidTotal, scaled);
    }
}
