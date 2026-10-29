package com.mtxgdn.game.item;

import com.mtxgdn.game.entity.Formation;
import java.util.List;

/**
 * 使用物品的结果。
 *
 * <p>普通消耗品成功时带 {@code item} 与 {@code effects}（客户端要用它们渲染
 * "用了什么、生效了什么"）；阵图走的是另一条路径，成功后带的是新布下的
 * {@code formation}。两个分支的附加字段互斥，用可空字段保住这个区别。</p>
 *
 * @param success 是否使用成功
 * @param message 给玩家看的提示
 * @param item 被使用的物品；仅普通物品成功时有值
 * @param effects 物品效果列表；仅普通物品成功时有值
 * @param formation 布下的阵法；仅阵图分支成功时有值
 */
public record UseItemResult(
        boolean success,
        String message,
        Object item,
        List<?> effects,
        Formation formation) {

    public static UseItemResult rejected(String message) {
        return new UseItemResult(false, message, null, null, null);
    }

    /** 普通物品使用成功。 */
    public static UseItemResult used(String message, Object item, List<?> effects) {
        return new UseItemResult(true, message, item, effects, null);
    }

    /** 阵图使用成功（布下阵法）。 */
    public static UseItemResult formationPlaced(String message, Formation formation) {
        return new UseItemResult(true, message, null, null, formation);
    }
}
