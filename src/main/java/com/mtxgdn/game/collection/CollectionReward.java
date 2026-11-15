package com.mtxgdn.game.collection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

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

    /** 给玩家看的单行描述（物品名回落成 key），例如「金币 +1000、下品灵石 +10、回血丹 ×2」。 */
    public String describe() {
        return describe(key -> key);
    }

    /**
     * 给玩家看的单行描述，物品 key 交给 {@code itemNamer} 转成显示名。
     *
     * <p>为什么不在这里直接查 {@code ItemRegistry}：本类是纯值对象（配置解析与测试都用它），
     * 让它可以脱离物品注册表单独构造；名字解析由服务层通过 {@code itemNamer} 注入。</p>
     */
    public String describe(Function<String, String> itemNamer) {
        if (isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "金币", gold);
        append(sb, "下品灵石", spiritStones);
        append(sb, "修为", exp);
        for (Map.Entry<String, Long> entry : items.entrySet()) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            String key = entry.getKey();
            String name = key;
            if (itemNamer != null) {
                try {
                    String resolved = itemNamer.apply(key);
                    if (resolved != null && !resolved.isBlank()) {
                        name = resolved;
                    }
                } catch (RuntimeException ignored) {
                    // 名字解析失败不影响展示，回落成 key
                }
            }
            sb.append(name).append(" ×").append(entry.getValue());
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
