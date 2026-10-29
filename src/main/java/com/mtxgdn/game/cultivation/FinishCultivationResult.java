package com.mtxgdn.game.cultivation;

/**
 * 结束闭关的结果。
 *
 * @param success 是否结束闭关
 * @param message 给玩家看的提示
 * @param elapsedSeconds 本次闭关秒数；失败时为 null
 * @param cultivationPerSecond 每秒修炼收益；失败时为 null
 * @param rawExpGained 结算获得的原始经验（未含加成）；失败时为 null
 * @param season 当前季节名；失败时为 null
 * @param seasonMultiplier 季节加成倍率；失败时为 null
 * @param locationId 所在位置 ID；失败时为 null
 * @param locationName 所在位置名；失败时为 null
 * @param locationMultiplier 地点灵气加成倍率；失败时为 null
 */
public record FinishCultivationResult(
        boolean success,
        String message,
        Long elapsedSeconds,
        Integer cultivationPerSecond,
        Long rawExpGained,
        String season,
        Double seasonMultiplier,
        Long locationId,
        String locationName,
        Double locationMultiplier) {

    public static FinishCultivationResult rejected(String message) {
        return new FinishCultivationResult(false, message, null, null, null,
                null, null, null, null, null);
    }

    public static FinishCultivationResult finished(
            String message, long elapsedSeconds, int cultivationPerSecond, long rawExpGained,
            String season, double seasonMultiplier,
            long locationId, String locationName, double locationMultiplier) {
        return new FinishCultivationResult(true, message, elapsedSeconds,
                cultivationPerSecond, rawExpGained, season, seasonMultiplier,
                locationId, locationName, locationMultiplier);
    }
}
