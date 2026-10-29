package com.mtxgdn.game.sect;

import com.mtxgdn.game.entity.Sect;

/**
 * 宗门建档类操作的结果（创建 / 升级 / 转让 / 解散）。
 *
 * <p>只有创建成功时带 {@code sect}（新宗门实体，供调用方立刻展示）。
 * 失败分支只有提示。</p>
 *
 * @param success 是否操作成功
 * @param message 给玩家看的提示
 * @param sect 新建的宗门；仅创建成功时有值
 */
public record SectActionResult(boolean success, String message, Sect sect) {

    public static SectActionResult ok(String message) {
        return new SectActionResult(true, message, null);
    }

    public static SectActionResult created(String message, Sect sect) {
        return new SectActionResult(true, message, sect);
    }

    public static SectActionResult fail(String message) {
        return new SectActionResult(false, message, null);
    }
}
