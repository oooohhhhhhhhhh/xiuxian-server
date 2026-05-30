package com.mtxgdn.game.item;

import com.mtxgdn.util.GameLogger;
import com.mtxgdn.util.LangManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ItemRegistry {

    private static final GameLogger LOG = GameLogger.getLogger(ItemRegistry.class);
    private static final Map<String, Item> items = new LinkedHashMap<>();

    private ItemRegistry() {
    }

    public static void register(Item item) {
        String fullKey = item.getFullKey();
        if (items.containsKey(fullKey)) {
            Item old = items.get(fullKey);
            LOG.warn("物品覆盖: " + fullKey + " (" + old + " -> " + item + ")");
        }
        items.put(fullKey, item);
        LOG.debug("注册物品: " + fullKey);
    }

    public static Item get(String fullKey) {
        return items.get(fullKey);
    }

    public static Item resolve(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        Item byKey = items.get(input);
        if (byKey != null) {
            return byKey;
        }

        return findByTranslatedName(input);
    }

    public static Item findByTranslatedName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String lower = name.toLowerCase();
        for (Item item : items.values()) {
            String translatedName = LangManager.get(item.getNameKey());
            if (translatedName != null && translatedName.toLowerCase().contains(lower)) {
                return item;
            }
            if (item.getNameKey().toLowerCase().contains(lower)) {
                return item;
            }
        }
        for (Item item : items.values()) {
            String translatedName = LangManager.get(item.getNameKey());
            if (translatedName != null && translatedName.equals(name)) {
                return item;
            }
        }
        return null;
    }

    public static List<Item> getByType(ItemType type) {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.getType() == type) result.add(item);
        }
        return result;
    }

    public static List<Item> getByRarity(ItemRarity rarity) {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (item.getRarity() == rarity) result.add(item);
        }
        return result;
    }

    public static Collection<Item> getAll() {
        return Collections.unmodifiableCollection(items.values());
    }

    public static int count() {
        return items.size();
    }

    public static boolean contains(String fullKey) {
        return items.containsKey(fullKey);
    }

    public static void clear() {
        items.clear();
    }
}
