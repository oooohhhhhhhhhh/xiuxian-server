package com.mtxgdn.game.crafting;

/**
 * 制造（炼丹/炼器）的结果。
 *
 * <p>三种形状：校验失败只有 {@code success}/{@code message}；制造成功多带
 * {@code craftSuccess} 与产物信息；「材料够但掷骰失败」也算成功执行
 * （消耗了材料、拿到安慰经验），带 {@code craftSuccess=false}。</p>
 *
 * <p>可空字段是为了让 Gson 跳过 null，保持「该分支没有这个字段」的语义：
 * 三个入口都靠 {@code containsKey} 判断要不要把字段写进响应，
 * 写成 0 会让每个响应都多出一堆值为 0 的字段。</p>
 *
 * @param success 操作是否成功执行（掷骰失败也是 true，看 craftSuccess）
 * @param message 给玩家看的提示
 * @param craftSuccess 是否真的炼成了；校验失败时为 null
 * @param expGained 获得的经验；校验失败时为 null
 * @param itemGained 产出的物品键；仅炼成时有值
 * @param itemQuantity 产出数量；仅炼成时有值
 * @param quality 品质枚举名；仅炼成时有值
 * @param qualityLabel 品质显示名；仅炼成时有值
 */
public record CraftResult(
        boolean success,
        String message,
        Boolean craftSuccess,
        Long expGained,
        String itemGained,
        Integer itemQuantity,
        String quality,
        String qualityLabel) {

    /** 前置校验失败：只有 success 与 message。 */
    public static CraftResult rejected(String message) {
        return new CraftResult(false, message, null, null, null, null, null, null);
    }

    /** 事务抛异常。 */
    public static CraftResult failed(String message) {
        return new CraftResult(false, message, null, null, null, null, null, null);
    }

    /** 炼成。 */
    public static CraftResult crafted(String message, long expGained, String itemGained,
                                      int itemQuantity, String quality, String qualityLabel) {
        return new CraftResult(true, message, true, expGained, itemGained,
                itemQuantity, quality, qualityLabel);
    }

    /** 掷骰失败，只给安慰经验。 */
    public static CraftResult pity(String message, long pityExp) {
        return new CraftResult(true, message, false, pityExp, null, null, null, null);
    }
}
