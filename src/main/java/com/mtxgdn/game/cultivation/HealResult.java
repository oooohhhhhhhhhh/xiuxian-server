package com.mtxgdn.game.cultivation;

/**
 * 疗伤的结果。
 *
 * <p>注意「血量已满」算成功但不花钱：那种情况下没有 {@code cost} 与
 * {@code hpBefore/hpAfter}，用可空包装保住这个区别。</p>
 *
 * @param success 是否成功（含「血已满」）
 * @param message 给玩家看的提示
 * @param cost 花费灵石；未实际疗伤时为 null
 * @param currentStones 玩家当前灵石；灵石不足时为 null
 * @param hpBefore 疗伤前血量；未实际疗伤时为 null
 * @param hpAfter 疗伤后血量；未实际疗伤时为 null
 */
public record HealResult(
        boolean success,
        String message,
        Long cost,
        Long currentStones,
        Integer hpBefore,
        Integer hpAfter) {

    /** 校验失败：只有 success 与 message。 */
    public static HealResult rejected(String message) {
        return new HealResult(false, message, null, null, null, null);
    }

    /** 灵石不足：额外带回花费与余额，方便前端提示。 */
    public static HealResult notEnoughStones(String message, long cost, long currentStones) {
        return new HealResult(false, message, cost, currentStones, null, null);
    }

    /** 血量已满，不花钱。 */
    public static HealResult alreadyFull(String message) {
        return new HealResult(true, message, null, null, null, null);
    }

    /** 疗伤完成。 */
    public static HealResult healed(String message, long cost, int hpBefore, int hpAfter) {
        return new HealResult(true, message, cost, null, hpBefore, hpAfter);
    }
}
