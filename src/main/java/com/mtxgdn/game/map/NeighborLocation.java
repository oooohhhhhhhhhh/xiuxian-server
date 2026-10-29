package com.mtxgdn.game.map;

/**
 * 相邻地点条目（含路程与可达性）。
 *
 * @param id 地点 ID
 * @param name 地点名
 * @param region 所属区域
 * @param safeZone 是否安全区
 * @param accessible 当前玩家境界是否够
 * @param travelSeconds 路程秒数
 * @param dangerLevel 危险度 0..4
 * @param dangerName 危险度显示名
 * @param wilderness 是否野外节点
 */
public record NeighborLocation(
        long id,
        String name,
        String region,
        boolean safeZone,
        boolean accessible,
        long travelSeconds,
        int dangerLevel,
        String dangerName,
        boolean wilderness) {
}
