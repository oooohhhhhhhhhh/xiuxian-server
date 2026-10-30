package com.mtxgdn.game.gamble;

import java.util.random.RandomGenerator;

/**
 * 金银阁的单局结算。
 *
 * <p><b>本类刻意不做任何 IO</b>：不查余额、不改余额、不写流水。
 * 调用方把"当前余额"与"随机源"传进来，拿到一个 {@link GambleResult} 再决定怎么落库。
 * 这样"余额永不为负""抽水恒为正"这些性质可以直接在测试里断言，
 * 而不需要起数据库、造玩家、跑事务。</p>
 *
 * <p><b>相对参考项目修掉的四类缺陷</b>（逐条对应 {@code xiuxian_dufang}）：</p>
 * <ol>
 *   <li><b>余额不足仍结算</b>：原版在 {@code stone < price_num} 分支只发消息、
 *       漏了 {@code finish()}，于是继续往下掷骰结算 —— 等于给玩家一条无抵押信贷线，
 *       约 50% 概率把余额打成负数。这里下注前就把余额与限额校验做完，
 *       不满足直接返回失败，不存在"继续往下走"的路径。</li>
 *   <li><b>金额无上限</b>：原版 {@code price_num = int(price)} 之后直接结算，
 *       超 int64 的金额在赢时让 sqlite 抛 {@code OverflowError}。
 *       这里所有金额都在 {@link GambleLimits} 的约束内，且结算前先算好
 *       并检查是否溢出。</li>
 *   <li><b>净额记账 → 负余额</b>：原版赢 {@code +S}、输 {@code −S}，本金从不预扣。
 *       这里用保证金模型：先扣本金，赢了返还「本金 + 净赔付」，余额在结构上不可能为负。</li>
 *   <li><b>零抽水</b>：原版六种玩法 EV 恒为 0，赌场既不制造货币也不回收货币，
 *       唯一效果是把玩家资产变成随机游走，长期看所有人破产。
 *       这里赔率表带抽水（大小奇偶 2.50%、猜 4.17%），让它真正成为货币水槽。</li>
 * </ol>
 *
 * <p><b>为什么用 {@link RandomGenerator} 而不是 {@code Math.random()}</b>：
 * 传进来的随机源可以是 {@code SplittableRandom}（快），
 * 也可以是测试里的固定序列（可复现）。参考项目用 {@code random.randint}，
 * 而 Python 的 {@code random} 是 Mersenne Twister —— 同一个进程里
 * 观察到足够多的输出就能预测后续结果，对赌场这种直接换钱的场景并不合适。</p>
 */
public final class GambleSettlement {

    private GambleSettlement() {
    }

    /**
     * 结算一局。
     *
     * @param mode    玩法
     * @param stake   本金
     * @param guess   猜点数时押的数字（1~6）；其它玩法传 0
     * @param balance 下注前余额
     * @param limits  限额
     * @param random  随机源
     * @return 结算结果；被拒绝时 {@code win == false} 且 {@code balanceAfter == balance}
     * @throws IllegalArgumentException 参数本身不合法（玩法为空、猜点数越界、本金非正）
     */
    public static GambleResult settle(
            GambleMode mode,
            long stake,
            int guess,
            long balance,
            GambleLimits limits,
            RandomGenerator random) {
        if (mode == null) {
            throw new IllegalArgumentException("玩法不能为空");
        }
        if (stake <= 0) {
            throw new IllegalArgumentException("本金必须为正数: " + stake);
        }
        if (mode == GambleMode.GUESS && (guess < 1 || guess > 6)) {
            throw new IllegalArgumentException("猜点数必须在 1~6 之间: " + guess);
        }

        // 余额与限额校验：不满足就原样返回，余额一分不动。
        // 原版在这里只发消息不 return，是"负余额"的源头。
        if (stake > balance) {
            return rejected(mode, stake, guess, balance);
        }
        if (stake > limits.maxAllowedStake(balance, 0)) {
            return rejected(mode, stake, guess, balance);
        }

        int roll = random.nextInt(1, 7);
        boolean win = mode.matches(roll, guess);

        // 保证金模型：本金在下注时已离开余额，这里只算"返还多少"。
        long netPayout = 0;
        if (win) {
            netPayout = netPayoutFor(stake, mode);
            if (netPayout < 0) {
                // 赔率 × 本金溢出 long：宁可判为拒绝，也不要写一个负数赔付进库。
                // 单笔上限已经把本金压在 100 万以内，实际走不到这里，
                // 但结算逻辑不该依赖"上游一定守规矩"。
                return rejected(mode, stake, guess, balance);
            }
        }

        long balanceAfter = win
                ? balance - stake + stake + netPayout
                : balance - stake;

        return new GambleResult(mode, stake, roll, guess, win, netPayout, balance, balanceAfter);
    }

    /**
     * 净赔付 = 本金 × 净赔率，向下取整。
     *
     * <p>向下取整而不是四舍五入：赌场这边多留 1 灵石，比少留 1 灵石安全 ——
     * 后者在大额下注上累积起来会悄悄削掉抽水。</p>
     */
    static long netPayoutFor(long stake, GambleMode mode) {
        double payout = stake * mode.netOdds();
        if (payout > Long.MAX_VALUE) {
            return -1;
        }
        return (long) Math.floor(payout);
    }

    /** 被拒绝的一局：余额不变，没掷骰。 */
    private static GambleResult rejected(GambleMode mode, long stake, int guess, long balance) {
        return new GambleResult(mode, stake, 0, guess, false, 0, balance, balance);
    }
}
