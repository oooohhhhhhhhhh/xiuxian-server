package com.mtxgdn.game.item;

/**
 * 装备物品的结果。
 *
 * @param success 是否装备成功
 * @param message 给玩家看的提示
 */
public record EquipResult(boolean success, String message) {

    public static EquipResult ok(String message) {
        return new EquipResult(true, message);
    }

    public static EquipResult fail(String message) {
        return new EquipResult(false, message);
    }
}
