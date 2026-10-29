package com.mtxgdn.game.map;

/**
 * 移动（含传送）的结果。
 *
 * <p>三种移动共用同一形状但附加字段不同：普通移动带 from/to/路程/首访标记，
 * 传送符只有 message（可带首访标记），解锁传送带 to/区域/花费。用可空字段
 * 保住这个区别，避免失败分支被序列化出一堆默认值。</p>
 *
 * @param success 是否完成移动
 * @param message 给玩家看的提示
 * @param from 出发地；仅普通移动有值
 * @param to 目的地；普通移动与解锁传送有值
 * @param toRegion 目的地所属区域；普通移动与解锁传送有值
 * @param safeZone 目的地是否安全区；普通移动与解锁传送有值
 * @param travelSeconds 路程秒数；仅普通移动有值
 * @param dangerLevel 目的地危险度；普通移动与解锁传送有值
 * @param dangerName 危险度显示名；普通移动与解锁传送有值
 * @param wilderness 目的地是否野外；仅普通移动有值
 * @param firstVisit 是否首次抵达；非首访为 null
 * @param cost 传送花费灵石；仅解锁传送有值
 */
public record TravelResult(
        boolean success,
        String message,
        String from,
        String to,
        String toRegion,
        Boolean safeZone,
        Long travelSeconds,
        Integer dangerLevel,
        String dangerName,
        Boolean wilderness,
        Boolean firstVisit,
        Long cost) {

    /** 校验失败：只有 success 与 message。 */
    public static TravelResult rejected(String message) {
        return new TravelResult(false, message, null, null, null, null,
                null, null, null, null, null, null);
    }

    /** 普通移动成功。 */
    public static TravelResult traveled(
            String message, String from, String to, String toRegion,
            boolean safeZone, long travelSeconds, int dangerLevel, String dangerName,
            boolean wilderness, boolean firstVisit) {
        return new TravelResult(true, message, from, to, toRegion, safeZone,
                travelSeconds, dangerLevel, dangerName, wilderness,
                firstVisit ? Boolean.TRUE : null, null);
    }

    /** 传送符成功：只有提示（可带首访标记）。 */
    public static TravelResult teleported(String message, boolean firstVisit) {
        return new TravelResult(true, message, null, null, null, null,
                null, null, null, null, firstVisit ? Boolean.TRUE : null, null);
    }

    /** 解锁传送成功：带目的地与花费。 */
    public static TravelResult teleportedByUnlock(
            String message, String to, String toRegion,
            boolean safeZone, long cost, int dangerLevel, String dangerName) {
        return new TravelResult(true, message, null, to, toRegion, safeZone,
                null, dangerLevel, dangerName, null, null, cost);
    }
}
