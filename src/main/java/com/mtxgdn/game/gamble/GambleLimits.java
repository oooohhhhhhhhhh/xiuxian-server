package com.mtxgdn.game.gamble;

/**
 * 金银阁的下注限额。
 *
 * <p><b>为什么限额必须是结构的一部分，而不是"以后再调"的常量</b>：
 * 参考项目对下注额完全没有上限校验（{@code price_num = int(price)} 之后直接结算），
 * 后果有两个，都不是靠调数值能解决的：</p>
 * <ul>
 *   <li>超 int64 的金额在赢的时候让 sqlite 抛 {@code OverflowError} ——
 *       玩家可以拿 {@code 99999999999999999999} 把 handler 打崩；</li>
 *   <li>不限额的零期望赌局等于给玩家一个"用 10 秒 CD 换一次跨量级资产重估"的按钮。</li>
 * </ul>
 *
 * <p>这里的三条限制各有分工，缺一条都能被绕开：</p>
 * <ul>
 *   <li>{@code maxStake} 挡住单笔过大与溢出；</li>
 *   <li>{@code maxStakeRatio} 挡住"一把梭哈"—— 只限绝对值的话，
 *       富人可以把单笔压到刚好等于上限，仍然是一次定生死；</li>
 *   <li>{@code dailyMaxStake} 挡住"拆成很多小注连赌"，把一天的方差摊平。</li>
 * </ul>
 *
 * @param maxStake      单笔下注上限（灵石）
 * @param maxStakeRatio 单笔下注占当前余额的比例上限，取值 (0, 1]
 * @param dailyMaxStake 每日累计下注上限（灵石）
 */
public record GambleLimits(long maxStake, double maxStakeRatio, long dailyMaxStake) {

    /**
     * 出厂限额。
     *
     * <p>单笔 100 万、每日 2000 万、单笔不超过余额一半。数值本身可以调，
     * 但三条限制的<b>结构</b>不该被删 —— 只留其中一两条都会留下可被利用的缺口。</p>
     */
    public static final GambleLimits DEFAULT = new GambleLimits(1_000_000L, 0.5, 20_000_000L);

    public GambleLimits {
        if (maxStake <= 0) {
            throw new IllegalArgumentException("单笔下注上限必须为正数: " + maxStake);
        }
        if (!(maxStakeRatio > 0 && maxStakeRatio <= 1)) {
            throw new IllegalArgumentException("单笔比例上限必须在 (0,1] 内: " + maxStakeRatio);
        }
        if (dailyMaxStake < maxStake) {
            throw new IllegalArgumentException(
                    "每日上限不得小于单笔上限，否则单笔上限形同虚设: " + dailyMaxStake + " < " + maxStake);
        }
    }

    /**
     * 本笔下注允许的最大金额。
     *
     * <p>取三条限制里最紧的那条：单笔绝对值、余额比例、今日剩余额度。</p>
     *
     * @param balance      当前余额
     * @param usedToday    今日已累计下注额
     * @return 允许的最大下注额；余额或额度不足时为 0
     */
    public long maxAllowedStake(long balance, long usedToday) {
        if (balance <= 0) {
            return 0;
        }
        // 比例上限向下取整：宁可少让赌一点，也不要因为四舍五入多放过 1 灵石
        long byRatio = (long) Math.floor(balance * maxStakeRatio);
        long byDaily = dailyMaxStake - usedToday;
        long limit = Math.min(maxStake, Math.min(byRatio, byDaily));
        return Math.max(0, Math.min(limit, balance));
    }
}
