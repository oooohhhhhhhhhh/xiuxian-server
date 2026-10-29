package com.mtxgdn.game.daily;

/**
 * 今日天象与机缘面板。
 *
 * <p>三个加成字段是已经格式化好的百分比字符串（如 {@code "+20%"}），
 * 保留字符串形态是因为前端直接展示、不再参与运算。</p>
 *
 * @param phenomenon 天象名
 * @param phenomenonDesc 天象描述
 * @param cultivationBonus 修炼加成，如 "+20%"
 * @param explorationBonus 探索加成，如 "+10%"
 * @param spiritStoneBonus 灵石加成，如 "+15%"
 * @param morningCultivationDone 今日是否已晨修
 * @param consecutiveDays 连续晨修天数
 * @param totalActiveDays 累计活跃天数
 * @param fortunes 四条机缘进度
 * @param dailyTask 今日待办描述
 * @param taskReward 完成奖励描述；无奖励时为 null
 */
public record DailyInfo(
        String phenomenon,
        String phenomenonDesc,
        String cultivationBonus,
        String explorationBonus,
        String spiritStoneBonus,
        boolean morningCultivationDone,
        int consecutiveDays,
        int totalActiveDays,
        DailyFortunes fortunes,
        String dailyTask,
        String taskReward) {
}
