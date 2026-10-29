package com.mtxgdn.game.sect;

/**
 * 宗主挑战（竞争期）操作的结果。
 *
 * <p>三个动作共用：挑战者发起 / 宗主查看状态 → 只有提示；
 * 宗主应战 → 带对战结果。用可空字段保住这个区别。</p>
 *
 * @param success 是否操作成功
 * @param message 给玩家看的提示
 * @param challengerWins 挑战者是否获胜；仅应战结算后有值
 * @param winnerId 胜者玩家 ID；仅应战结算后有值
 * @param winnerName 胜者名字；仅应战结算后有值
 * @param battleLog 战报文本；仅应战结算后有值
 */
public record ContestResult(
        boolean success,
        String message,
        Boolean challengerWins,
        Long winnerId,
        String winnerName,
        String battleLog) {

    public static ContestResult ok(String message) {
        return new ContestResult(true, message, null, null, null, null);
    }

    public static ContestResult fail(String message) {
        return new ContestResult(false, message, null, null, null, null);
    }

    public static ContestResult resolved(String message, boolean challengerWins,
                                         long winnerId, String winnerName, String battleLog) {
        return new ContestResult(true, message, challengerWins, winnerId, winnerName, battleLog);
    }
}
