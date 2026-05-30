package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;

import java.util.*;

public class CombatService {

    private final PlayerService playerService;
    private final SkillService skillService;
    private final Random random = new Random();

    public CombatService() {
        this.playerService = new PlayerService();
        this.skillService = new SkillService();
    }

    public CombatResult pvpChallenge(long challengerPlayerId, long targetPlayerId) {
        Player challenger = playerService.getPlayerById(challengerPlayerId);
        Player target = playerService.getPlayerById(targetPlayerId);

        if (challenger == null) {
            return CombatResult.failure("挑战者角色不存在");
        }
        if (target == null) {
            return CombatResult.failure("对手角色不存在");
        }
        if (challengerPlayerId == targetPlayerId) {
            return CombatResult.failure("不能挑战自己");
        }
        if (challenger.getHp() <= 0) {
            return CombatResult.failure("你的生命值不足，无法发起挑战");
        }
        if (target.getHp() <= 0) {
            return CombatResult.failure("对手已阵亡，无法挑战");
        }

        List<Skill> challengerSkills = skillService.getPlayerSkills(challenger.getId());
        List<Skill> targetSkills = skillService.getPlayerSkills(target.getId());

        Set<Long> challengerUsedSkillIds = new HashSet<>();
        Set<Long> targetUsedSkillIds = new HashSet<>();

        int challengerCurrentHp = challenger.getHp();
        int challengerCurrentMp = challenger.getMp();
        int targetCurrentHp = target.getHp();
        int targetCurrentMp = target.getMp();

        List<String> battleLog = new ArrayList<>();
        battleLog.add("【" + challenger.getName() + "】向【" + target.getName() + "】发起了挑战！");
        battleLog.add("---");

        boolean challengerFirst = challenger.getSpeed() >= target.getSpeed();
        int maxRounds = 20;
        int round = 0;
        String winner = null;

        int[] mpOut = new int[1];
        SpiritualRoot challengerRoot = challenger.getSpiritualRoot();
        SpiritualRoot targetRoot = target.getSpiritualRoot();

        while (round < maxRounds && challengerCurrentHp > 0 && targetCurrentHp > 0) {
            round++;

            if (challengerRoot != null && challengerRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                int regen = (int)(challenger.getMaxHp() * challengerRoot.getEffectValue());
                challengerCurrentHp = Math.min(challenger.getMaxHp(), challengerCurrentHp + regen);
            }
            if (targetRoot != null && targetRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                int regen = (int)(target.getMaxHp() * targetRoot.getEffectValue());
                targetCurrentHp = Math.min(target.getMaxHp(), targetCurrentHp + regen);
            }

            if (challengerFirst) {
                int damage = calculateDamage(challenger, target, challengerSkills, challengerCurrentMp, battleLog,
                        challenger.getName(), target.getName(), challengerUsedSkillIds, mpOut);
                challengerCurrentMp = Math.max(0, challengerCurrentMp - mpOut[0]);
                targetCurrentHp -= damage;
                battleLog.add("【" + target.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, targetCurrentHp));

                if (targetCurrentHp <= 0) {
                    winner = challenger.getName();
                    break;
                }

                damage = calculateDamage(target, challenger, targetSkills, targetCurrentMp, battleLog,
                        target.getName(), challenger.getName(), targetUsedSkillIds, mpOut);
                targetCurrentMp = Math.max(0, targetCurrentMp - mpOut[0]);
                challengerCurrentHp -= damage;
                battleLog.add("【" + challenger.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, challengerCurrentHp));

                if (challengerCurrentHp <= 0) {
                    winner = target.getName();
                    break;
                }
            } else {
                int damage = calculateDamage(target, challenger, targetSkills, targetCurrentMp, battleLog,
                        target.getName(), challenger.getName(), targetUsedSkillIds, mpOut);
                targetCurrentMp = Math.max(0, targetCurrentMp - mpOut[0]);
                challengerCurrentHp -= damage;
                battleLog.add("【" + challenger.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, challengerCurrentHp));

