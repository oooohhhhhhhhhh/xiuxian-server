package com.mtxgdn.game.entity;

public class Recipe {

    public enum Category { PILL, EQUIPMENT, CONSUMABLE }

    private long id;
    private String name;
    private String description;
    private Category category;
    private int requiredRealm;
    private String resultItemKey;
    private int resultQuantity;
    private String material1Key;
    private int material1Count;
    private String material2Key;
    private int material2Count;
    private String material3Key;
    private int material3Count;
    private long costGold;
    private long costSpiritStones;
    private double successRate;
    private long minExpGain;
    private long maxExpGain;

    public Recipe() {
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public int getRequiredRealm() { return requiredRealm; }
    public void setRequiredRealm(int requiredRealm) { this.requiredRealm = requiredRealm; }

    public String getResultItemKey() { return resultItemKey; }
    public void setResultItemKey(String resultItemKey) { this.resultItemKey = resultItemKey; }

    public int getResultQuantity() { return resultQuantity; }
    public void setResultQuantity(int resultQuantity) { this.resultQuantity = resultQuantity; }

    public String getMaterial1Key() { return material1Key; }
    public void setMaterial1Key(String material1Key) { this.material1Key = material1Key; }

    public int getMaterial1Count() { return material1Count; }
    public void setMaterial1Count(int material1Count) { this.material1Count = material1Count; }

    public String getMaterial2Key() { return material2Key; }
    public void setMaterial2Key(String material2Key) { this.material2Key = material2Key; }

    public int getMaterial2Count() { return material2Count; }
    public void setMaterial2Count(int material2Count) { this.material2Count = material2Count; }

    public String getMaterial3Key() { return material3Key; }
    public void setMaterial3Key(String material3Key) { this.material3Key = material3Key; }

    public int getMaterial3Count() { return material3Count; }
    public void setMaterial3Count(int material3Count) { this.material3Count = material3Count; }

    public long getCostGold() { return costGold; }
    public void setCostGold(long costGold) { this.costGold = costGold; }

    public long getCostSpiritStones() { return costSpiritStones; }
    public void setCostSpiritStones(long costSpiritStones) { this.costSpiritStones = costSpiritStones; }

    public double getSuccessRate() { return successRate; }
    public void setSuccessRate(double successRate) { this.successRate = successRate; }

    public long getMinExpGain() { return minExpGain; }
    public void setMinExpGain(long minExpGain) { this.minExpGain = minExpGain; }

    public long getMaxExpGain() { return maxExpGain; }
    public void setMaxExpGain(long maxExpGain) { this.maxExpGain = maxExpGain; }
}
