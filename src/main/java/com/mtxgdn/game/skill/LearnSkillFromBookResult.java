package com.mtxgdn.game.skill;

import com.mtxgdn.game.entity.Skill;

/**
 * 从技能书领悟技能的结果。
 *
 * <p>已经学会过该技能时是「升级」，会多带一个 {@code newLevel}；
 * 第一次学会时没有这个字段，因此用可空的 {@link Integer} 而不是 0 ——
 * 0 会被序列化成 {@code "newLevel":0}，凭空多出一个字段。</p>
 *
 * @param success 是否领悟成功
 * @param message 给玩家看的提示
 * @param skill 对应技能；技能不存在时为 null
 * @param newLevel 升级后的等级；首次学会时为 null
 */
public record LearnSkillFromBookResult(
        boolean success, String message, Skill skill, Integer newLevel) {

    public static LearnSkillFromBookResult ok(String message, Skill skill) {
        return new LearnSkillFromBookResult(true, message, skill, null);
    }

    public static LearnSkillFromBookResult leveledUp(String message, Skill skill, int newLevel) {
        return new LearnSkillFromBookResult(true, message, skill, newLevel);
    }

    public static LearnSkillFromBookResult fail(String message) {
        return new LearnSkillFromBookResult(false, message, null, null);
    }
}
