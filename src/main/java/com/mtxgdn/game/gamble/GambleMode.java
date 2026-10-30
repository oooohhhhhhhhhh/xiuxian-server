package com.mtxgdn.game.gamble;

/**
 * 金银阁的玩法与赔率表。
 *
 * <p><b>为什么赔率要显式写在这里，而不是散落在结算分支里</b>：
 * 参考项目（{@code xiuxian_dufang}）把六种结果写成六个 {@code if/elif} 分支，
 * 每个分支各写一次 {@code update_ls(+price)} 或 {@code update_ls(+price*5)}。
 * 这种写法下"赔率"不是一个可被检查的数值，而是一段重复代码 ——
 * 想核对庄家优势只能人肉读六个分支，想调赔率要改六处，
 * 漏改一处就是白送钱。把它收敛成"枚举 + 赔率表"之后，
 * 期望值可以在测试里直接算出来。</p>
 *
 * <p><b>赔率是"净赔付倍率"</b>（不含本金）：押 100 猜大猜中，净得 95，
 * 加上退还的本金共回 195。这与参考项目的 {@code +price}（净得 100）不同 ——
 * 后者抽水为 0，详见 {@link #houseEdge()}。</p>
 */
public enum GambleMode {

    /** 大：4/5/6。 */
    BIG("大", 0.95, 3),
    /** 小：1/2/3。 */
    SMALL("小", 0.95, 3),
    /** 奇：1/3/5。 */
    ODD("奇", 0.95, 3),
    /** 偶：2/4/6。 */
    EVEN("偶", 0.95, 3),
    /** 猜点数：押中唯一一个数字。 */
    GUESS("猜", 4.75, 1);

    private final String label;
    private final double netOdds;
    /** 六个点数里有多少个算中奖。 */
    private final int winningFaces;

    GambleMode(String label, double netOdds, int winningFaces) {
        this.label = label;
        this.netOdds = netOdds;
        this.winningFaces = winningFaces;
    }

    public String label() {
        return label;
    }

    /** 中奖时的净赔付倍率（不含退还的本金）。 */
    public double netOdds() {
        return netOdds;
    }

    /** 中奖概率，分母恒为 6。 */
    public double winProbability() {
        return winningFaces / 6.0;
    }

    /**
     * 庄家优势 = 期望亏损 / 单注。
     *
     * <p>单注期望收益为 {@code P(win) × netOdds − P(lose)}，
     * 取负号再除以 1 就是庄家优势。大小奇偶约 2.50%，猜约 4.17%。</p>
     */
    public double houseEdge() {
        return -(winProbability() * netOdds - (1 - winProbability()));
    }

    /**
     * 掷出 {@code roll} 时是否中奖。
     *
     * @param roll  1~6
     * @param guess 仅 {@link #GUESS} 用到；其余模式忽略
     */
    public boolean matches(int roll, int guess) {
        return switch (this) {
            case BIG -> roll >= 4;
            case SMALL -> roll <= 3;
            case ODD -> roll % 2 == 1;
            case EVEN -> roll % 2 == 0;
            case GUESS -> roll == guess;
        };
    }

    /**
     * 按标签解析玩法。
     *
     * <p>刻意用 {@code equals} 而不是正则字符类：参考项目的
     * {@code ([大|小|奇|偶|猜])} 是<b>字符类</b>，{@code |} 被当成普通字符，
     * 于是 {@code 金银阁10|} 也能匹配、随后落入"输"分支白扣一次灵石。
     * 这里不接受 {@code |}，从结构上消掉这个缺陷。</p>
     *
     * @return 匹配的玩法；不认识的标签返回 {@code null}
     */
    public static GambleMode fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (GambleMode mode : values()) {
            if (mode.label.equals(label)) {
                return mode;
            }
        }
        return null;
    }
}
