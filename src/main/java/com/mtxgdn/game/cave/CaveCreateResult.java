package com.mtxgdn.game.cave;

import com.mtxgdn.game.entity.Cave;

/**
 * 开辟洞府的结果。
 *
 * @param success 是否成功开辟
 * @param message 给玩家看的提示
 * @param cave 新洞府；失败时为 null
 */
public record CaveCreateResult(boolean success, String message, Cave cave) {

    public static CaveCreateResult rejected(String message) {
        return new CaveCreateResult(false, message, null);
    }

    public static CaveCreateResult created(String message, Cave cave) {
        return new CaveCreateResult(true, message, cave);
    }
}
