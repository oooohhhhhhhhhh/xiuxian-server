package com.mtxgdn.game.entity;

public class PlayerInfo {

    private long id;
    private long userId;
    private String name;
    private SpiritualRoot spiritualRoot;
    private int level;
    private long experience;
    private int realm;
    private String realmName;
    private int subRealm;
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int attack;
    private int defense;
    private int speed;
    private int spirit;
    private long gold;
    private int cultivationProgress;
    private boolean isCultivating;
    private long cultivationStartTime;
    private long lastSecretRealmTime;
    private long lastExplorationTime;
    private int tutorialStep;
    private int tutorialTips;
    private String createdAt;
    private String updatedAt;

    public PlayerInfo() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SpiritualRoot getSpiritualRoot() {
        return spiritualRoot;
    }

    public void setSpiritualRoot(SpiritualRoot spiritualRoot) {
        this.spiritualRoot = spiritualRoot;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getExperience() {
        return experience;
    }

    public void setExperience(long experience) {
        this.experience = experience;
    }

    public int getRealm() {
        return realm;
    }

    public void setRealm(int realm) {
        this.realm = realm;
    }

    public String getRealmName() {
        return realmName;
    }

    public void setRealmName(String realmName) {
        this.realmName = realmName;
    }

    public int getSubRealm() {
        return subRealm;
    }

    public void setSubRealm(int subRealm) {
        this.subRealm = subRealm;
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public int getMaxHp() {
        return maxHp;
    }

    public void setMaxHp(int maxHp) {
        this.maxHp = maxHp;
    }

    public int getMp() {
        return mp;
    }

    public void setMp(int mp) {
        this.mp = mp;
    }

    public int getMaxMp() {
        return maxMp;
    }

    public void setMaxMp(int maxMp) {
        this.maxMp = maxMp;
    }

    public int getAttack() {
        return attack;
    }

    public void setAttack(int attack) {
        this.attack = attack;
    }

    public int getDefense() {
        return defense;
    }

    public void setDefense(int defense) {
        this.defense = defense;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public int getSpirit() {
        return spirit;
    }

    public void setSpirit(int spirit) {
        this.spirit = spirit;
    }

    public long getGold() {
        return gold;
    }

    public void setGold(long gold) {
        this.gold = gold;
    }

    public int getCultivationProgress() {
        return cultivationProgress;
    }

    public void setCultivationProgress(int cultivationProgress) {
        this.cultivationProgress = cultivationProgress;
    }

    public boolean isCultivating() {
        return isCultivating;
    }

    public void setCultivating(boolean cultivating) {
        isCultivating = cultivating;
    }

    public long getCultivationStartTime() {
        return cultivationStartTime;
    }

    public void setCultivationStartTime(long cultivationStartTime) {
        this.cultivationStartTime = cultivationStartTime;
    }

    public long getLastSecretRealmTime() {
        return lastSecretRealmTime;
    }

    public void setLastSecretRealmTime(long lastSecretRealmTime) {
        this.lastSecretRealmTime = lastSecretRealmTime;
    }

    public long getLastExplorationTime() {
        return lastExplorationTime;
    }

    public void setLastExplorationTime(long lastExplorationTime) {
        this.lastExplorationTime = lastExplorationTime;
    }

    public int getTutorialStep() { return tutorialStep; }
    public void setTutorialStep(int tutorialStep) { this.tutorialStep = tutorialStep; }

    public int getTutorialTips() { return tutorialTips; }
    public void setTutorialTips(int tutorialTips) { this.tutorialTips = tutorialTips; }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
