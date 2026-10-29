package com.mtxgdn.game.economy;

/**
 * 商店购买的结果。
 *
 * @param success 是否购买成功
 * @param message 给玩家看的提示
 * @param item 物品显示名；失败时为 null
 * @param cost 花费灵石；失败时为 null
 */
public record ShopPurchaseResult(
        boolean success,
        String message,
        String item,
        Integer cost) {

    public static ShopPurchaseResult rejected(String message) {
        return new ShopPurchaseResult(false, message, null, null);
    }

    public static ShopPurchaseResult bought(String message, String item, int cost) {
        return new ShopPurchaseResult(true, message, item, cost);
    }
}