                if (challengerCurrentHp <= 0) {
                    winner = target.getName();
                    break;
                }

                damage = calculateDamage(challenger, target, challengerSkills, challengerCurrentMp, battleLog,
                        challenger.getName(), target.getName(), challengerUsedSkillIds, mpOut);
                challengerCurrentMp = Math.max(0, challengerCurrentMp - mpOut[0]);
                targetCurrentHp -= damage;
                battleLog.add("【" + target.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, targetCurrentHp));

                if (targetCurrentHp <= 0) {
                    winner = challenger.getName();
                    break;
                }
            }
        }

        if (winner == null) {
            if (challengerCurrentHp > targetCurrentHp) {
                winner = challenger.getName();
            } else if (targetCurrentHp > challengerCurrentHp) {
                winner = target.getName();
            } else {
                winner = "平局";
            }
        }

        boolean challengerWon = winner.equals(challenger.getName());
        long expReward = challengerWon ? calculateExpReward(challenger, target) : calculateExpReward(target, challenger) / 3;
        long goldReward = challengerWon ? random.nextLong(50, 200) : 0;

        if (challengerWon && expReward > 0) {
            playerService.addExperience(challenger.getId(), expReward);
        }
        if (challengerWon && goldReward > 0) {
            playerService.addGold(challenger.getId(), goldReward);
        }

        for (long skillId : challengerUsedSkillIds) {
            skillService.addProficiency(challenger.getId(), skillId, 15);
        }
        for (long skillId : targetUsedSkillIds) {
            skillService.addProficiency(target.getId(), skillId, 10);
        }

        new DailyService().onBattle(challenger.getId());
        new DailyService().onBattle(target.getId());

        challenger.setHp(Math.max(0, challengerCurrentHp));
        target.setHp(Math.max(0, targetCurrentHp));
        playerService.updatePlayer(challenger.getId(), challenger);
        playerService.updatePlayer(target.getId(), target);

        battleLog.add("---");
        battleLog.add("战斗结束！胜利者: " + winner);
        if (challengerWon) {
            battleLog.add("你获得了 " + expReward + " 经验和 " + goldReward + " 金币的奖励！");
        } else if (winner.equals(target.getName())) {
            battleLog.add("挑战失败，你获得了 " + expReward + " 经验作为安慰...");
        }

        CombatResult result = new CombatResult();
        result.setSuccess(true);
        result.setWinner(winner);
        result.setChallengerWon(challengerWon);
        result.setChallengerName(challenger.getName());
        result.setTargetName(target.getName());
        result.setChallengerRemainingHp(Math.max(0, challengerCurrentHp));
        result.setTargetRemainingHp(Math.max(0, targetCurrentHp));
        result.setTotalRounds(round);
        result.setExpReward(expReward);
        result.setGoldReward(goldReward);
        result.setBattleLog(battleLog);
        return result;
    }

    private int calculateDamage(Player attacker, Player defender, List<Skill> skills, int currentMp,
                                 List<String> battleLog, String attackerName, String defenderName,
                                 Set<Long> usedSkillIds, int[] mpCostOut) {
        SpiritualRoot attackerRoot = attacker.getSpiritualRoot();
        SpiritualRoot defenderRoot = defender.getSpiritualRoot();

        Skill attackSkill = null;
        if (!skills.isEmpty() && currentMp > 0) {
            for (Skill skill : skills) {
                int scaledMpCost = skill.getMpCost() + (int)(skill.getMpCost() * (skill.getLevel() - 1) * 0.15);
                if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.MP_COST_REDUCTION)) {
                    scaledMpCost = (int)(scaledMpCost * (1 - attackerRoot.getEffectValue()));
                }
                if (skill.isAttackSkill() && scaledMpCost <= currentMp) {
                    attackSkill = skill;
                    break;
                }
            }
        }

        int baseDamage;
        if (attackSkill != null) {
            int skillLevel = attackSkill.getLevel();
            int scaledMpCost = attackSkill.getMpCost() + (int)(attackSkill.getMpCost() * (skillLevel - 1) * 0.15);
            if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.MP_COST_REDUCTION)) {
                scaledMpCost = (int)(scaledMpCost * (1 - attackerRoot.getEffectValue()));
            }
            baseDamage = attackSkill.getDamage() + (int)(attackSkill.getDamage() * (skillLevel - 1) * 0.15);
            if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.SKILL_DAMAGE)) {
                baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
            }
            battleLog.add("【" + attackerName + "】使用了【" + attackSkill.getName() + " Lv." + skillLevel
                    + "】（消耗 " + scaledMpCost + " MP）！");
            usedSkillIds.add(attackSkill.getId());
            mpCostOut[0] = scaledMpCost;
        } else {
            mpCostOut[0] = 0;
            baseDamage = attacker.getAttack();
        }

        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
            baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
        }

        int defense = defender.getDefense();
        double critChance = attacker.getSpeed() / 200.0;
        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_CHANCE)) {
            critChance += attackerRoot.getEffectValue();
        }
        boolean isCrit = random.nextDouble() < critChance;
        if (isCrit) {
            double critMult = 1.5;
            if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_DAMAGE)) {
                critMult += attackerRoot.getEffectValue();
            }
            baseDamage = (int)(baseDamage * critMult);
            battleLog.add("暴击！");
        }

        int variance = random.nextInt(-5, 6);
        int finalDamage = Math.max(1, baseDamage + variance - defense / 3);

        if (defenderRoot != null && defenderRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            finalDamage = (int)(finalDamage * (1 - defenderRoot.getEffectValue()));
        }

        return Math.max(1, finalDamage);
    }

    private long calculateExpReward(Player winner, Player loser) {
        int realmDiff = winner.getRealm() - loser.getRealm();
        long baseExp = 50 + loser.getLevel() * 10L;
        if (realmDiff < 0) {
            baseExp *= 2;
        }
        return baseExp + random.nextLong(0, 30);
    }

    public static class CombatResult {
        private boolean success;
        private String winner;
        private boolean challengerWon;
        private String challengerName;
        private String targetName;
        private int challengerRemainingHp;
        private int targetRemainingHp;
        private int totalRounds;
        private long expReward;
        private long goldReward;
        private List<String> battleLog;
        private String message;

        public CombatResult() {
        }

        public static CombatResult failure(String message) {
            CombatResult r = new CombatResult();
            r.success = false;
            r.message = message;
            return r;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getWinner() {
            return winner;
        }

        public void setWinner(String winner) {
            this.winner = winner;
        }

        public boolean isChallengerWon() {
            return challengerWon;
        }

        public void setChallengerWon(boolean challengerWon) {
            this.challengerWon = challengerWon;
        }

        public String getChallengerName() {
            return challengerName;
        }

        public void setChallengerName(String challengerName) {
            this.challengerName = challengerName;
        }

        public String getTargetName() {
            return targetName;
        }

        public void setTargetName(String targetName) {
            this.targetName = targetName;
        }

        public int getChallengerRemainingHp() {
            return challengerRemainingHp;
        }

        public void setChallengerRemainingHp(int challengerRemainingHp) {
            this.challengerRemainingHp = challengerRemainingHp;
        }

        public int getTargetRemainingHp() {
            return targetRemainingHp;
        }

        public void setTargetRemainingHp(int targetRemainingHp) {
            this.targetRemainingHp = targetRemainingHp;
        }

        public int getTotalRounds() {
            return totalRounds;
        }

        public void setTotalRounds(int totalRounds) {
            this.totalRounds = totalRounds;
        }

        public long getExpReward() {
            return expReward;
        }

        public void setExpReward(long expReward) {
            this.expReward = expReward;
        }

        public long getGoldReward() {
            return goldReward;
        }

        public void setGoldReward(long goldReward) {
            this.goldReward = goldReward;
        }

        public List<String> getBattleLog() {
            return battleLog;
        }

        public void setBattleLog(List<String> battleLog) {
            this.battleLog = battleLog;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
