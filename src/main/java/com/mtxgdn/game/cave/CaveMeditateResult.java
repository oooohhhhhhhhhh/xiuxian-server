package com.mtxgdn.game.cave;

import com.mtxgdn.game.service.HeartDemonService;

/**
 * 洞中冥想的结果。
 *
 * <p>{@code heartDemon} 只在触发心魔劫时才有值 —— 未触发时原实现压根不写这个键，
 * 用可空字段保住这个区别。</p>
 *
 * @param success 是否成功冥想
 * @param message 给玩家看的提示
 * @param expGain 实际获得的经验；失败时为 null
 * @param bonus 洞府 + 阵法的总加成百分比；失败时为 null
 * @param heartDemon 心魔劫结算详情；未触发或失败时为 null
 */
public record CaveMeditateResult(
        boolean success,
        String message,
        Long expGain,
        Integer bonus,
        HeartDemonService.HeartDemonResult heartDemon) {

    public static CaveMeditateResult rejected(String message) {
        return new CaveMeditateResult(false, message, null, null, null);
    }

    public static CaveMeditateResult meditated(
            String message, long expGain, int bonus, HeartDemonService.HeartDemonResult heartDemon) {
        return new CaveMeditateResult(true, message, expGain, bonus, heartDemon);
    }
}
