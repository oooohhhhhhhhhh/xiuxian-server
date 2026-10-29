package com.mtxgdn.game.farm;

import java.util.Map;

/**
 * 农田操作的结果。
 *
 * <p>十来个操作共用同一形状，但附加字段各不相同：种植带成熟时间戳，单块收获
 * 带产量，批量收获带总数与明细。用可空字段保住这个区别 —— 失败分支不会
 * 被序列化出一堆默认值。</p>
 *
 * @param success 是否操作成功
 * @param message 给玩家看的提示
 * @param harvestTime 成熟时间戳（毫秒）；仅种植成功时有值
 * @param yield 本次产量；仅单块收获成功时有值
 * @param harvestedCount 本次收获的地块数；仅批量收获成功时有值
 * @param details 作物 key → 数量；仅批量收获成功时有值
 */
public record FarmResult(
        boolean success,
        String message,
        Long harvestTime,
        Integer yield,
        Integer harvestedCount,
        Map<String, Integer> details) {

    /** 校验失败或无事可做。 */
    public static FarmResult rejected(String message) {
        return new FarmResult(false, message, null, null, null, null);
    }

    /** 只有提示的成功（浇水、施肥、清理、杀虫、扩地、批量浇水/施肥）。 */
    public static FarmResult ok(String message) {
        return new FarmResult(true, message, null, null, null, null);
    }

    /** 种植成功，带成熟时间。 */
    public static FarmResult planted(String message, long harvestTime) {
        return new FarmResult(true, message, harvestTime, null, null, null);
    }

    /** 单块收获成功，带产量。 */
    public static FarmResult harvested(String message, int yield) {
        return new FarmResult(true, message, null, yield, null, null);
    }

    /** 批量收获成功，带总数与明细。 */
    public static FarmResult harvestedAll(
            String message, int harvestedCount, Map<String, Integer> details) {
        return new FarmResult(true, message, null, null, harvestedCount, details);
    }
}
