package com.mtxgdn.game.skill;

/**
 * 技能熟练度累加的结果。
 *
 * <p>三个「无事发生」的分支（未学会、已满级）原本只写 success/leveledUp，
 * 不带等级与熟练度字段。这里用可空包装保持这个区别：字段为 null 时
 * 调用方不会把它当成 0 级 / 0 熟练度。</p>
 *
 * @param success 是否执行成功（未学会也算失败）
 * @param leveledUp 本次是否升级
 * @param level 当前等级；未学会时为 null
 * @param proficiency 当前熟练度；未学会时为 null
 * @param proficiencyForNext 升到下一级所需熟练度；未学会时为 null
 */
public record SkillProficiencyResult(
        boolean success,
        boolean leveledUp,
        Integer level,
        Integer proficiency,
        Integer proficiencyForNext) {

    public static SkillProficiencyResult fail() {
        return new SkillProficiencyResult(false, false, null, null, null);
    }

    public static SkillProficiencyResult maxed(int level) {
        return new SkillProficiencyResult(true, false, level, null, null);
    }

    public static SkillProficiencyResult of(
            boolean leveledUp, int level, int proficiency, int proficiencyForNext) {
        return new SkillProficiencyResult(
                true, leveledUp, level, proficiency, proficiencyForNext);
    }
}
