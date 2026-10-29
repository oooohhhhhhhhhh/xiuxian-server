package com.mtxgdn.game.economy;

/**
 * 物品回收的结果。
 *
 * @param success 是否回收成功
 * @param message 给玩家看的提示
 * @param recycled 回收内容描述（"物品名 x数量"）；失败时为 null
 * @param stonesGained 获得的灵石数；失败时为 null
 */
public record RecycleResult(
        boolean success,
        String message,
        String recycled,
        Long stonesGained) {

    public static RecycleResult rejected(String message) {
        return new RecycleResult(false, message, null, null);
    }

    public static RecycleResult recycled(String message, String recycled, long stonesGained) {
        return new RecycleResult(true, message, recycled, stonesGained);
    }
}
