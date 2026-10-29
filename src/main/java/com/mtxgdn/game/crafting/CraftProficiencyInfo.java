package com.mtxgdn.game.crafting;

/**
 * 合成熟练度详情（供 {@code /合成 熟练度} 展示）。
 *
 * @param craftCount 累计制造成功次数
 * @param level 由次数派生的熟练度等级
 * @param maxLevel 等级上限
 * @param nextLevelAt 还差多少次升下一级；已满级为 -1
 * @param successBonus 成功率加成（小数，如 0.015 表示 +1.5%）
 */
public record CraftProficiencyInfo(
        int craftCount,
        int level,
        int maxLevel,
        int nextLevelAt,
        double successBonus) {
}
