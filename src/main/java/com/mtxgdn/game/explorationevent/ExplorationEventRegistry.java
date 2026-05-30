package com.mtxgdn.game.explorationevent;

import com.mtxgdn.util.GameLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    public static ExplorationEvent get(String fullKey) {
        return events.get(fullKey);
    }

    public static ExplorationEvent randomByWeight(java.util.Random random) {
        if (events.isEmpty()) {
            return null;
        }
        int totalWeight = 0;
        for (ExplorationEvent event : events.values()) {
            totalWeight += event.getWeight();
        }
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (ExplorationEvent event : events.values()) {
            cumulative += event.getWeight();
            if (roll < cumulative) {
                return event;
            }
        }
        return events.values().iterator().next();
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
