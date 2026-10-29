package com.mtxgdn.game.daily;

/**
 * 单条今日机缘的进度。
 *
 * <p>字段名与原先 {@code Map.of("current", ..., "target", ..., "rewarded", ...)}
 * 完全一致，序列化后的 JSON 不变。</p>
 *
 * @param current 已完成次数
 * @param target 目标次数
 * @param rewarded 奖励是否已发放
 */
public record DailyFortune(int current, int target, boolean rewarded) {
}
