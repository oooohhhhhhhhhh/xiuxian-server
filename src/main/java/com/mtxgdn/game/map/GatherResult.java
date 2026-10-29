package com.mtxgdn.game.map;

/**
 * 采集的结果。
 *
 * @param success 是否采到东西
 * @param message 给玩家看的提示
 * @param itemKey 采到的物品 key；失败时为 null
 * @param itemName 物品显示名；失败时为 null
 * @param quantity 本次采到数量；失败时为 null
 * @param remaining 该地剩余量；失败时为 null
 */
public record GatherResult(
        boolean success,
        String message,
        String itemKey,
        String itemName,
        Integer quantity,
        Integer remaining) {

    public static GatherResult rejected(String message) {
        return new GatherResult(false, message, null, null, null, null);
    }

    public static GatherResult gathered(
            String message, String itemKey, String itemName, int quantity, int remaining) {
        return new GatherResult(true, message, itemKey, itemName, quantity, remaining);
    }
}
