package com.mtxgdn.game.cave;

/**
 * 洞府升级的结果。
 *
 * @param success 是否升级成功
 * @param message 给玩家看的提示
 * @param newLevel 升级后的等级；失败时为 null
 */
public record CaveLevelUpResult(boolean success, String message, Integer newLevel) {

    public static CaveLevelUpResult rejected(String message) {
        return new CaveLevelUpResult(false, message, null);
    }

    public static CaveLevelUpResult leveledUp(String message, int newLevel) {
        return new CaveLevelUpResult(true, message, newLevel);
    }
}
