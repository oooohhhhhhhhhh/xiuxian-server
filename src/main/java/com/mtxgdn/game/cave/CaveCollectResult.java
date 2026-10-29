package com.mtxgdn.game.cave;

/**
 * 收集洞府灵气的结果。
 *
 * @param success 是否收集成功
 * @param message 给玩家看的提示
 * @param collected 本次收到的灵石；失败时为 null
 */
public record CaveCollectResult(boolean success, String message, Long collected) {

    public static CaveCollectResult rejected(String message) {
        return new CaveCollectResult(false, message, null);
    }

    public static CaveCollectResult collected(String message, long collected) {
        return new CaveCollectResult(true, message, collected);
    }
}
