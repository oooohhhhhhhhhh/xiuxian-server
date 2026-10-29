package com.mtxgdn.game.sect;

/**
 * 仓库捐献的结果。
 *
 * @param success 是否捐献成功
 * @param message 给玩家看的提示
 * @param contributionGain 获得的贡献值；失败时为 null
 */
public record WarehouseDonateResult(
        boolean success,
        String message,
        Long contributionGain) {

    public static WarehouseDonateResult fail(String message) {
        return new WarehouseDonateResult(false, message, null);
    }

    public static WarehouseDonateResult donated(String message, long contributionGain) {
        return new WarehouseDonateResult(true, message, contributionGain);
    }
}
