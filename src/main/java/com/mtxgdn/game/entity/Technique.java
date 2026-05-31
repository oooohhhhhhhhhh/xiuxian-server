package com.mtxgdn.game.entity;

public class Technique {

    public enum Type { ATTACK, DEFENSE, CULTIVATION, UTILITY }

    private long id;
    private String name;
    private String description;
    private int requiredRealm;
    private long learnCostGold;
    private long learnCostSpiritStones;
    private int upgradeBaseCostGold;
    private int upgradeBaseCostSpiritStones;
    private Type type;
    private int maxLevel;
    private int hpBonus;
    private int mpBonus;
    private int attackBonus;
    private int defenseBonus;
    private int speedBonus;
    private int spiritBonus;
    private double cultivationSpeedBonus;
    private double expBonus;
    private double combatDamageBonus;
    private double damageReduction;
    private int level;
    private int proficiency;
    private boolean isEquipped;

    public Technique() {
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getRequiredRealm() { return requiredRealm; }
    public void setRequiredRealm(int requiredRealm) { this.requiredRealm = requiredRealm; }

    public long getLearnCostGold() { return learnCostGold; }
    public void setLearnCostGold(long learnCostGold) { this.learnCostGold = learnCostGold; }

    public long getLearnCostSpiritStones() { return learnCostSpiritStones; }
    public void setLearnCostSpiritStones(long learnCostSpiritStones) { this.learnCostSpiritStones = learnCostSpiritStones; }

    public int getUpgradeBaseCostGold() { return upgradeBaseCostGold; }
    public void setUpgradeBaseCostGold(int upgradeBaseCostGold) { this.upgradeBaseCostGold = upgradeBaseCostGold; }

    public int getUpgradeBaseCostSpiritStones() { return upgradeBaseCostSpiritStones; }
    public void setUpgradeBaseCostSpiritStones(int upgradeBaseCostSpiritStones) { this.upgradeBaseCostSpiritStones = upgradeBaseCostSpiritStones; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public int getMaxLevel() { return maxLevel; }
    public void setMaxLevel(int maxLevel) { this.maxLevel = maxLevel; }

    public int getHpBonus() { return hpBonus; }
    public void setHpBonus(int hpBonus) { this.hpBonus = hpBonus; }
    public int getMpBonus() { return mpBonus; }
    public void setMpBonus(int mpBonus) { this.mpBonus = mpBonus; }
    public int getAttackBonus() { return attackBonus; }
    public void setAttackBonus(int attackBonus) { this.attackBonus = attackBonus; }
    public int getDefenseBonus() { return defenseBonus; }
    public void setDefenseBonus(int defenseBonus) { this.defenseBonus = defenseBonus; }
    public int getSpeedBonus() { return speedBonus; }
    public void setSpeedBonus(int speedBonus) { this.speedBonus = speedBonus; }
    public int getSpiritBonus() { return spiritBonus; }
    public void setSpiritBonus(int spiritBonus) { this.spiritBonus = spiritBonus; }

    public double getCultivationSpeedBonus() { return cultivationSpeedBonus; }
    public void setCultivationSpeedBonus(double cultivationSpeedBonus) { this.cultivationSpeedBonus = cultivationSpeedBonus; }
    public double getExpBonus() { return expBonus; }
    public void setExpBonus(double expBonus) { this.expBonus = expBonus; }
    public double getCombatDamageBonus() { return combatDamageBonus; }
    public void setCombatDamageBonus(double combatDamageBonus) { this.combatDamageBonus = combatDamageBonus; }
    public double getDamageReduction() { return damageReduction; }
    public void setDamageReduction(double damageReduction) { this.damageReduction = damageReduction; }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public int getProficiency() { return proficiency; }
    public void setProficiency(int proficiency) { this.proficiency = proficiency; }

    public boolean isEquipped() { return isEquipped; }
    public void setEquipped(boolean equipped) { isEquipped = equipped; }

    public int getScaledHpBonus() { return hpBonus + (int)(hpBonus * (level - 1) * 0.12); }
    public int getScaledMpBonus() { return mpBonus + (int)(mpBonus * (level - 1) * 0.12); }
    public int getScaledAttackBonus() { return attackBonus + (int)(attackBonus * (level - 1) * 0.12); }
    public int getScaledDefenseBonus() { return defenseBonus + (int)(defenseBonus * (level - 1) * 0.12); }
    public int getScaledSpeedBonus() { return speedBonus + (int)(speedBonus * (level - 1) * 0.12); }
    public int getScaledSpiritBonus() { return spiritBonus + (int)(spiritBonus * (level - 1) * 0.12); }
    public double getScaledCultivationSpeedBonus() { return cultivationSpeedBonus * (1 + (level - 1) * 0.08); }
    public double getScaledExpBonus() { return expBonus * (1 + (level - 1) * 0.08); }
    public double getScaledCombatDamageBonus() { return combatDamageBonus * (1 + (level - 1) * 0.08); }
    public double getScaledDamageReduction() { return damageReduction * (1 + (level - 1) * 0.08); }
}
