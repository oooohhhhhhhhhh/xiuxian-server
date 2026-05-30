package com.mtxgdn.game.entity;

public class Skill {

    private long id;
    private String name;
    private String description;
    private int requiredRealm;
    private long learnCostGold;
    private long learnCostSpiritStones;
    private int damage;
    private int mpCost;
    private int cooldownSeconds;
    private int skillType;
    private int healAmount;
    private int maxLevel;
    private int level;
    private int proficiency;

    public Skill() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getRequiredRealm() {
        return requiredRealm;
    }

    public void setRequiredRealm(int requiredRealm) {
        this.requiredRealm = requiredRealm;
    }

    public long getLearnCostGold() {
        return learnCostGold;
    }

    public void setLearnCostGold(long learnCostGold) {
        this.learnCostGold = learnCostGold;
    }

    public long getLearnCostSpiritStones() {
        return learnCostSpiritStones;
    }

    public void setLearnCostSpiritStones(long learnCostSpiritStones) {
        this.learnCostSpiritStones = learnCostSpiritStones;
    }

    public int getDamage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = damage;
    }

    public int getMpCost() {
        return mpCost;
    }

    public void setMpCost(int mpCost) {
        this.mpCost = mpCost;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    public int getSkillType() {
        return skillType;
    }

    public void setSkillType(int skillType) {
        this.skillType = skillType;
    }

    public int getHealAmount() {
        return healAmount;
    }

    public void setHealAmount(int healAmount) {
        this.healAmount = healAmount;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getProficiency() {
        return proficiency;
    }

    public void setProficiency(int proficiency) {
        this.proficiency = proficiency;
    }

    public boolean isHealSkill() {
        return skillType == 1;
    }

    public boolean isAttackSkill() {
        return skillType == 0;
    }
}
