package com.mtxgdn.game.entity;

public class RealmConfig {

    private int id;
    private String name;
    private int subRealm;
    private String description;
    private long requiredExp;
    private long requiredSpiritStones;
    private int hpBonus;
    private int mpBonus;
    private int attackBonus;
    private int defenseBonus;
    private int speedBonus;
    private int spiritBonus;
    private int unlockSkillId;

    public RealmConfig() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSubRealm() {
        return subRealm;
    }

    public void setSubRealm(int subRealm) {
        this.subRealm = subRealm;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getRequiredExp() {
        return requiredExp;
    }

    public void setRequiredExp(long requiredExp) {
        this.requiredExp = requiredExp;
    }

    public long getRequiredSpiritStones() {
        return requiredSpiritStones;
    }

    public void setRequiredSpiritStones(long requiredSpiritStones) {
        this.requiredSpiritStones = requiredSpiritStones;
    }

    public int getHpBonus() {
        return hpBonus;
    }

    public void setHpBonus(int hpBonus) {
        this.hpBonus = hpBonus;
    }

    public int getMpBonus() {
        return mpBonus;
    }

    public void setMpBonus(int mpBonus) {
        this.mpBonus = mpBonus;
    }

    public int getAttackBonus() {
        return attackBonus;
    }

    public void setAttackBonus(int attackBonus) {
        this.attackBonus = attackBonus;
    }

    public int getDefenseBonus() {
        return defenseBonus;
    }

    public void setDefenseBonus(int defenseBonus) {
        this.defenseBonus = defenseBonus;
    }

    public int getSpeedBonus() {
        return speedBonus;
    }

    public void setSpeedBonus(int speedBonus) {
        this.speedBonus = speedBonus;
    }

    public int getSpiritBonus() {
        return spiritBonus;
    }

    public void setSpiritBonus(int spiritBonus) {
        this.spiritBonus = spiritBonus;
    }

    public int getUnlockSkillId() {
        return unlockSkillId;
    }

    public void setUnlockSkillId(int unlockSkillId) {
        this.unlockSkillId = unlockSkillId;
    }

    public boolean isMaxRealm() {
        return id == 10 && subRealm == 0;
    }

    public String getFullName() {
        return name;
    }

    @Override
    public String toString() {
        return getFullName();
    }
}
