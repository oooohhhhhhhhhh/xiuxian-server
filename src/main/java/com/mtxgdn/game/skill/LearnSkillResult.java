package com.mtxgdn.game.skill;

import com.mtxgdn.game.entity.Skill;

/**
 * 学习技能的结果。
 *
 * <p>失败时带一个业务错误码（6101 技能不存在、6102 已学会、6103 境界不足、
 * 6104 资源不足、6106 写库失败），HTTP 入口用它作为响应的 code，
 * 因此不能退化成只有 success/message 的通用结果。</p>
 *
 * @param success 是否学会
 * @param message 给玩家看的提示
 * @param code 业务错误码；成功时为 0
 * @param skill 学会的技能；失败时为 null
 */
public record LearnSkillResult(boolean success, String message, int code, Skill skill) {

    public static LearnSkillResult ok(String message, Skill skill) {
        return new LearnSkillResult(true, message, 0, skill);
    }

    public static LearnSkillResult fail(String message, int code) {
        return new LearnSkillResult(false, message, code, null);
    }
}
