package com.mtxgdn.game.worldboss;

import java.util.List;

/**
 * 挑战世界 Boss 的结果。
 *
 * <p>校验未通过时只有 {@code success}/{@code message}；挑战成功时带一整套
 * 战果。字段用可空包装，是为了让 Gson 跳过 null，保持"该分支没有这个字段"
 * 与"字段值为 0"的区别。</p>
 *
 * @param success 是否完成了一次挑战
 * @param message 给玩家看的提示
 * @param bossName Boss 名；未开战时为 null
 * @param damage 本次造成的伤害；未开战时为 null
 * @param playerWon 战斗是否获胜；未开战时为 null
 * @param hpRemaining Boss 剩余血量；未开战时为 null
 * @param maxHp Boss 最大血量；未开战时为 null
 * @param killed 本次是否击杀；未开战时为 null
 * @param stones 获得的灵石；未开战时为 null
 * @param exp 获得的经验；未开战时为 null
 * @param capped 奖励是否被上限截断；未开战时为 null
 * @param battleLog 战斗日志；未开战时为 null
 */
public record WorldBossChallengeResult(
        boolean success,
        String message,
        String bossName,
        Long damage,
        Boolean playerWon,
        Long hpRemaining,
        Long maxHp,
        Boolean killed,
        Long stones,
        Long exp,
        Boolean capped,
        List<String> battleLog) {

    /** 前置校验未通过：只有 success 与 message。 */
    public static WorldBossChallengeResult rejected(String message) {
        return new WorldBossChallengeResult(false, message, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** 挑战完成，带上全部战果。 */
    public static WorldBossChallengeResult fought(
            String message, String bossName, long damage, boolean playerWon,
            long hpRemaining, long maxHp, boolean killed,
            long stones, long exp, boolean capped, List<String> battleLog) {
        return new WorldBossChallengeResult(true, message, bossName, damage, playerWon,
                hpRemaining, maxHp, killed, stones, exp, capped, battleLog);
    }
}
