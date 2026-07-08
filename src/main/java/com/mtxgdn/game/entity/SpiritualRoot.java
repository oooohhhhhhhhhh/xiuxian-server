package com.mtxgdn.game.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public enum SpiritualRoot {

    TRUE_FIVE_ELEMENTS("正·五行灵根", Tier.PERFECT, "天地间最完美的灵根，五元素100%亲和，万法皆通",
            0.25, 0.25, 0.25, 0.25, 0.25, 0.10,
            SpecialEffect.FIVE_ELEMENTS_AFFINITY, 1.0),

    TAIYI_GOLDEN("太乙金灵根", Tier.HEAVENLY, "金系天灵根，锐金之气贯体，攻伐无双",
            0.15, 0, 0, 0.05, 0, 0,
            SpecialEffect.CRIT_CHANCE, 0.05),
    QINGDI_WOOD("青帝木灵根", Tier.HEAVENLY, "木系天灵根，生生不息，枯木亦可逢春",
            0, 0.15, 0, 0, 0, 0.05,
            SpecialEffect.REGENERATION, 0.03),
    XUANMING_WATER("玄冥水灵根", Tier.HEAVENLY, "水系天灵根，上善若水，法力如海",
            0, 0, 0.15, 0, 0.05, 0,
            SpecialEffect.MP_COST_REDUCTION, 0.20),
    LIHUO_FIRE("离火灵根", Tier.HEAVENLY, "火系天灵根，焚尽八荒，威势滔天",
            0.10, 0, 0, 0.05, 0, 0.05,
            SpecialEffect.DAMAGE_BOOST, 0.10),
    HOUTU_EARTH("厚土灵根", Tier.HEAVENLY, "土系天灵根，不动如山，坚不可摧",
            0, 0.10, 0, 0, 0.15, 0,
            SpecialEffect.DAMAGE_REDUCTION, 0.15),

    XUNFENG_WIND("巽风灵根", Tier.VARIANT, "异灵根，来去如风，踪迹难寻",
            0.05, 0, 0, 0, 0, 0.15,
            SpecialEffect.EXPLORATION_CD, 0.30),
    JINGLEI_THUNDER("惊雷灵根", Tier.VARIANT, "异灵根，天威浩荡，一击惊雷",
            0.05, 0, 0, 0.15, 0, 0,
            SpecialEffect.CRIT_DAMAGE, 0.30),
    XUANBING_ICE("玄冰灵根", Tier.VARIANT, "异灵根，冰封万里，寒彻骨髓",
            0, 0, 0.10, 0, 0.10, 0,
            SpecialEffect.SKILL_DAMAGE, 0.15),

    GOLDEN_FIRE("金火灵根", Tier.DUAL, "金火双灵根，真金不怕烈火炼",
            0.08, 0, 0, 0.04, 0, 0,
            SpecialEffect.MONSTER_EXP, 0.25),
    WOODEN_WATER("木水灵根", Tier.DUAL, "木水双灵根，水木清华，滋养道基",
            0, 0.08, 0.08, 0, 0, 0,
            SpecialEffect.CULTIVATION_EFFICIENCY, 0.15),
    EARTHEN_GOLD("土金灵根", Tier.DUAL, "土金双灵根，金石为开，商道亨通",
            0.04, 0, 0, 0, 0.08, 0,
            SpecialEffect.TRADE_FEE_HALF, 0.50),

    FIRE_WOOD_EARTH("火土木灵根", Tier.TRIPLE, "火土木三灵根，生生不息，厚德载物",
            0.02, 0.02, 0, 0, 0.02, 0,
            SpecialEffect.SPIRIT_STONE_DROP, 0.20),
    GOLDEN_WATER_WOOD("金水木灵根", Tier.TRIPLE, "金水木三灵根，道心通明，悟性过人",
            0, 0, 0.02, 0.02, 0, 0.02,
            SpecialEffect.PROFICIENCY_BOOST, 0.30),

    FOUR_ELEMENTS("四象灵根", Tier.QUAD, "金木火土四灵根，四象轮转，中正平和",
            0.02, 0.02, 0, 0, 0.02, 0,
            SpecialEffect.NONE, 0),

    CHAOS_MIXED("混沌灵根", Tier.MIXED, "五行俱全，世人谓之杂灵根，然混沌之中蕴藏无限可能",
            0, 0, 0, 0, 0, 0,
            SpecialEffect.LATE_BLOOMER, 0.05);

    public enum Tier {
        PERFECT("正灵根"),
        HEAVENLY("天灵根"),
        VARIANT("异灵根"),
        DUAL("双灵根"),
        TRIPLE("三灵根"),
        QUAD("四灵根"),
        MIXED("杂灵根");

        private final String displayName;
        Tier(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum SpecialEffect {
        NONE,
        CRIT_CHANCE,
        REGENERATION,
        MP_COST_REDUCTION,
        DAMAGE_BOOST,
        DAMAGE_REDUCTION,
        EXPLORATION_CD,
        CRIT_DAMAGE,
        SKILL_DAMAGE,
        MONSTER_EXP,
        CULTIVATION_EFFICIENCY,
        TRADE_FEE_HALF,
        SPIRIT_STONE_DROP,
        PROFICIENCY_BOOST,
        LATE_BLOOMER,
        FIVE_ELEMENTS_AFFINITY
    }

    private static final int[] WEIGHTS = {
        1,
        3, 3, 3, 3, 3,
        5, 5, 5,
        10, 10, 10,
        10, 10,
        14,
        6
    };
    private static int totalWeight = -1;

    private final String displayName;
    private final Tier tier;
    private final String description;
    private final double attackBonus;
    private final double hpBonus;
    private final double mpBonus;
    private final double spiritBonus;
    private final double defenseBonus;
    private final double speedBonus;
    private final SpecialEffect effect;
    private final double effectValue;

    SpiritualRoot(String displayName, Tier tier, String description,
                  double attackBonus, double hpBonus, double mpBonus,
                  double spiritBonus, double defenseBonus, double speedBonus,
                  SpecialEffect effect, double effectValue) {
        this.displayName = displayName;
        this.tier = tier;
        this.description = description;
        this.attackBonus = attackBonus;
        this.hpBonus = hpBonus;
        this.mpBonus = mpBonus;
        this.spiritBonus = spiritBonus;
        this.defenseBonus = defenseBonus;
        this.speedBonus = speedBonus;
        this.effect = effect;
        this.effectValue = effectValue;
    }

    public String getDisplayName() { return displayName; }
    public Tier getTier() { return tier; }
    public String getDescription() { return description; }
    public double getAttackBonus() { return attackBonus; }
    public double getHpBonus() { return hpBonus; }
    public double getMpBonus() { return mpBonus; }
    public double getSpiritBonus() { return spiritBonus; }
    public double getDefenseBonus() { return defenseBonus; }
    public double getSpeedBonus() { return speedBonus; }
    public SpecialEffect getEffect() { return effect; }
    public double getEffectValue() { return effectValue; }

    public int applyAttackBonus(int base) { return base + (int)(base * attackBonus); }
    public int applyHpBonus(int base) { return base + (int)(base * hpBonus); }
    public int applyMpBonus(int base) { return base + (int)(base * mpBonus); }
    public int applySpiritBonus(int base) { return base + (int)(base * spiritBonus); }
    public int applyDefenseBonus(int base) { return base + (int)(base * defenseBonus); }
    public int applySpeedBonus(int base) { return base + (int)(base * speedBonus); }

    public static SpiritualRoot drawRandom(Random random) {
        if (totalWeight < 0) {
            totalWeight = 0;
            for (int w : WEIGHTS) totalWeight += w;
        }
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        SpiritualRoot[] values = values();
        for (int i = 0; i < values.length; i++) {
            cumulative += WEIGHTS[i];
            if (roll < cumulative) {
                return values[i];
            }
        }
        return CHAOS_MIXED;
    }

    public static List<SpiritualRoot> getByTier(Tier tier) {
        List<SpiritualRoot> result = new ArrayList<>();
        for (SpiritualRoot root : values()) {
            if (root.tier == tier) result.add(root);
        }
        return result;
    }

    public boolean hasEffect(SpecialEffect e) { return this.effect == e; }
}
