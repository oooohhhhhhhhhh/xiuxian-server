package com.mtxgdn.game.buff;

/**
 * 一条生效中的临时增益（展示用）。
 *
 * <p>与 {@code BuffService.ActiveBuff}（内部持有 {@code expireTime} 的旧式实体）
 * 区分开：这里是已经算好剩余秒数的对外视图，不含绝对过期时刻。</p>
 *
 * @param id 增益标识
 * @param attackBonus 攻击加成
 * @param defenseBonus 防御加成
 * @param speedBonus 速度加成
 * @param spiritBonus 灵力加成
 * @param remainingSeconds 剩余秒数，已过期则为 0
 */
public record ActiveBuffView(
        String id,
        int attackBonus,
        int defenseBonus,
        int speedBonus,
        int spiritBonus,
        long remainingSeconds) {
}
