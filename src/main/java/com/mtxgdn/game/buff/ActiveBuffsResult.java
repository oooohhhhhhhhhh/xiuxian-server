package com.mtxgdn.game.buff;

import java.util.List;

/**
 * 玩家当前生效的增益列表。
 *
 * <p>原来的返回是 {@code Map<String, Object>}，{@code buffs} 里装的还是
 * {@code Map<String, Object>}。两层字符串键意味着任何一处键名写错都只有
 * 运行时才炸，而这里正是玩家属性面板的数据来源。</p>
 *
 * @param buffs 生效中的增益，按查询顺序
 * @param count 条数，等于 {@code buffs.size()}，为兼容既有响应而保留
 */
public record ActiveBuffsResult(List<ActiveBuffView> buffs, int count) {

    public static ActiveBuffsResult of(List<ActiveBuffView> buffs) {
        return new ActiveBuffsResult(List.copyOf(buffs), buffs.size());
    }
}
