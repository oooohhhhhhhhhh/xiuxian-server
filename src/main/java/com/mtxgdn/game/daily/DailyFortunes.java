package com.mtxgdn.game.daily;

/**
 * 四条今日机缘的进度汇总。
 *
 * @param exploration 探索秘境
 * @param battle 参与战斗
 * @param secretRealm 进入秘境
 * @param skillLearn 学习技能
 */
public record DailyFortunes(
        DailyFortune exploration,
        DailyFortune battle,
        DailyFortune secretRealm,
        DailyFortune skillLearn) {
}
