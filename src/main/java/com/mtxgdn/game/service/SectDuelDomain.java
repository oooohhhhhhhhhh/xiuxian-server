package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.SectMember;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 宗门对拼域：把两个 {@link Player} 放一起打一场（纯内存，不碰数据库）。
 *
 * <p>这套规则被两个地方共用：宗门战的逐场对决（{@link SectWarDomain}）与
 * 宗主争夺战的三局两胜（{@link SectSuccessionDomain}）。拆分前它们都直接调
 * {@code SectService} 的私有方法，所以只能待在同一个类里；拆成多个域类之后，
 * 与其把这份规则复制两份（迟早会飘），不如单独抽一个类共用。</p>
 *
 * <p><b>纯函数边界</b>：本类不读数据库、不开连接、不写库，也不改动传入的
 * {@link Player} 对象 —— 只有 {@link #duelFirstWins} 会往传入的
 * {@code log} 列表里追加战报文本。这条边界是刻意保留的：它让本类可以在
 * 事务块内被安全调用（宗主争夺战的落库逻辑就紧挨着一次对拼），
 * 也让对拼结果可复现（同一个 {@link Random} 序列、同样的入参 → 同样的战报）。</p>
 *
 * <p>方法体与拆分前逐字相同：{@code maxRounds = 15}、暴击默认 5%、
 * 伤害随机浮动 {@code [-5, +5]}、防御减免 {@code def/3}、灵根各效果系数一律原样保留。</p>
 */
final class SectDuelDomain {

    private final Random random = new Random();

    SectDuelDomain() {
    }

    /**
     * 选出战成员：按境界倒序、再按等级倒序，取前 {@code count} 名。
     */
    List<SectMember> selectFighters(List<SectMember> members, int count) {
        return members.stream()
                .sorted(Comparator.comparingInt((SectMember m) -> -m.getPlayerRealm())
                        .thenComparingInt(m -> -m.getPlayerLevel()))
                .limit(count)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    /** 打一场并把战报写进 {@code log}，返回胜者名。 */
    String simulateDuel(Player a, Player b, List<String> log) {
        return duelFirstWins(a, b, log) ? a.getName() : b.getName();
    }

    /**
     * 纯函数对拼：{@code a} 是否获胜。不读写数据库、不改动传入的 Player。
     * <p>抽成布尔返回值是为了让宗主争夺战能直接判胜负 —— 按玩家名比较在同名时不可靠。</p>
     */
    boolean duelFirstWins(Player a, Player b, List<String> log) {
        int aHp = a.getMaxHp();
        int bHp = b.getMaxHp();
        int aAtk = a.getAttack();
        int bAtk = b.getAttack();
        int aDef = a.getDefense();
        int bDef = b.getDefense();
        int aSpd = a.getSpeed();
        int bSpd = b.getSpeed();
        SpiritualRoot aRoot = a.getSpiritualRoot();
        SpiritualRoot bRoot = b.getSpiritualRoot();

        boolean aFirst = aSpd >= bSpd;
        int maxRounds = 15;

        for (int round = 1; round <= maxRounds && aHp > 0 && bHp > 0; round++) {
            // 灵根再生
            if (aRoot != null && aRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                aHp = Math.min(a.getMaxHp(), aHp + (int)(a.getMaxHp() * aRoot.getEffectValue()));
            }
            if (bRoot != null && bRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                bHp = Math.min(b.getMaxHp(), bHp + (int)(b.getMaxHp() * bRoot.getEffectValue()));
            }

            if (aFirst) {
                int dmg = calcWarDamage(aAtk, aRoot, bDef, bRoot);
                bHp -= dmg;
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return true; }

                dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return false; }
            } else {
                int dmg = calcWarDamage(bAtk, bRoot, aDef, aRoot);
                aHp -= dmg;
                if (aHp <= 0) { log.add("  " + b.getName() + " 造成 " + dmg + " 伤害，" + a.getName() + " HP:" + Math.max(0, aHp) + "/" + a.getMaxHp()); return false; }

                dmg = calcWarDamage(aAtk, aRoot, bDef, bRoot);
                bHp -= dmg;
                if (bHp <= 0) { log.add("  " + a.getName() + " 造成 " + dmg + " 伤害，" + b.getName() + " HP:" + Math.max(0, bHp) + "/" + b.getMaxHp()); return true; }
            }
        }

        // HP百分比胜
        double aPct = (double) aHp / a.getMaxHp();
        double bPct = (double) bHp / b.getMaxHp();
        if (aPct >= bPct) {
            log.add("  双方鏖战" + maxRounds + "回合，" + a.getName() + " HP剩余" + aPct * 100 + "% 胜出");
            return true;
        } else {
            log.add("  双方鏖战" + maxRounds + "回合，" + b.getName() + " HP剩余" + bPct * 100 + "% 胜出");
            return false;
        }
    }

    private int calcWarDamage(int atk, SpiritualRoot atkRoot, int def, SpiritualRoot defRoot) {
        double dmg = atk;

        // 攻击方灵根加成
        if (atkRoot != null) {
            if (atkRoot.hasEffect(SpiritualRoot.SpecialEffect.SKILL_DAMAGE)) {
                dmg *= (1 + atkRoot.getEffectValue());
            }
            if (atkRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
                dmg *= (1 + atkRoot.getEffectValue());
            }
        }

        // 暴击
        double critChance = atkRoot != null && atkRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_CHANCE)
                ? atkRoot.getEffectValue() : 0.05;
        double critMult = 1.5;
        if (atkRoot != null && atkRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_DAMAGE)) {
            critMult += atkRoot.getEffectValue();
        }
        if (random.nextDouble() < critChance) {
            dmg *= critMult;
        }

        // 随机浮动 + 防御减免
        dmg += random.nextInt(11) - 5;
        dmg -= def / 3.0;
        dmg = Math.max(1, dmg);

        // 防御方减伤
        if (defRoot != null && defRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            dmg *= (1 - defRoot.getEffectValue());
        }

        return Math.max(1, (int) Math.round(dmg));
    }
}
