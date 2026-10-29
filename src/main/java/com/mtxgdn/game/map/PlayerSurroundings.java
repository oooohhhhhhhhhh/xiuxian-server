package com.mtxgdn.game.map;

import java.util.List;
import java.util.Map;

/**
 * 当前位置及周边信息。
 *
 * <p>{@code wildernessResource} 与 {@code wildernessExpiresAt} 只在野外节点上有值；
 * 其余字段在「地图数据未就绪」时整份缺席（那种情况走 rejected）。</p>
 *
 * @param success 是否查询成功
 * @param message 给玩家看的提示；成功时为 null
 * @param current 当前位置简介
 * @param neighbors 相邻地点
 * @param cultivationBonus 灵气倍率
 * @param explorationBonus 地界倍率
 * @param dangerLevel 危险度
 * @param dangerName 危险度显示名
 * @param wilderness 是否野外
 * @param wildernessResource 野外剩余资源（物品 key → 数量）；非野外为 null
 * @param wildernessExpiresAt 野外刷新时间戳（秒）；非野外为 null
 */
public record PlayerSurroundings(
        boolean success,
        String message,
        LocationBrief current,
        List<NeighborLocation> neighbors,
        Double cultivationBonus,
        Double explorationBonus,
        Integer dangerLevel,
        String dangerName,
        Boolean wilderness,
        Map<String, Integer> wildernessResource,
        Long wildernessExpiresAt) {

    /** 地图数据未就绪。 */
    public static PlayerSurroundings rejected(String message) {
        return new PlayerSurroundings(false, message, null, null, null, null,
                null, null, null, null, null);
    }

    public static PlayerSurroundings of(
            LocationBrief current,
            List<NeighborLocation> neighbors,
            double cultivationBonus,
            double explorationBonus,
            int dangerLevel,
            String dangerName,
            boolean wilderness,
            Map<String, Integer> wildernessResource,
            Long wildernessExpiresAt) {
        return new PlayerSurroundings(true, null, current, neighbors,
                cultivationBonus, explorationBonus, dangerLevel, dangerName,
                wilderness, wildernessResource, wildernessExpiresAt);
    }
}
