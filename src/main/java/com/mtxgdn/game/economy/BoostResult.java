package com.mtxgdn.game.economy;

/**
 * 灵石加速修炼的结果。
 *
 * @param success 是否加速成功
 * @param message 给玩家看的提示
 * @param stonesSpent 实际消耗的灵石（按整百对齐后的值）；失败时为 null
 * @param boostHours 加速小时数；失败时为 null
 * @param bonusExp 额外获得的经验；失败时为 null
 */
public record BoostResult(
        boolean success,
        String message,
        Long stonesSpent,
        Integer boostHours,
        Long bonusExp) {

    public static BoostResult rejected(String message) {
        return new BoostResult(false, message, null, null, null);
    }

    public static BoostResult boosted(
            String message, long stonesSpent, int boostHours, long bonusExp) {
        return new BoostResult(true, message, stonesSpent, boostHours, bonusExp);
    }
}
