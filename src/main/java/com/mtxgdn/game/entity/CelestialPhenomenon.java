package com.mtxgdn.game.entity;

public enum CelestialPhenomenon {
    PURPLE_QI("紫气东来", "晨曦紫气弥漫天地，修炼事半功倍", 1.30, 1.00, 1.00, 1.00),
    STARLIGHT("星辰耀天", "满天星辰之力倾泻而下，游历收获颇丰", 1.00, 1.20, 1.20, 1.00),
    BLOOD_MOON("血月当空", "血色月光笼罩大地，战斗之意空前高涨", 1.00, 1.00, 1.30, 1.00),
    SPIRIT_TIDE("灵潮涌动", "天地灵潮汹涌而来，灵石遍地", 1.00, 1.00, 1.00, 1.50),
    WITHER_BLOOM("枯荣交替", "万物枯荣在一念之间，恢复之力大增", 1.00, 1.00, 1.00, 1.00),
    TRANQUIL("万籁俱寂", "天地归于沉寂，一切收益微增", 1.05, 1.05, 1.05, 1.05);

    private final String displayName;
    private final String description;
    private final double cultivationMultiplier;
    private final double explorationMultiplier;
    private final double combatMultiplier;
    private final double spiritStoneMultiplier;

    CelestialPhenomenon(String displayName, String description,
                        double cultivationMultiplier, double explorationMultiplier,
                        double combatMultiplier, double spiritStoneMultiplier) {
        this.displayName = displayName;
        this.description = description;
        this.cultivationMultiplier = cultivationMultiplier;
        this.explorationMultiplier = explorationMultiplier;
        this.combatMultiplier = combatMultiplier;
        this.spiritStoneMultiplier = spiritStoneMultiplier;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public double getCultivationMultiplier() { return cultivationMultiplier; }
    public double getExplorationMultiplier() { return explorationMultiplier; }
    public double getCombatMultiplier() { return combatMultiplier; }
    public double getSpiritStoneMultiplier() { return spiritStoneMultiplier; }

    public long applyCultivation(long base) { return (long)(base * cultivationMultiplier); }
    public long applyExploration(long base) { return (long)(base * explorationMultiplier); }
    public long applyCombat(long base) { return (long)(base * combatMultiplier); }
    public long applySpiritStone(long base) { return (long)(base * spiritStoneMultiplier); }
}
