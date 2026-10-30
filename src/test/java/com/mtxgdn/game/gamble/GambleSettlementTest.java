package com.mtxgdn.game.gamble;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 金银阁结算的纯领域测试 —— 不连库、不启服务、不经过任何通道。
 *
 * <p>这个模块<b>刻意没有接入任何入口</b>（没有 REST 端点、没有 WS 动作、
 * 没有 QQ 指令、没有配置开关）。它只是一份可被测试的领域实现，
 * 用来验证"如果要做金银阁，应该怎么做"。</p>
 *
 * <p>本文件同时充当"参考项目缺陷清单"的可执行版本：每一条缺陷都对应一个用例，
 * 断言的是<b>结构性质</b>而不是具体数值 —— 换掉赔率表或限额数值，这些断言仍然成立。</p>
 */
class GambleSettlementTest {

    private static final GambleLimits LIMITS = GambleLimits.DEFAULT;

    /** 固定掷出某个点数的随机源，用来消除结算逻辑里的随机性。 */
    private static RandomGenerator alwaysRoll(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value - 1L;
            }

            @Override
            public int nextInt(int origin, int bound) {
                // GambleSettlement 调用 nextInt(1, 7)，这里直接给固定值
                return value;
            }
        };
    }

    @Nested
    @DisplayName("赔率与抽水")
    class Odds {

        @Test
        @DisplayName("每种玩法的庄家优势都为正 —— 赌场必须真正回收货币")
        void everyModeHasPositiveHouseEdge() {
            for (GambleMode mode : GambleMode.values()) {
                assertTrue(mode.houseEdge() > 0,
                        mode.label() + " 的庄家优势必须为正，否则赌场既不制造也不回收货币，"
                                + "唯一效果是把玩家资产变成随机游走。实际: " + mode.houseEdge());
            }
        }

        @Test
        @DisplayName("大小奇偶抽水约 2.5%，猜点数约 4.17%（真实赌场量级）")
        void houseEdgeIsInRealisticRange() {
            for (GambleMode mode : GambleMode.values()) {
                double edge = mode.houseEdge();
                assertTrue(edge >= 0.02 && edge <= 0.05,
                        mode.label() + " 抽水应在 2%~5%（真实赌场 2.7%~5.3% 量级），实际: " + edge);
            }
            // 猜点数抽水更高：单注方差大，理应定价更贵
            assertTrue(GambleMode.GUESS.houseEdge() > GambleMode.BIG.houseEdge(),
                    "猜点数的抽水应高于大小奇偶");
        }

        @Test
        @DisplayName("中奖概率分母恒为 6，猜点数恰好 1/6")
        void winProbabilityMatchesSixFaces() {
            assertEquals(0.5, GambleMode.BIG.winProbability(), 1e-12);
            assertEquals(0.5, GambleMode.SMALL.winProbability(), 1e-12);
            assertEquals(0.5, GambleMode.ODD.winProbability(), 1e-12);
            assertEquals(0.5, GambleMode.EVEN.winProbability(), 1e-12);
            assertEquals(1.0 / 6, GambleMode.GUESS.winProbability(), 1e-12);
        }

        @Test
        @DisplayName("六种点数上的判定与赔率表一致（穷举 1~6）")
        void matchingIsConsistentAcrossAllFaces() {
            int[] bigWins = {0};
            for (int roll = 1; roll <= 6; roll++) {
                if (GambleMode.BIG.matches(roll, 0)) bigWins[0]++;
                assertEquals(roll <= 3, GambleMode.SMALL.matches(roll, 0), "小: " + roll);
                assertEquals(roll % 2 == 1, GambleMode.ODD.matches(roll, 0), "奇: " + roll);
                assertEquals(roll % 2 == 0, GambleMode.EVEN.matches(roll, 0), "偶: " + roll);
                assertEquals(roll == 4, GambleMode.GUESS.matches(roll, 4), "猜4: " + roll);
            }
            assertEquals(3, bigWins[0], "大应当在 1~6 里恰好中 3 个点数");
        }
    }

    @Nested
    @DisplayName("余额永不为负（修参考项目的净额记账）")
    class NeverNegative {

        @Test
        @DisplayName("连续下注直到赌不下去，余额一次都不会变成负数")
        void balanceNeverGoesNegativeUnderRepeatedLosses() {
            long balance = 1000;
            long stake = 100;
            // 固定掷出 1：押大必输
            RandomGenerator loseAlways = alwaysRoll(1);

            int rounds = 0;
            while (balance >= stake && rounds < 100) {
                GambleResult result = GambleSettlement.settle(
                        GambleMode.BIG, stake, 0, balance, LIMITS, loseAlways);
                balance = result.balanceAfter();
                assertTrue(balance >= 0,
                        "第 " + rounds + " 局之后余额变成负数: " + balance
                                + "。参考项目正是因为净额记账才出现负余额，"
                                + "而负余额会让其它模块的 randint 直接抛异常");
                rounds++;
            }

            // 不会归零：单笔不得超过余额一半，所以余额降到 100 时再押 100 会被拒，
            // 循环就停在这里。这正是比例限额在起作用 —— 想继续赌必须把注码减半。
            assertEquals(100, balance,
                    "每局必输、每注固定 100 时，余额降到 100 就赌不动了"
                            + "（100 超过 100×0.5 的比例上限）。实际: " + balance);
            assertTrue(rounds > 0, "至少应当真的赌过几局");

            // 把注码减半就能继续赌下去，说明拦住它的是比例限额而不是余额不足
            GambleResult smaller = GambleSettlement.settle(
                    GambleMode.BIG, 50, 0, balance, LIMITS, loseAlways);
            assertEquals(50, smaller.balanceAfter(), "注码减半后应当能继续下注");
        }

        @Test
        @DisplayName("余额不足时直接拒绝，不掷骰、不动余额")
        void insufficientBalanceIsRejectedWithoutRolling() {
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 100, 0, 50, LIMITS, alwaysRoll(6));

            assertFalse(result.win(), "余额不足不该判赢");
            assertEquals(0, result.roll(), "被拒绝时不该掷骰（roll 为 0）");
            assertEquals(50, result.balanceAfter(), "被拒绝时余额必须原样不动");
            assertEquals(0, result.balanceDelta(), "被拒绝时净影响必须为 0");
        }

        @Test
        @DisplayName("余额恰好等于本金时可以下注，输了归零不为负")
        void exactBalanceIsAllowedAndLandsOnZero() {
            // 比例上限 0.5 会挡住"押上全部余额"，所以这里用单笔上限放宽后的限额，
            // 专门验证"余额恰好等于本金"这条边界：结算后必须落在 0，不能变成负数。
            GambleLimits allIn = new GambleLimits(1000, 1.0, 1000);
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 100, 0, 100, allIn, alwaysRoll(1));

            assertTrue(result.balanceAfter() >= 0);
            assertEquals(0, result.balanceAfter(),
                    "本金等于余额且必输时，余额应当恰好归零（保证金模型先扣后返，不会透支）");
        }

        @Test
        @DisplayName("押上全部余额在默认限额下被拒绝（比例上限挡住梭哈）")
        void allInIsRejectedUnderDefaultLimits() {
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 100, 0, 100, LIMITS, alwaysRoll(1));

            assertEquals(0, result.balanceDelta(),
                    "默认限额下 100 超过 100×0.5，应当被拒绝而不是梭哈");
        }

        @Test
        @DisplayName("随机扫一遍所有余额/本金组合，余额永不为负")
        void fuzzBalanceStaysNonNegative() {
            SplittableRandom random = new SplittableRandom(20261019L);
            for (int i = 0; i < 20_000; i++) {
                long balance = random.nextLong(0, 5_000_000);
                long stake = random.nextLong(1, 2_000_000);
                GambleMode mode = GambleMode.values()[random.nextInt(GambleMode.values().length)];
                int guess = 1 + random.nextInt(6);

                GambleResult result = GambleSettlement.settle(
                        mode, stake, guess, balance, LIMITS, random);

                assertTrue(result.balanceAfter() >= 0,
                        "余额变负: balance=" + balance + " stake=" + stake
                                + " mode=" + mode + " after=" + result.balanceAfter());
            }
        }
    }

    @Nested
    @DisplayName("限额（修参考项目的金额无上限）")
    class Limits {

        @Test
        @DisplayName("单笔超过上限被拒绝")
        void stakeOverMaxIsRejected() {
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, LIMITS.maxStake() + 1, 0, Long.MAX_VALUE / 2,
                    LIMITS, alwaysRoll(6));

            assertEquals(0, result.balanceDelta(), "超限下注必须被拒绝，余额不动");
            assertEquals(0, result.roll(), "被拒绝时不该掷骰");
        }

        @Test
        @DisplayName("单笔不得超过余额的一半（挡住一把梭哈）")
        void stakeOverHalfBalanceIsRejected() {
            long balance = 1000;
            // 600 > 1000 × 0.5
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 600, 0, balance, LIMITS, alwaysRoll(6));
            assertEquals(0, result.balanceDelta(), "超过余额比例上限的下注必须被拒绝");

            // 500 恰好等于一半，应当允许
            GambleResult allowed = GambleSettlement.settle(
                    GambleMode.BIG, 500, 0, balance, LIMITS, alwaysRoll(6));
            assertTrue(allowed.balanceDelta() != 0, "恰好等于比例上限的下注应当允许");
        }

        @Test
        @DisplayName("每日累计额度用尽后拒绝")
        void dailyCapIsEnforced() {
            long usedToday = LIMITS.dailyMaxStake();
            assertEquals(0, LIMITS.maxAllowedStake(Long.MAX_VALUE / 2, usedToday),
                    "今日额度用尽后不该还能下注");
        }

        @Test
        @DisplayName("限额构造时就挡住自相矛盾的配置")
        void limitsRejectContradictoryConfig() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GambleLimits(0, 0.5, 1000), "单笔上限必须为正");
            assertThrows(IllegalArgumentException.class,
                    () -> new GambleLimits(100, 0, 1000), "比例上限必须大于 0");
            assertThrows(IllegalArgumentException.class,
                    () -> new GambleLimits(100, 1.5, 1000), "比例上限不得超过 1");
            assertThrows(IllegalArgumentException.class,
                    () -> new GambleLimits(1000, 0.5, 500),
                    "每日上限小于单笔上限时，单笔上限形同虚设，应当在构造时就拒绝");
        }
    }

    @Nested
    @DisplayName("指令解析（修参考项目的字符类误用与未锚定）")
    class Parsing {

        @Test
        @DisplayName("正常形态都能解析")
        void normalFormsParse() {
            assertParsed("金银阁10大", GambleMode.BIG, 10, 0);
            assertParsed("金银阁 100 小", GambleMode.SMALL, 100, 0);
            assertParsed("金银阁10奇", GambleMode.ODD, 10, 0);
            assertParsed("金银阁10偶", GambleMode.EVEN, 10, 0);
            assertParsed("金银阁100猜3", GambleMode.GUESS, 100, 3);
            assertParsed("金银阁 100 猜 6", GambleMode.GUESS, 100, 6);
            assertParsed("  金银阁10大  ", GambleMode.BIG, 10, 0);
        }

        @Test
        @DisplayName("竖线不能被当成玩法（原版字符类误用会白扣钱）")
        void pipeIsNotAMode() {
            var result = GambleCommandParser.parse("金银阁10|");
            assertEquals(GambleCommandParser.Status.UNKNOWN_MODE, result.status(),
                    "`|` 在参考项目里被当成普通字符匹配成功，随后落入输分支白扣一次灵石。"
                            + "这里必须解析失败。实际: " + result);
            assertFalse(result.ok());
        }

        @Test
        @DisplayName("夹在闲聊里的金银阁不触发（原版未加锚定）")
        void textEmbeddedCommandIsIgnored() {
            var result = GambleCommandParser.parse("随便说点什么金银阁10大");
            assertEquals(GambleCommandParser.Status.NOT_A_COMMAND, result.status(),
                    "原版正则没有 ^...$ 锚定，闲聊里提到金银阁就会被扣钱。"
                            + "这里必须完全不认。实际: " + result);
            assertFalse(result.shouldReply(), "不是指令就不该有任何回应");
        }

        @Test
        @DisplayName("超长金额在解析阶段就被拒，不留给结算去溢出")
        void overflowAmountIsRejectedEarly() {
            var result = GambleCommandParser.parse("金银阁99999999999999999999大");
            assertEquals(GambleCommandParser.Status.INVALID_AMOUNT, result.status(),
                    "原版一路走到 sqlite 才抛 OverflowError，异常点离出错点隔着半个 handler。"
                            + "这里必须在解析阶段拒绝。实际: " + result);
        }

        @Test
        @DisplayName("语法不完整的形态给出 INCOMPLETE 而不是静默无响应")
        void incompleteFormsAreReported() {
            assertEquals(GambleCommandParser.Status.INCOMPLETE,
                    GambleCommandParser.parse("金银阁").status());
            assertEquals(GambleCommandParser.Status.INCOMPLETE,
                    GambleCommandParser.parse("金银阁10").status());
            assertEquals(GambleCommandParser.Status.INCOMPLETE,
                    GambleCommandParser.parse("金银阁10猜").status());
        }

        @Test
        @DisplayName("猜点数越界被拒")
        void guessOutOfRangeIsRejected() {
            assertEquals(GambleCommandParser.Status.INVALID_GUESS,
                    GambleCommandParser.parse("金银阁10猜7").status());
            assertEquals(GambleCommandParser.Status.INVALID_GUESS,
                    GambleCommandParser.parse("金银阁10猜0").status());
        }

        @Test
        @DisplayName("押大小奇偶时不该带点数（原版静默忽略）")
        void trailingNumberOnNonGuessModeIsRejected() {
            var result = GambleCommandParser.parse("金银阁10大5");
            assertEquals(GambleCommandParser.Status.UNKNOWN_MODE, result.status(),
                    "原版把 `5` 静默忽略，玩家会以为自己押了点什么。这里必须报错。实际: " + result);
        }

        @Test
        @DisplayName("未知玩法标签被拒")
        void unknownModeIsRejected() {
            assertEquals(GambleCommandParser.Status.UNKNOWN_MODE,
                    GambleCommandParser.parse("金银阁10豹子").status());
        }

        @Test
        @DisplayName("空输入与非指令输入不产生回应")
        void nonCommandProducesNoReply() {
            assertFalse(GambleCommandParser.parse(null).shouldReply());
            assertFalse(GambleCommandParser.parse("").shouldReply());
            assertFalse(GambleCommandParser.parse("修仙").shouldReply());
        }

        private void assertParsed(String raw, GambleMode mode, long stake, int guess) {
            var result = GambleCommandParser.parse(raw);
            assertTrue(result.ok(), "应当解析成功: " + raw + " 实际: " + result);
            assertEquals(mode, result.mode(), "玩法不符: " + raw);
            assertEquals(stake, result.stake(), "金额不符: " + raw);
            assertEquals(guess, result.guess(), "点数不符: " + raw);
        }
    }

    @Nested
    @DisplayName("结算正确性")
    class Settlement {

        @Test
        @DisplayName("猜大押中：净得 95，余额增加 95")
        void bigWinPaysNetOdds() {
            // 余额 1000，押 100 大，掷出 6 → 中奖
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 100, 0, 1000, LIMITS, alwaysRoll(6));

            assertTrue(result.win());
            assertEquals(95, result.netPayout(), "100 × 0.95 向下取整 = 95");
            assertEquals(1095, result.balanceAfter(), "先扣 100 再返还 195，净增 95");
            assertEquals(95, result.balanceDelta());
        }

        @Test
        @DisplayName("猜大押错：本金没收，余额减少本金")
        void bigLossForfeitsStake() {
            GambleResult result = GambleSettlement.settle(
                    GambleMode.BIG, 100, 0, 1000, LIMITS, alwaysRoll(1));

            assertFalse(result.win());
            assertEquals(0, result.netPayout(), "输了没有赔付");
            assertEquals(900, result.balanceAfter());
            assertEquals(-100, result.balanceDelta());
        }

        @Test
        @DisplayName("猜点数押中：净得 4.75 倍")
        void guessWinPaysHigherOdds() {
            GambleResult result = GambleSettlement.settle(
                    GambleMode.GUESS, 100, 3, 1000, LIMITS, alwaysRoll(3));

            assertTrue(result.win());
            assertEquals(475, result.netPayout(), "100 × 4.75 = 475");
            assertEquals(1475, result.balanceAfter());
            assertTrue(result.wasGuess());
        }

        @Test
        @DisplayName("结算的期望收益恒为负（长期必输，这是赌场该有的样子）")
        void expectedValueIsNegative() {
            // 用大量随机局估算 EV，抽水 2.5% 应当明显可见
            SplittableRandom random = new SplittableRandom(42L);
            long totalDelta = 0;
            int rounds = 200_000;
            long stake = 100;

            for (int i = 0; i < rounds; i++) {
                GambleResult result = GambleSettlement.settle(
                        GambleMode.BIG, stake, 0, 1_000_000, LIMITS, random);
                totalDelta += result.balanceDelta();
            }

            double evPerRound = (double) totalDelta / rounds;
            assertTrue(evPerRound < 0,
                    "单局期望收益必须为负（实测 " + evPerRound + "）");
            // 理论值 -2.5，留足随机波动余量
            assertTrue(evPerRound > -6 && evPerRound < -1,
                    "单局期望收益应接近 -2.5（抽水 2.5%），实际: " + evPerRound);
        }

        @Test
        @DisplayName("非法参数直接抛异常，不返回一个看似合法的结果")
        void invalidArgumentsThrow() {
            assertThrows(IllegalArgumentException.class,
                    () -> GambleSettlement.settle(null, 100, 0, 1000, LIMITS, alwaysRoll(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> GambleSettlement.settle(GambleMode.BIG, 0, 0, 1000, LIMITS, alwaysRoll(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> GambleSettlement.settle(GambleMode.BIG, -100, 0, 1000, LIMITS, alwaysRoll(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> GambleSettlement.settle(GambleMode.GUESS, 100, 7, 1000, LIMITS, alwaysRoll(1)),
                    "猜点数越界必须抛异常，不能当成必输处理");
        }
    }
}
