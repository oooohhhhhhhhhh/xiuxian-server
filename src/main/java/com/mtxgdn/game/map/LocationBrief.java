package com.mtxgdn.game.map;

/**
 * 地点简介（位置、邻居列表里的单个条目）。
 *
 * <p>字段名与原先的 Map 逐字一致，序列化结果不变。</p>
 *
 * @param id 地点 ID
 * @param name 地点名
 * @param description 地点描述
 * @param region 所属区域
 * @param safeZone 是否安全区
 */
public record LocationBrief(
        long id,
        String name,
        String description,
        String region,
        boolean safeZone) {
}
