package com.mtxgdn.game.entity;

public class Cave {

    private long id;
    private long playerId;
    private String name;
    private int level;
    private long spiritEnergy;
    private int maxSpiritEnergy;
    private int cultivationBonus;
    private int storageBonus;
    private long lastCollectTime;
    private String createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getPlayerId() { return playerId; }
    public void setPlayerId(long playerId) { this.playerId = playerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public long getSpiritEnergy() { return spiritEnergy; }
    public void setSpiritEnergy(long spiritEnergy) { this.spiritEnergy = spiritEnergy; }

    public int getMaxSpiritEnergy() { return maxSpiritEnergy; }
    public void setMaxSpiritEnergy(int maxSpiritEnergy) { this.maxSpiritEnergy = maxSpiritEnergy; }

    public int getCultivationBonus() { return cultivationBonus; }
    public void setCultivationBonus(int cultivationBonus) { this.cultivationBonus = cultivationBonus; }

    public int getStorageBonus() { return storageBonus; }
    public void setStorageBonus(int storageBonus) { this.storageBonus = storageBonus; }

    public long getLastCollectTime() { return lastCollectTime; }
    public void setLastCollectTime(long lastCollectTime) { this.lastCollectTime = lastCollectTime; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static final int MIN_REALM_CREATE = 3;
    public static final long CREATE_COST_SPIRIT_STONES = 300;
    public static final int MAX_LEVEL = 10;

    public static int getMaxSpiritEnergyForLevel(int level) {
        return 1000 + (level - 1) * 500;
    }

    public static int getCultivationBonusForLevel(int level) {
        return level * 5;
    }

    public static int getStorageBonusForLevel(int level) {
        return level * 10;
    }

    public static long getLevelUpCost(int currentLevel) {
        return currentLevel * 500;
    }
}