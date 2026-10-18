package com.mtxgdn.game.entity;

import com.mtxgdn.i18n.I18n;

public class RealmConfig {

    private int id;
    private String name;
    private int subRealm;
    private String description;

    /**
     * 名称与描述的词条 key（如 {@code realm.1.0.name}）。
     *
     * <p>为空或语言包里查不到时回落到 {@link #name} / {@link #description}
     * 里的中文字面量——这样老配置文件（只有 name/description）无需任何改动
     * 也能继续跑，只是不会跟着语言切换。</p>
     */
    private String nameKey;
    private String descKey;

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

    /**
     * 本地化后的境界名。
     *
     * <p>这个值<b>只用于展示</b>——玩家的境界在库里存的是整数 {@code realm}，
     * 名称是读配置时现算出来的，所以按请求语言返回不同文字不会污染任何数据。</p>
     */
    public String getName() {
        return nameKey == null || nameKey.isBlank() ? name : I18n.tOr(nameKey, name);
    }

    public String getNameKey() {
        return nameKey;
    }

    public void setNameKey(String nameKey) {
        this.nameKey = nameKey;
    }

    /** 原始中文名，不经过本地化。供需要稳定值的场合（如写日志、做 key 匹配）使用。 */
    public String getRawName() {
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

    /** 本地化后的境界描述。同样只用于展示。 */
    public String getDescription() {
        return descKey == null || descKey.isBlank() ? description : I18n.tOr(descKey, description);
    }

    public String getDescKey() {
        return descKey;
    }

    public void setDescKey(String descKey) {
        this.descKey = descKey;
    }

    /** 原始中文描述，不经过本地化。 */
    public String getRawDescription() {
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
