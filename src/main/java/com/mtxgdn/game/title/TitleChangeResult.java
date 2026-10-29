package com.mtxgdn.game.title;

/**
 * 授予 / 装备称号的结果。
 *
 * <p>成功时额外带回 {@code titleKey} 与 {@code titleName}：管理员接口
 * （{@code AdminPlayersResource}）把这个结果整体序列化成 HTTP 响应，
 * 字段名就是对外契约，不能丢。</p>
 *
 * <p>失败时两个字段为 null，Gson 默认跳过 null 字段，因此失败响应仍然
 * 只有 {@code success} 与 {@code message}，与改造前的 Map 形状一致。</p>
 *
 * @param success 是否成功
 * @param message 给玩家看的提示
 * @param titleKey 称号标识；失败时为 null
 * @param titleName 称号显示名；失败时为 null
 */
public record TitleChangeResult(
        boolean success, String message, String titleKey, String titleName) {

    public static TitleChangeResult ok(String message, String titleKey, String titleName) {
        return new TitleChangeResult(true, message, titleKey, titleName);
    }

    public static TitleChangeResult fail(String message) {
        return new TitleChangeResult(false, message, null, null);
    }
}
