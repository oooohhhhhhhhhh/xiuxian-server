package com.mtxgdn.game.gamble;

/**
 * 一次下注的结算结果。
 *
 * <p><b>为什么 {@code balanceAfter} 由结算逻辑自己算，而不是让调用方回查</b>：
 * 参考项目的结算顺序是"先判输赢、再 {@code update_ls} 改库"，中间没有任何地方
 * 知道改完之后余额是多少。于是它既无法在写库前做下限保护，
 * 也没法把"这一注之后还剩多少"回给玩家。把余额变化纳入结算结果之后，
 * "余额会不会变负"变成了一个可以在纯函数里断言的性质，而不是要靠数据库约束兜底。</p>
 *
 * <p><b>保证金模型</b>：下注时先从余额扣掉本金，赢了再返还「本金 + 净赔付」。
 * 这与参考项目的"净额记账"（赢 {@code +S}、输 {@code −S}，本金从不预扣）不同 ——
 * 后者的直接后果是余额可以变成负数，而负余额会连锁打崩别的模块
 * （例如"偷灵石"里 {@code randint(-1, -50)} 直接抛 {@code ValueError}）。</p>
 *
 * @param mode         玩法
 * @param stake        本金
 * @param roll         掷出的点数 1~6
 * @param guess        猜点数时押的数字；其它玩法为 0
 * @param win          是否中奖
 * @param netPayout    净赔付（不含本金）；输时为 0
 * @param balanceBefore 下注前余额
 * @param balanceAfter  结算后余额
 */
public record GambleResult(
        GambleMode mode,
        long stake,
        int roll,
        int guess,
        boolean win,
        long netPayout,
        long balanceBefore,
        long balanceAfter) {

    /** 本注对余额的净影响（赢为正、输为负）。 */
    public long balanceDelta() {
        return balanceAfter - balanceBefore;
    }

    /** 押中的点数（仅猜点数模式有意义）。 */
    public boolean wasGuess() {
        return mode == GambleMode.GUESS;
    }
}
