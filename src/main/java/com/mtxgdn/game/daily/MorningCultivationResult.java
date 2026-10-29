package com.mtxgdn.game.daily;

/**
 * 晨修结果。
 *
 * <p>校验失败时只有 {@code success}/{@code message}；成功时带完整的收益与
 * 天象信息。用可空字段保住这个区别，避免失败分支被序列化出一堆 0。</p>
 *
 * @param success 是否完成晨修
 * @param message 给玩家看的提示
 * @param expGained 获得的经验；失败时为 null
 * @param spiritStonesGained 获得的灵石；失败时为 null
 * @param consecutiveDays 连续晨修天数；失败时为 null
 * @param phenomenon 天象名；失败时为 null
 * @param phenomenonDesc 天象描述；失败时为 null
 * @param season 当前季节名；失败时为 null
 * @param seasonMultiplier 季节加成倍率；失败时为 null
 */
public record MorningCultivationResult(
        boolean success,
        String message,
        Long expGained,
        Long spiritStonesGained,
        Integer consecutiveDays,
        String phenomenon,
        String phenomenonDesc,
        String season,
        Double seasonMultiplier) {

    public static MorningCultivationResult rejected(String message) {
        return new MorningCultivationResult(false, message, null, null, null,
                null, null, null, null);
    }

    public static MorningCultivationResult done(
            String message, long expGained, long spiritStonesGained, int consecutiveDays,
            String phenomenon, String phenomenonDesc,
            String season, double seasonMultiplier) {
        return new MorningCultivationResult(true, message, expGained, spiritStonesGained,
                consecutiveDays, phenomenon, phenomenonDesc, season, seasonMultiplier);
    }
}
