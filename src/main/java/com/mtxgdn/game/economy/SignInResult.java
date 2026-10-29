package com.mtxgdn.game.economy;

/**
 * 每日签到的结果。
 *
 * @param success 是否签到成功
 * @param message 给玩家看的提示
 * @param day 本次是第几天（1..7）；失败时为 null
 * @param streak 连续签到天数；失败时为 null
 * @param reward 奖励描述文本；失败时为 null
 */
public record SignInResult(
        boolean success,
        String message,
        Integer day,
        Integer streak,
        String reward) {

    public static SignInResult rejected(String message) {
        return new SignInResult(false, message, null, null, null);
    }

    public static SignInResult signed(String message, int day, int streak, String reward) {
        return new SignInResult(true, message, day, streak, reward);
    }
}
