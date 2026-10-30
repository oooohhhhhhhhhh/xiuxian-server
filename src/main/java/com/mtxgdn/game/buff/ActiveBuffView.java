package com.mtxgdn.game.buff;

/**
 * 一条生效中的临时增益（展示用）。
 *
 * <p>与 DAO 层的 {@code BuffDAO.ActiveBuffRow}（直接映射 {@code player_buffs} 一行、
 * 持有绝对过期时刻 {@code expire_time}）区分开：这里是已经算好剩余秒数的对外视图，
 * 不含绝对过期时刻 —— 客户端只关心"还能持续多久"，把绝对时刻暴露出去
 * 会让时区/时钟偏差变成展示问题。</p>
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
