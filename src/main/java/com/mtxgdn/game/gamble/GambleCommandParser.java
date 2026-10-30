package com.mtxgdn.game.gamble;

import java.util.Optional;

/**
 * 金银阁的指令解析。
 *
 * <p><b>为什么单独抽一个解析器</b>：参考项目把解析塞在一条正则里
 * （{@code r"(金银阁)\s?(\d+)\s?([大|小|奇|偶|猜])?\s?(\d+)?"}），
 * 那条正则有两个各自独立、都能直接换钱的缺陷：</p>
 *
 * <ol>
 *   <li><b>{@code ([大|小|奇|偶|猜])} 是字符类，不是分组</b>：
 *       方括号里的 {@code |} 被当成<b>普通字符</b>，于是
 *       {@code 金银阁10|} 也能匹配，{@code mode} 取到 {@code "|"}，
 *       随后六个 {@code if/elif} 一个都不中，落进最后的 else 分支
 *       —— 白扣一次灵石。</li>
 *   <li><b>没有 {@code ^...$} 锚定</b>：{@code "随便说点什么金银阁10大"}
 *       整句也会触发结算。玩家在闲聊里提到这三个字就会被扣钱，
 *       而且可能与其它 matcher 抢消息。</li>
 * </ol>
 *
 * <p>这里改成"整串匹配 + 显式枚举"，两个缺陷从结构上就不存在了：
 * 输入必须<b>整个</b>是金银阁指令（允许首尾空白），
 * 玩法标签必须是 {@link GambleMode#fromLabel} 认识的五个字之一。</p>
 *
 * <p><b>为什么不接受 {@code /金银阁}、{@code /猜大}、{@code /猜小}</b>：
 * 参考项目的《完整文档.md》写着这三个指令，但代码里<b>一个都不存在</b> ——
 * 照文档配置的运营方会以为功能坏了。这里只实现代码里真实存在的形态，
 * 不为了迁就一份写错的文档而增加三个没人测过的入口。</p>
 */
public final class GambleCommandParser {

    /** 指令前缀。 */
    private static final String PREFIX = "金银阁";

    private GambleCommandParser() {
    }

