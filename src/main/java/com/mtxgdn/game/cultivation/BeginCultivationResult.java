package com.mtxgdn.game.cultivation;

/**
 * 开始闭关的结果。
 *
 * <p>成功时带一整套修炼速率上下文（境界之外的季节、地点加成），
 * 三个入口都靠这些字段渲染提示。用可空包装保持"失败时没有这些字段"。</p>
 *
 * @param success 是否开始闭关
 * @param message 给玩家看的提示
 * @param cultivating 是否处于修炼中；失败时为 null
 * @param startTime 闭关起始时间戳；失败时为 null
 * @param cultivationPerSecond 每秒修炼收益；失败时为 null
 * @param season 当前季节名；失败时为 null
 * @param seasonMultiplier 季节加成倍率；失败时为 null
 * @param locationId 所在位置 ID；失败时为 null
 * @param locationName 所在位置名；失败时为 null
 * @param locationMultiplier 地点灵气加成倍率；失败时为 null
 */
public record BeginCultivationResult(
        boolean success,
        String message,
        Boolean cultivating,
        Long startTime,
        Integer cultivationPerSecond,
        String season,
        Double seasonMultiplier,
        Long locationId,
        String locationName,
        Double locationMultiplier) {

    public static BeginCultivationResult rejected(String message) {
        return new BeginCultivationResult(false, message, null, null, null,
                null, null, null, null, null);
    }

    public static BeginCultivationResult started(
            String message, long startTime, int cultivationPerSecond,
            String season, double seasonMultiplier,
            long locationId, String locationName, double locationMultiplier) {
        return new BeginCultivationResult(true, message, true, startTime,
                cultivationPerSecond, season, seasonMultiplier,
                locationId, locationName, locationMultiplier);
    }
}
