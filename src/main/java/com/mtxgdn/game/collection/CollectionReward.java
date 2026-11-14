package com.mtxgdn.game.collection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次图鉴解锁发放的奖励。
 *
 * <p>全部字段非负；{@code items} 是「物品 key → 数量」，数量 &gt; 0。
 * 奖励在解锁事务内发放（见 {@link CollectionService#unlockInTransaction}），
 * 走的是各服务的 {@code conn} 重载，因此"记录已解锁"和"发奖"要么一起成功、要么一起回滚。</p>
 */
public record CollectionReward(long gold,
                               long spiritStones,
                               long exp,
                               Map<String, Long> items) {

    public static final CollectionReward EMPTY = new CollectionReward(0, 0, 0, Map.of());

    public CollectionReward {
        items = items == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(items));
    }

    public boolean isEmpty() {
        return gold <= 0 && spiritStones <= 0 && exp <= 0 && items.isEmpty();
    }

    /** 两份奖励相加（解锁奖励 + 集齐奖励合并展示/发放）。 */
    public CollectionReward plus(CollectionReward other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Map<String, Long> merged = new LinkedHashMap<>(items);
        other.items.forEach((key, amount) -> merged.merge(key, amount, Long::sum));
        return new CollectionReward(gold + other.gold,
                spiritStones + other.spiritStones,
                exp + other.exp,
                merged);
    }

    /** 给玩家看的单行描述，例如「金币 +1000、灵石 +10、物品 mtxgdn:healing_pill ×2」。 */
    public String describe() {
        if (isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "金币", gold);
        append(sb, "灵石", spiritStones);
        append(sb, "修为", exp);
        for (Map.Entry<String, Long> entry : items.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(entry.getKey()).append(" ×").append(entry.getValue());
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, long value) {
        if (value <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("、");
        }
        sb.append(label).append(" +").append(value);
    }
}
