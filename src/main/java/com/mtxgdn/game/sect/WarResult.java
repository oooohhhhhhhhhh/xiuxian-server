package com.mtxgdn.game.sect;

/**
 * 宣战的结果。
 *
 * <p>宣战本身会立刻打一场，所以成功时带战报与双方胜场 —— 调用方据此直接
 * 展示结果，不必再查一次。</p>
 *
 * @param success 是否完成宣战（含战斗结算）
 * @param message 给玩家看的提示
 * @param battleLog 战报文本；失败时为 null
 * @param attackerWins 进攻方胜场数；失败时为 null
 * @param defenderWins 防守方胜场数；失败时为 null
 * @param winner 获胜宗门名；失败时为 null
 */
public record WarResult(
        boolean success,
        String message,
        String battleLog,
        Integer attackerWins,
        Integer defenderWins,
        String winner) {

    public static WarResult fail(String message) {
        return new WarResult(false, message, null, null, null, null);
    }

    public static WarResult fought(String message, String battleLog,
                                   int attackerWins, int defenderWins, String winner) {
        return new WarResult(true, message, battleLog, attackerWins, defenderWins, winner);
    }
}
