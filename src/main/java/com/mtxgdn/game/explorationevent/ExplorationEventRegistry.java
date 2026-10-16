package com.mtxgdn.game.explorationevent;

import com.mtxgdn.util.GameLogger;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExplorationEventRegistry {

    private static final GameLogger LOG = GameLogger.getLogger(ExplorationEventRegistry.class);
    private static final Map<String, ExplorationEvent> events = new LinkedHashMap<>();

    private ExplorationEventRegistry() {
    }

    public static void register(ExplorationEvent event) {
        String fullKey = event.getFullKey();
        if (events.containsKey(fullKey)) {
            ExplorationEvent old = events.get(fullKey);
            LOG.warn("事件覆盖: " + fullKey + " (" + old + " -> " + event + ")");
        }
        events.put(fullKey, event);
        LOG.debug("注册事件: " + fullKey);
    }

    /** 取消注册一个探索事件。 */
    public static void unregister(ExplorationEvent event) {
        if (event == null) return;
        String fullKey = event.getFullKey();
        if (events.remove(fullKey) != null) {
            LOG.debug("取消注册事件: " + fullKey);
        }
    }

    public static ExplorationEvent get(String fullKey) {
        return events.get(fullKey);
    }

    /** 按事件 key（不含命名空间）查找；用于地界权重偏置这类只持有 key 的场景。 */
    public static ExplorationEvent getByKey(String key) {
        if (key == null) return null;
        for (ExplorationEvent event : events.values()) {
            if (event.getKey().equals(key)) {
                return event;
            }
        }
        return null;
    }

    public static ExplorationEvent randomByWeight(java.util.Random random) {
        return randomByWeight(random, java.util.Map.of());
    }

    /**
     * 按权重随机抽取事件，并按地界偏置调整权重。
     *
     * <p>{@code bias} 为「事件 key → 权重倍率」，未列出的 key 倍率视为 1.0。
     * 这让"落霞山产落霞草""万妖谷妖兽聚集"这类地点描述真正作用于事件抽取。</p>
     */
    public static ExplorationEvent randomByWeight(java.util.Random random, java.util.Map<String, Double> bias) {
        if (events.isEmpty()) {
            return null;
        }
        double totalWeight = 0;
        for (ExplorationEvent event : events.values()) {
            totalWeight += biasedWeight(event, bias);
        }
        if (totalWeight <= 0) {
            return events.values().iterator().next();
        }
        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (ExplorationEvent event : events.values()) {
            cumulative += biasedWeight(event, bias);
            if (roll < cumulative) {
                return event;
            }
        }
        return events.values().iterator().next();
    }

    private static double biasedWeight(ExplorationEvent event, java.util.Map<String, Double> bias) {
        double weight = event.getWeight();
        if (bias == null || bias.isEmpty()) {
            return weight;
        }
        Double multiplier = bias.get(event.getKey());
        return multiplier != null ? weight * multiplier : weight;
    }

    public static Collection<ExplorationEvent> getAll() {
        return Collections.unmodifiableCollection(events.values());
    }

    public static int count() {
        return events.size();
    }

    public static boolean contains(String fullKey) {
        return events.containsKey(fullKey);
    }

    public static void clear() {
        events.clear();
    }
}
