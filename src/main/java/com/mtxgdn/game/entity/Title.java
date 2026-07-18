package com.mtxgdn.game.entity;

public class Title {

    public enum Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    private String key;
    private String name;
    private String description;
    private Rarity rarity;
    private int attackBonus;
    private int defenseBonus;
    private int hpBonus;
    private int mpBonus;
    private int speedBonus;
    private int spiritBonus;
    private double cultivationSpeedBonus;
    private double expBonus;
    private double dropRateBonus;
    private int requiredRealm;
    private double damageBonus;
    private double damageReduction;

    public Title() {}

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Rarity getRarity() { return rarity; }
    public void setRarity(Rarity rarity) { this.rarity = rarity; }
    public int getAttackBonus() { return attackBonus; }
    public void setAttackBonus(int attackBonus) { this.attackBonus = attackBonus; }
    public int getDefenseBonus() { return defenseBonus; }
    public void setDefenseBonus(int defenseBonus) { this.defenseBonus = defenseBonus; }
    public int getHpBonus() { return hpBonus; }
    public void setHpBonus(int hpBonus) { this.hpBonus = hpBonus; }
    public int getMpBonus() { return mpBonus; }
    public void setMpBonus(int mpBonus) { this.mpBonus = mpBonus; }
    public int getSpeedBonus() { return speedBonus; }
    public void setSpeedBonus(int speedBonus) { this.speedBonus = speedBonus; }
    public int getSpiritBonus() { return spiritBonus; }
    public void setSpiritBonus(int spiritBonus) { this.spiritBonus = spiritBonus; }
    public double getCultivationSpeedBonus() { return cultivationSpeedBonus; }
    public void setCultivationSpeedBonus(double cultivationSpeedBonus) { this.cultivationSpeedBonus = cultivationSpeedBonus; }
    public double getExpBonus() { return expBonus; }
    public void setExpBonus(double expBonus) { this.expBonus = expBonus; }
    public double getDropRateBonus() { return dropRateBonus; }
    public void setDropRateBonus(double dropRateBonus) { this.dropRateBonus = dropRateBonus; }
    public int getRequiredRealm() { return requiredRealm; }
    public void setRequiredRealm(int requiredRealm) { this.requiredRealm = requiredRealm; }

    public double getExperienceBonus() { return expBonus; }
    public double getDamageBonus() { return damageBonus; }
    public void setDamageBonus(double damageBonus) { this.damageBonus = damageBonus; }
    public double getDamageReduction() { return damageReduction; }
    public void setDamageReduction(double damageReduction) { this.damageReduction = damageReduction; }

    public String getRarityLabel() {
        return switch (rarity) {
            case COMMON -> "普通";
            case UNCOMMON -> "稀有";
            case RARE -> "珍贵";
            case EPIC -> "史诗";
            case LEGENDARY -> "传说";
        };
    }

    public String getRarityColor() {
        return switch (rarity) {
            case COMMON -> "§7";
            case UNCOMMON -> "§a";
            case RARE -> "§9";
            case EPIC -> "§5";
            case LEGENDARY -> "§6";
        };
    }
}
