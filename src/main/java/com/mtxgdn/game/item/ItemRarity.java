package com.mtxgdn.game.item;

public enum ItemRarity {
    COMMON("凡品", 1),
    UNCOMMON("良品", 2),
    RARE("珍品", 3),
    EPIC("仙品", 4),
    LEGENDARY("神品", 5),
    MYTHIC("圣品", 6);

    private final String displayName;
    private final int tier;

    ItemRarity(String displayName, int tier) {
        this.displayName = displayName;
        this.tier = tier;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getTier() {
        return tier;
    }
}