    /**
     * 解析一条指令。
     *
     * <p>接受三种形态（首尾空白忽略，中间允许一个或多个空白）：</p>
     * <ul>
     *   <li>{@code 金银阁100大} / {@code 金银阁 100 大} → 押大小奇偶</li>
     *   <li>{@code 金银阁100猜3} / {@code 金银阁 100 猜 3} → 猜点数</li>
     *   <li>{@code 金银阁} / {@code 金银阁100} → 语法不完整，返回 {@link Status#INCOMPLETE}</li>
     * </ul>
     *
     * @param raw 原始消息文本
     * @return 解析结果；不匹配前缀时返回 {@link Status#NOT_A_COMMAND}
     */
    public static ParseResult parse(String raw) {
        if (raw == null) {
            return new ParseResult(Status.NOT_A_COMMAND, null, 0, 0, "消息为空");
        }
        String text = raw.strip();
        if (!text.startsWith(PREFIX)) {
            return new ParseResult(Status.NOT_A_COMMAND, null, 0, 0, "不是金银阁指令");
        }

        String rest = text.substring(PREFIX.length()).strip();
        if (rest.isEmpty()) {
            return new ParseResult(Status.INCOMPLETE, null, 0, 0, "缺少金额");
        }

        // 金额：开头连续的数字。用显式扫描而不是正则 ——
        // 这里需要"金额后面紧跟玩法"这种位置关系，正则写出来反而更难读。
        int index = 0;
        while (index < rest.length() && isAsciiDigit(rest.charAt(index))) {
            index++;
        }
        if (index == 0) {
            return new ParseResult(Status.INCOMPLETE, null, 0, 0, "缺少金额");
        }

        String amountText = rest.substring(0, index);
        long stake = parseAmount(amountText);
        if (stake < 0) {
            // 溢出或非法：不能返回一个"看起来合法"的金额，
            // 否则会走到结算逻辑里变成一次巨额下注。
            return new ParseResult(Status.INVALID_AMOUNT, null, 0, 0, "金额超出可处理范围");
        }

        String tail = rest.substring(index).strip();
        if (tail.isEmpty()) {
            return new ParseResult(Status.INCOMPLETE, null, stake, 0, "缺少玩法（大/小/奇/偶/猜）");
        }

        // 玩法标签必须是单字：这样 "大5" 这种尾巴会自然落到"未知玩法"，
        // 而不是被当成"押大 + 忽略点数"。原版对大/小后面的多余数字是静默忽略的。
        String modeLabel = tail.substring(0, 1);
        GambleMode mode = GambleMode.fromLabel(modeLabel);
        if (mode == null) {
            return new ParseResult(Status.UNKNOWN_MODE, null, stake, 0,
                    "未知玩法: " + modeLabel + "（只支持 大/小/奇/偶/猜）");
        }

        String guessText = tail.substring(1).strip();
        if (mode == GambleMode.GUESS) {
            if (guessText.isEmpty()) {
                return new ParseResult(Status.INCOMPLETE, mode, stake, 0, "猜点数缺少点数");
            }
            int guess = parseGuess(guessText);
            if (guess < 0) {
                return new ParseResult(Status.INVALID_GUESS, mode, stake, 0,
                        "猜点数必须是 1~6 的整数: " + guessText);
            }
            return new ParseResult(Status.OK, mode, stake, guess, "");
        }

        // 非猜点数模式不该带尾巴："金银阁10大5" 在原版里 5 被静默忽略，
        // 玩家会以为自己押了点什么。这里明确报错。
        if (!guessText.isEmpty()) {
            return new ParseResult(Status.UNKNOWN_MODE, mode, stake, 0,
                    "押 " + modeLabel + " 时不该带点数: " + guessText);
        }
        return new ParseResult(Status.OK, mode, stake, 0, "");
    }

    /**
     * 解析金额。
     *
     * <p>刻意不接受超过 {@code long} 的数字：参考项目用 Python 的任意精度整数，
     * 于是 {@code 99999999999999999999} 能一路走到 sqlite 才抛
     * {@code OverflowError} —— 异常点离出错点隔着半个 handler，极难定位。
     * 这里在解析阶段就拒绝。</p>
     *
     * @return 金额；非法或溢出返回 -1
     */
    private static long parseAmount(String text) {
        try {
            long value = Long.parseLong(text);
            return value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** @return 1~6 的整数；非法返回 -1 */
    private static int parseGuess(String text) {
        try {
            int value = Integer.parseInt(text);
            return (value >= 1 && value <= 6) ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** 解析状态。 */
    public enum Status {
        /** 可以结算。 */
        OK,
        /** 压根不是金银阁指令（不该有任何回应）。 */
        NOT_A_COMMAND,
        /** 是金银阁指令但语法不完整（该回帮助）。 */
        INCOMPLETE,
        /** 金额非法或溢出。 */
        INVALID_AMOUNT,
        /** 玩法标签不认识。 */
        UNKNOWN_MODE,
        /** 猜点数越界。 */
        INVALID_GUESS
    }

    /**
     * 解析结果。
     *
     * @param status 状态
     * @param mode   玩法；语法没走到那一步时为 {@code null}
     * @param stake  金额；未解析出时为 0
     * @param guess  猜的点数；非猜点数模式或未解析时为 0
     * @param message 给玩家看的说明；成功时为空串
     */
    public record ParseResult(
            Status status,
            GambleMode mode,
            long stake,
            int guess,
            String message) {

        public boolean ok() {
            return status == Status.OK;
        }

        /** 是否应该给玩家任何回应。 */
        public boolean shouldReply() {
            return status != Status.NOT_A_COMMAND;
        }

        /** 成功时取出玩法，失败时为空。 */
        public Optional<GambleMode> modeIfOk() {
            return ok() ? Optional.ofNullable(mode) : Optional.empty();
        }
    }
}
