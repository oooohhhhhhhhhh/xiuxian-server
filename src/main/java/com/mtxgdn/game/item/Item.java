package com.mtxgdn.game.item;

import com.mtxgdn.util.LangManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Item {

    private String fullKey;
    private String nameKey;
    private String descKey;
    private ItemType type;
    private ItemRarity rarity;
    private List<ItemEffect> effects;
    private int maxStack;
    private int price;
    private boolean tradeable;
    private int requiredRealm;

    protected Item(String namespace, String key, ItemType type, ItemRarity rarity,
                   int maxStack, int price, boolean tradeable, int requiredRealm,
                   ItemEffect... effects) {
        this.fullKey = namespace + ":" + key;
        this.nameKey = "item." + key + ".name";
        this.type = type;
        this.rarity = rarity;
        this.descKey = "item." + key + ".desc";
        this.effects = Collections.unmodifiableList(Arrays.asList(effects));
        this.maxStack = maxStack;
        this.price = price;
        this.tradeable = tradeable;
        this.requiredRealm = requiredRealm;
        ItemRegistry.register(this);
    }

    public String getFullKey() {
        return fullKey;
    }

    public String getNamespace() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(0, idx) : fullKey;
    }

    public String getKey() {
        int idx = fullKey.indexOf(':');
        return idx > 0 ? fullKey.substring(idx + 1) : fullKey;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescKey() {
        return descKey;
    }

    public String getName() {
        return LangManager.get(nameKey);
    }

    public ItemType getType() {
        return type;
    }

    public ItemRarity getRarity() {
        return rarity;
    }

    public String getDescription() {
        return LangManager.get(descKey);
    }

    public List<ItemEffect> getEffects() {
        return effects;
    }

    public int getMaxStack() {
        return maxStack;
    }

    public int getPrice() {
        return price;
    }

    public boolean isTradeable() {
        return tradeable;
    }

    public int getRequiredRealm() {
        return requiredRealm;
    }

    public String use(long userId) {
        return "使用: " + fullKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Item other)) return false;
        return fullKey.equals(other.fullKey);
    }

    @Override
    public int hashCode() {
        return fullKey.hashCode();
    }

    @Override
    public String toString() {
        return fullKey;
    }
}
