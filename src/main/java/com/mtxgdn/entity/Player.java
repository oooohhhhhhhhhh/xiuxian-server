package com.mtxgdn.entity;

import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.ArrayList;
import java.util.List;

public class Player {

    private Long id;
    private Long userId;
    private String name;
    private SpiritualRoot spiritualRoot;
    private int level;
    private long experience;
    private int realm;
    private int subRealm;
    private String currentTitle;
    private List<String> ownedTitles = new ArrayList<>();
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
    private long lastOfflineTime;
    /**
     * 最近活跃时间（毫秒，惰性节流写入）。
     * <p>与 {@link #lastOfflineTime} 的区别：后者只有 WebSocket 断线时才写，
     * 覆盖不到 QQ / REST / Minecraft 通道；本字段由 {@code PlayerService} 在读取玩家时
     * 按小时节流刷新，是所有通道通用的"最后出现时间"，供宗门宗主弃坑判定使用。</p>
     */
    private long lastActiveTime;
    private String battleStrategy = "balanced";
    private long currentLocationId = 1;
    private long lastTravelTime;
    private long lastBreakthroughTime;
    /**
     * 突破失败增益（pity）：连续渡劫失败的累计次数。
     *
     * <p>存"次数"而不是"加成值"，这样调整 {@code breakthrough.pity_bonus} 后老数据
     * 自动跟着变，不需要数据迁移。成功后清零。</p>
     */
    private int breakthroughPity;
    /**
     * 凝实（极境）层数，0..3。
     *
     * <p>修为圆满时可以不去渡劫，改为压制修为淬体：每层提升基础属性，
     * 但渡劫成功率按层数打折、失败损失也被放大。渡劫成功后清零。</p>
     */
    private int consolidation;
    /** 玩法计数（称号获取用） */
    private int secretRealmCount;
    private int pvpWins;
    private int craftCount;
    private String createdAt;
    private String updatedAt;

    public Player() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
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

    public int getSubRealm() {
        return subRealm;
    }

    public void setSubRealm(int subRealm) {
        this.subRealm = subRealm;
    }

    /**
     * 境界序位：把 (大境界, 小境界) 压成一个单调递增的整数（每大境界 3 小境界）。
     *
     * <p>用于<b>跨玩家比较实力</b>的场合（宗主继位、世界 Boss 境界门槛）。
     * 刻意不用 {@link #getLevel()}：本项目中 {@code level} 并不随修炼推进，
     * 只有建档与后台改库会写，拿它比较会得到一批并列的 1 级。</p>
     */
    public int getRealmStage() {
        return realm * 3 + subRealm;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public void setCurrentTitle(String currentTitle) {
        this.currentTitle = currentTitle;
    }

    public List<String> getOwnedTitles() {
        return ownedTitles;
    }

    public void setOwnedTitles(List<String> ownedTitles) {
        this.ownedTitles = ownedTitles;
    }

    public void addOwnedTitle(String titleKey) {
        if (!ownedTitles.contains(titleKey)) {
            ownedTitles.add(titleKey);
        }
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

    public long getLastOfflineTime() { return lastOfflineTime; }
    public void setLastOfflineTime(long lastOfflineTime) { this.lastOfflineTime = lastOfflineTime; }

    public long getLastActiveTime() { return lastActiveTime; }
    public void setLastActiveTime(long lastActiveTime) { this.lastActiveTime = lastActiveTime; }

    public String getBattleStrategy() { return battleStrategy; }
    public void setBattleStrategy(String battleStrategy) { this.battleStrategy = battleStrategy; }

    public long getCurrentLocationId() { return currentLocationId; }
    public void setCurrentLocationId(long currentLocationId) { this.currentLocationId = currentLocationId; }

    public long getLastTravelTime() { return lastTravelTime; }
    public void setLastTravelTime(long lastTravelTime) { this.lastTravelTime = lastTravelTime; }

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

    public long getLastBreakthroughTime() {
        return lastBreakthroughTime;
    }

    public void setLastBreakthroughTime(long lastBreakthroughTime) {
        this.lastBreakthroughTime = lastBreakthroughTime;
    }

    public int getBreakthroughPity() { return breakthroughPity; }
    public void setBreakthroughPity(int breakthroughPity) { this.breakthroughPity = breakthroughPity; }

    public int getConsolidation() { return consolidation; }
    public void setConsolidation(int consolidation) { this.consolidation = consolidation; }

    public int getSecretRealmCount() { return secretRealmCount; }
    public void setSecretRealmCount(int secretRealmCount) { this.secretRealmCount = secretRealmCount; }
    public int getPvpWins() { return pvpWins; }
    public void setPvpWins(int pvpWins) { this.pvpWins = pvpWins; }
    public int getCraftCount() { return craftCount; }
    public void setCraftCount(int craftCount) { this.craftCount = craftCount; }
}
