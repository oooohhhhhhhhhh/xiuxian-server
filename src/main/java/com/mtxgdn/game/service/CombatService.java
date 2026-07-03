package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.BossForm;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.entity.RaidCombatResult;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.ItemRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatService {

    private final PlayerService playerService;
    private final SkillService skillService;
    private final Random random = new Random();

    // ==================== 切磋确认机制 ====================

    private static final long CHALLENGE_TIMEOUT_MS = 30_000;

    public static class PendingChallenge {
        public final long challengerPlayerId;
        public final String challengerName;
        public final long targetPlayerId;
        public final String targetName;
        public final long createdAt;
        public PendingChallenge(long challengerPlayerId, String challengerName, long targetPlayerId, String targetName) {
            this.challengerPlayerId = challengerPlayerId;
            this.challengerName = challengerName;
            this.targetPlayerId = targetPlayerId;
            this.targetName = targetName;
            this.createdAt = System.currentTimeMillis();
        }
        public boolean isExpired() { return System.currentTimeMillis() - createdAt > CHALLENGE_TIMEOUT_MS; }
    }

    private final Map<Long, PendingChallenge> pendingChallenges = new ConcurrentHashMap<>();

    public CombatService() {
        this.playerService = new PlayerService();
        this.skillService = new SkillService();
    }

    /**
     * 发起切磋挑战，返回结果中 success=true 表示挑战已创建等待接受。
     */
    public CombatResult createChallenge(long challengerPlayerId, long targetPlayerId) {
        Player challenger = playerService.getPlayerById(challengerPlayerId);
        Player target = playerService.getPlayerById(targetPlayerId);

        if (challenger == null) return CombatResult.failure("挑战者角色不存在");
        if (target == null) return CombatResult.failure("对手角色不存在");
        if (challengerPlayerId == targetPlayerId) return CombatResult.failure("不能挑战自己");
        if (challenger.getHp() <= 0) return CombatResult.failure("你的生命值不足，无法发起挑战");
        if (target.getHp() <= 0) return CombatResult.failure("对手已阵亡，无法挑战");

        // 清理过期挑战
        pendingChallenges.values().removeIf(PendingChallenge::isExpired);

        PendingChallenge existing = pendingChallenges.get(targetPlayerId);
        if (existing != null && !existing.isExpired()) {
            return CombatResult.failure("【" + existing.targetName + "】正在被他人挑战中，稍后再试");
        }

        existing = pendingChallenges.get(challengerPlayerId);
        if (existing != null && !existing.isExpired()) {
            return CombatResult.failure("你已向【" + existing.targetName + "】发起了挑战，等待对方回应中");
        }

        PendingChallenge challenge = new PendingChallenge(challengerPlayerId, challenger.getName(), targetPlayerId, target.getName());
        pendingChallenges.put(targetPlayerId, challenge);

        CombatResult result = new CombatResult();
        result.setSuccess(true);
        result.setPending(true);
        result.setChallengerName(challenger.getName());
        result.setTargetName(target.getName());
        result.setMessage("挑战已发出，等待【" + target.getName() + "】回应...");
        return result;
    }

    /**
     * 接受切磋挑战，执行战斗。playerId 必须是挑战目标。
     */
    public CombatResult acceptChallenge(long playerId) {
        PendingChallenge challenge = pendingChallenges.remove(playerId);
        if (challenge == null || challenge.isExpired()) {
            return CombatResult.failure("没有待接受的挑战，可能已超时");
        }
        if (challenge.targetPlayerId != playerId) {
            return CombatResult.failure("你无法接受此挑战");
        }
        return executePvp(challenge.challengerPlayerId, challenge.targetPlayerId);
    }

    /**
     * 拒绝切磋挑战。
     */
    public String rejectChallenge(long targetPlayerId) {
        PendingChallenge challenge = pendingChallenges.remove(targetPlayerId);
        if (challenge == null || challenge.isExpired()) {
            return null;
        }
        return challenge.challengerName;
    }

    /** 兼容旧接口：直接执行战斗（不走确认流程） */
    public CombatResult pvpChallenge(long challengerPlayerId, long targetPlayerId) {
        return executePvp(challengerPlayerId, targetPlayerId);
    }

    private CombatResult executePvp(long challengerPlayerId, long targetPlayerId) {
        Player challenger = playerService.getPlayerById(challengerPlayerId);
        Player target = playerService.getPlayerById(targetPlayerId);

        if (challenger == null) return CombatResult.failure("挑战者角色不存在");
        if (target == null) return CombatResult.failure("对手角色不存在");
        if (challengerPlayerId == targetPlayerId) return CombatResult.failure("不能挑战自己");
        if (challenger.getHp() <= 0) return CombatResult.failure("你的生命值不足，无法发起挑战");
        if (target.getHp() <= 0) return CombatResult.failure("对手已阵亡，无法挑战");

        List<Skill> challengerSkills = skillService.getPlayerSkills(challenger.getId());
        List<Skill> targetSkills = skillService.getPlayerSkills(target.getId());

        Set<Long> challengerUsedSkillIds = new HashSet<>();
        Set<Long> targetUsedSkillIds = new HashSet<>();

        int challengerCurrentHp = challenger.getHp();
        int challengerCurrentMp = challenger.getMp();
        int targetCurrentHp = target.getHp();
        int targetCurrentMp = target.getMp();

        String challengerTactic = challenger.getBattleStrategy() != null ? challenger.getBattleStrategy() : "balanced";
        String targetTactic = target.getBattleStrategy() != null ? target.getBattleStrategy() : "balanced";

        List<String> battleLog = new ArrayList<>();
        battleLog.add("⚔【" + challenger.getName() + "】VS【" + target.getName() + "】");
        battleLog.add("");

        boolean challengerFirst = playerService.getFinalSpeed(challenger) >= playerService.getFinalSpeed(target);
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

            battleLog.add("-- 第" + round + "回合 --");

            if (challengerFirst) {
                int damage = calculateDamage(challenger, target, challengerSkills, challengerCurrentMp, battleLog,
                        challenger.getName(), target.getName(), challengerUsedSkillIds, mpOut, challengerTactic);
                challengerCurrentMp = Math.max(0, challengerCurrentMp - mpOut[0]);
                targetCurrentHp -= damage;
                battleLog.add("  " + target.getName() + " 遭受 " + damage + " 点伤害 （HP: " + Math.max(0, targetCurrentHp) + "/" + target.getMaxHp() + "）");
                if (targetCurrentHp <= 0) { winner = challenger.getName(); break; }

                damage = calculateDamage(target, challenger, targetSkills, targetCurrentMp, battleLog,
                        target.getName(), challenger.getName(), targetUsedSkillIds, mpOut, targetTactic);
                targetCurrentMp = Math.max(0, targetCurrentMp - mpOut[0]);
                challengerCurrentHp -= damage;
                battleLog.add("  " + challenger.getName() + " 遭受 " + damage + " 点伤害 （HP: " + Math.max(0, challengerCurrentHp) + "/" + challenger.getMaxHp() + "）");
                if (challengerCurrentHp <= 0) { winner = target.getName(); break; }
            } else {
                int damage = calculateDamage(target, challenger, targetSkills, targetCurrentMp, battleLog,
                        target.getName(), challenger.getName(), targetUsedSkillIds, mpOut, targetTactic);
                targetCurrentMp = Math.max(0, targetCurrentMp - mpOut[0]);
                challengerCurrentHp -= damage;
                battleLog.add("  " + challenger.getName() + " 遭受 " + damage + " 点伤害 （HP: " + Math.max(0, challengerCurrentHp) + "/" + challenger.getMaxHp() + "）");
                if (challengerCurrentHp <= 0) { winner = target.getName(); break; }

                damage = calculateDamage(challenger, target, challengerSkills, challengerCurrentMp, battleLog,
                        challenger.getName(), target.getName(), challengerUsedSkillIds, mpOut, challengerTactic);
                challengerCurrentMp = Math.max(0, challengerCurrentMp - mpOut[0]);
                targetCurrentHp -= damage;
                battleLog.add("  " + target.getName() + " 遭受 " + damage + " 点伤害 （HP: " + Math.max(0, targetCurrentHp) + "/" + target.getMaxHp() + "）");
                if (targetCurrentHp <= 0) { winner = challenger.getName(); break; }
            }
        }

        if (winner == null) {
            if (challengerCurrentHp > targetCurrentHp) winner = challenger.getName();
            else if (targetCurrentHp > challengerCurrentHp) winner = target.getName();
            else winner = "平局";
        }

        boolean challengerWon = winner.equals(challenger.getName());
        boolean isTie = winner.equals("平局");
        long expReward = challengerWon ? calculateExpReward(challenger, target) : (isTie ? 0 : calculateExpReward(target, challenger) / 3);
        long goldReward = challengerWon ? random.nextLong(50, 200) : 0;

        if (challengerWon && expReward > 0) playerService.addExperience(challenger.getId(), expReward);
        if (challengerWon && goldReward > 0) playerService.addGold(challenger.getId(), goldReward);

        for (long skillId : challengerUsedSkillIds) skillService.addProficiency(challenger.getId(), skillId, 15);
        for (long skillId : targetUsedSkillIds) skillService.addProficiency(target.getId(), skillId, 10);

        new DailyService().onBattle(challenger.getId());
        new DailyService().onBattle(target.getId());

        challenger.setHp(Math.max(0, challengerCurrentHp));
        target.setHp(Math.max(0, targetCurrentHp));
        playerService.updatePlayer(challenger.getId(), challenger);
        playerService.updatePlayer(target.getId(), target);

        battleLog.add("");
        if (!winner.equals("平局")) {
            battleLog.add("🏆 " + winner + " 技高一筹，赢得了这场切磋！");
        } else {
            battleLog.add("🤝 双方势均力敌，以平局收场！");
        }
        battleLog.add("共 " + round + " 回合");
        if (challengerWon) {
            battleLog.add("获得灵力 +" + expReward + "，金币 +" + goldReward);
        } else if (winner.equals(target.getName())) {
            battleLog.add("惜败……你获得了 " + expReward + " 灵力作为安慰");
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
                                 Set<Long> usedSkillIds, int[] mpCostOut, String battleTactic) {
        SpiritualRoot attackerRoot = attacker.getSpiritualRoot();
        SpiritualRoot defenderRoot = defender.getSpiritualRoot();

        boolean isAggressive = "aggressive".equals(battleTactic);
        boolean isDefensive = "defensive".equals(battleTactic);

        // 防守策略：保留 50% MP 用于后续
        int mpReserve = isDefensive ? Math.max(0, currentMp / 2) : 0;

        Skill attackSkill = null;
        if (!skills.isEmpty() && currentMp > mpReserve) {
            List<Skill> atkSkills = new ArrayList<>();
            for (Skill skill : skills) {
                if (!skill.isAttackSkill()) continue;
                int scaledMpCost = skill.getMpCost() + (int)(skill.getMpCost() * (skill.getLevel() - 1) * 0.15);
                if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.MP_COST_REDUCTION)) {
                    scaledMpCost = (int)(scaledMpCost * (1 - attackerRoot.getEffectValue()));
                }
                if (scaledMpCost <= currentMp) atkSkills.add(skill);
            }

            if (!atkSkills.isEmpty()) {
                if (isAggressive) {
                    // 猛攻：选伤害最高的技能
                    atkSkills.sort((a, b) -> Integer.compare(
                        b.getDamage() + (int)(b.getDamage() * (b.getLevel() - 1) * 0.15),
                        a.getDamage() + (int)(a.getDamage() * (a.getLevel() - 1) * 0.15)));
                }
                attackSkill = atkSkills.get(0);
            }
        }

        int baseDamage;
        String skillDesc = "普攻";
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
            skillDesc = attackSkill.getName() + " Lv." + skillLevel;
            usedSkillIds.add(attackSkill.getId());
            mpCostOut[0] = scaledMpCost;
        } else {
            mpCostOut[0] = 0;
            baseDamage = playerService.getFinalAttack(attacker);
        }

        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
            baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
        }

        int defense = playerService.getFinalDefense(defender);
        double critChance = playerService.getFinalSpeed(attacker) / 200.0;
        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_CHANCE)) {
            critChance += attackerRoot.getEffectValue();
        }
        boolean isCrit = random.nextDouble() < critChance;
        double critMult = 1.5;
        if (isCrit) {
            if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.CRIT_DAMAGE)) {
                critMult += attackerRoot.getEffectValue();
            }
            baseDamage = (int)(baseDamage * critMult);
        }

        int variance = random.nextInt(-5, 6);
        int finalDamage = Math.max(1, baseDamage + variance - defense / 3);

        if (defenderRoot != null && defenderRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            finalDamage = (int)(finalDamage * (1 - defenderRoot.getEffectValue()));
        }
        finalDamage = Math.max(1, finalDamage);

        // 叙事输出
        String critText = isCrit ? "，暴击！" : "";
        String tacticText = isAggressive ? "猛攻" : isDefensive ? "沉稳" : "";
        if (attackSkill != null) {
            battleLog.add("  " + attackerName + " " + tacticText + "使出「" + skillDesc + "」" + critText + "——" + defenderName + " 受到 " + finalDamage + " 点伤害");
        } else {
            battleLog.add("  " + attackerName + " 挥剑斩去" + critText + "——" + defenderName + " 受到 " + finalDamage + " 点伤害");
        }

        return finalDamage;
    }

    private long calculateExpReward(Player winner, Player loser) {
        int realmDiff = winner.getRealm() - loser.getRealm();
        long baseExp = 50 + loser.getLevel() * 10L;
        if (realmDiff < 0) {
            baseExp *= 2;
        }
        return baseExp + random.nextLong(0, 30);
    }

    public PveCombatResult pveFight(long playerId, Monster monster) {
        Player player = playerService.getPlayerById(playerId);

        if (player == null) {
            return PveCombatResult.failure("角色不存在");
        }
        if (player.getHp() <= 0) {
            return PveCombatResult.failure("你的生命值不足，无法战斗");
        }

        List<Skill> playerSkills = skillService.getPlayerSkills(player.getId());
        Set<Long> usedSkillIds = new HashSet<>();

        int playerCurrentHp = player.getHp();
        int playerCurrentMp = player.getMp();
        int monsterCurrentHp = monster.getHp();

        List<String> battleLog = new ArrayList<>();
        if (monster.isBoss()) {
            battleLog.add("👑 前方出现了一股强大的气息——【" + monster.getName() + "】！");
            battleLog.add(monster.getDescription());
        } else {
            battleLog.add("⚔ 前方突然窜出一只【" + monster.getName() + "】！");
        }
        battleLog.add("【" + monster.getName() + "】- 攻击:" + monster.getAttack() + " 防御:" + monster.getDefense() + " 生命:" + monster.getHp());
        battleLog.add("---");

        boolean playerFirst = playerService.getFinalSpeed(player) >= monster.getSpeed();
        int maxRounds = 30;
        int round = 0;
        boolean playerWon = false;

        int[] mpOut = new int[1];
        SpiritualRoot playerRoot = player.getSpiritualRoot();

        while (round < maxRounds && playerCurrentHp > 0 && monsterCurrentHp > 0) {
            round++;

            if (playerRoot != null && playerRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                int regen = (int)(player.getMaxHp() * playerRoot.getEffectValue());
                playerCurrentHp = Math.min(player.getMaxHp(), playerCurrentHp + regen);
            }

            if (playerFirst) {
                int damage = calculatePlayerDamageToMonster(player, monster, playerSkills, playerCurrentMp,
                        battleLog, player.getName(), monster.getName(), usedSkillIds, mpOut);
                playerCurrentMp = Math.max(0, playerCurrentMp - mpOut[0]);
                monsterCurrentHp -= damage;
                battleLog.add("【" + monster.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, monsterCurrentHp));

                if (monsterCurrentHp <= 0) {
                    playerWon = true;
                    break;
                }

                int mDamage = calculateMonsterDamageToPlayer(monster, player, battleLog,
                        monster.getName(), player.getName());
                playerCurrentHp -= mDamage;
                battleLog.add("你受到 " + mDamage + " 点伤害，剩余生命 " + Math.max(0, playerCurrentHp));

                if (playerCurrentHp <= 0) {
                    break;
                }
            } else {
                int mDamage = calculateMonsterDamageToPlayer(monster, player, battleLog,
                        monster.getName(), player.getName());
                playerCurrentHp -= mDamage;
                battleLog.add("你受到 " + mDamage + " 点伤害，剩余生命 " + Math.max(0, playerCurrentHp));

                if (playerCurrentHp <= 0) {
                    break;
                }

                int damage = calculatePlayerDamageToMonster(player, monster, playerSkills, playerCurrentMp,
                        battleLog, player.getName(), monster.getName(), usedSkillIds, mpOut);
                playerCurrentMp = Math.max(0, playerCurrentMp - mpOut[0]);
                monsterCurrentHp -= damage;
                battleLog.add("【" + monster.getName() + "】受到 " + damage + " 点伤害，剩余生命 " + Math.max(0, monsterCurrentHp));

                if (monsterCurrentHp <= 0) {
                    playerWon = true;
                    break;
                }
            }
        }

        PveCombatResult result = new PveCombatResult();
        result.setSuccess(true);
        result.setMonsterName(monster.getName());
        result.setBoss(monster.isBoss());
        result.setPlayerHpRemaining(Math.max(0, playerCurrentHp));
        result.setMonsterHpRemaining(Math.max(0, monsterCurrentHp));
        result.setTotalRounds(round);

        ItemService itemService = new ItemService();

        if (playerWon) {
            result.setPlayerWon(true);

            long expReward = monster.getExpReward();
            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.MONSTER_EXP)) {
                expReward = (long)(expReward * (1 + root.getEffectValue()));
            }
            playerService.addExperience(player.getId(), expReward);
            result.setExpGained(expReward);

            long goldReward = monster.getGoldReward();
            if (goldReward > 0) {
                playerService.addGold(player.getId(), goldReward);
                result.setGoldGained(goldReward);
            }

            long ssReward = monster.getSpiritStoneReward();
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.SPIRIT_STONE_DROP)) {
                ssReward = (long)(ssReward * (1 + root.getEffectValue()));
            }
            if (ssReward > 0) {
                itemService.addSpiritStones(player.getId(), ssReward);
                result.setSpiritStonesGained(ssReward);
            }

            if (random.nextDouble() < monster.getLootChance()) {
                String[] lootTable = monster.getLootTable();
                if (lootTable != null && lootTable.length > 0) {
                    String lootItem = lootTable[random.nextInt(lootTable.length)];
                    if (ItemRegistry.contains(lootItem)) {
                        itemService.addItem(player.getId(), lootItem, 1);
                        result.setItemGained(lootItem);
                        result.setItemQuantity(1);
                    }
                }
            }

            battleLog.add("---");
            if (monster.isBoss()) {
                battleLog.add("🎉 经过 " + round + " 回合的鏖战，你成功击败了 Boss【" + monster.getName() + "】！");
            } else {
                battleLog.add("经过 " + round + " 回合激战，你成功击败了【" + monster.getName() + "】！");
            }
            battleLog.add("获得了 " + expReward + " 点经验。");
            if (goldReward > 0) battleLog.add("获得了 " + goldReward + " 金币。");
            if (ssReward > 0) battleLog.add("获得了 " + ssReward + " 灵石。");
            if (result.getItemGained() != null) battleLog.add("妖兽身上掉落了一件物品！");

            StringBuilder msgBuilder = new StringBuilder("击败了【" + monster.getName() + "】，获得 " + expReward + " 经验");
            if (goldReward > 0) msgBuilder.append(", ").append(goldReward).append(" 金币");
            if (ssReward > 0) msgBuilder.append(", ").append(ssReward).append(" 灵石");
            if (result.getItemGained() != null) msgBuilder.append(", 掉落物品");
            result.setMessage(msgBuilder.toString());
        } else {
            result.setPlayerWon(false);
            int hpLost = player.getHp() - Math.max(0, playerCurrentHp);
            battleLog.add("---");
            if (monster.isBoss()) {
                battleLog.add("Boss【" + monster.getName() + "】的力量太过强大，你被击败了...");
            } else {
                battleLog.add("你被【" + monster.getName() + "】击败了，损失了 " + hpLost + " 点生命值...");
            }
            result.setMessage("被【" + monster.getName() + "】击败，损失了 " + hpLost + " 点生命值");
        }

        for (long skillId : usedSkillIds) {
            skillService.addProficiency(player.getId(), skillId, 15);
        }

        player.setHp(Math.max(0, playerCurrentHp));
        playerService.updatePlayer(player.getId(), player);

        result.setBattleLog(battleLog);
        return result;
    }

    private int calculatePlayerDamageToMonster(Player attacker, Monster defender, List<Skill> skills,
                                                int currentMp, List<String> battleLog, String attackerName,
                                                String defenderName, Set<Long> usedSkillIds, int[] mpCostOut) {
        SpiritualRoot attackerRoot = attacker.getSpiritualRoot();

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
            baseDamage = playerService.getFinalAttack(attacker) + playerService.getFinalSpirit(attacker);
            battleLog.add("【" + attackerName + "】发动了普通攻击！");
        }

        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
            baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
        }

        double critChance = playerService.getFinalSpeed(attacker) / 200.0;
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
        int finalDamage = Math.max(1, baseDamage + variance - defender.getDefense() / 3);

        return Math.max(1, finalDamage);
    }

    private int calculateMonsterDamageToPlayer(Monster attacker, Player defender, List<String> battleLog,
                                                String attackerName, String defenderName) {
        SpiritualRoot defenderRoot = defender.getSpiritualRoot();

        int baseDamage = attacker.getAttack();
        int variance = random.nextInt(-4, 5);
        int finalDamage = Math.max(1, baseDamage + variance - playerService.getFinalDefense(defender) / 3);

        if (defenderRoot != null && defenderRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            finalDamage = (int)(finalDamage * (1 - defenderRoot.getEffectValue()));
        }

        return Math.max(1, finalDamage);
    }

    public static class CombatResult {
        private boolean success;
        private boolean pending;
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

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public boolean isPending() { return pending; }
        public void setPending(boolean pending) { this.pending = pending; }

        public String getWinner() { return winner; }

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

    public RaidCombatResult raidBossFight(List<Long> playerIds, List<BossForm> bossForms) {
        if (playerIds == null || playerIds.isEmpty()) {
            return RaidCombatResult.failure("团队成员不能为空");
        }

        List<Player> players = new ArrayList<>();
        for (long playerId : playerIds) {
            Player player = playerService.getPlayerById(playerId);
            if (player == null || player.getHp() <= 0) {
                return RaidCombatResult.failure("团队中存在无效或已阵亡的成员");
            }
            players.add(player);
        }

        int bossTotalHp = 0;
        for (BossForm form : bossForms) {
            bossTotalHp += (int)(form.getHpMultiplier() * 500 * (bossForms.indexOf(form) + 1));
        }

        int bossCurrentHp = bossTotalHp;
        BossForm currentForm = bossForms.get(0);
        int currentFormIndex = 0;
        int realm = 3;

        Monster boss = new Monster(currentForm.getName(), bossCurrentHp,
                (int)(500 * currentForm.getAttackMultiplier()),
                (int)(300 * currentForm.getDefenseMultiplier()),
                (int)(100 * currentForm.getSpeedMultiplier()),
                realm, true, new String[0], 0.5, 0, 0, 0, currentForm.getDescription());

        List<String> battleLog = new ArrayList<>();
        battleLog.add("👑【" + currentForm.getName() + "】出现在众人面前！");
        battleLog.add(currentForm.getDescription());
        battleLog.add("提示：进入Boss战，玩家属性削弱" + (int)(currentForm.getPlayerDebuffPercent() * 100) + "%");
        battleLog.add("---");

        Map<Long, Integer> playerHpMap = new LinkedHashMap<>();
        Map<Long, Integer> playerMpMap = new LinkedHashMap<>();
        Map<Long, List<Skill>> playerSkillsMap = new LinkedHashMap<>();
        Map<Long, Set<Long>> playerUsedSkills = new LinkedHashMap<>();
        Map<Long, String> playerNames = new LinkedHashMap<>();

        for (Player p : players) {
            playerHpMap.put(p.getId(), p.getHp());
            playerMpMap.put(p.getId(), p.getMp());
            playerSkillsMap.put(p.getId(), skillService.getPlayerSkills(p.getId()));
            playerUsedSkills.put(p.getId(), new HashSet<>());
            playerNames.put(p.getId(), p.getName());
        }

        long lastHitPlayerId = -1;
        String lastHitPlayerName = "";
        int round = 0;
        int maxRounds = 50;

        while (round < maxRounds && bossCurrentHp > 0 && playerHpMap.values().stream().anyMatch(hp -> hp > 0)) {
            round++;
            battleLog.add("-- 第" + round + "回合 --");

            for (Player player : players) {
                long playerId = player.getId();
                if (playerHpMap.get(playerId) <= 0) continue;

                double debuff = currentForm.getPlayerDebuffPercent();
                int adjustedAttack = (int)(playerService.getFinalAttack(player) * (1 - debuff));
                int adjustedDefense = (int)(playerService.getFinalDefense(player) * (1 - debuff));

                int[] mpOut = new int[1];
                int damage = calculatePlayerDamageToMonster(player, boss, playerSkillsMap.get(playerId),
                        playerMpMap.get(playerId), battleLog, player.getName(), boss.getName(),
                        playerUsedSkills.get(playerId), mpOut);

                damage = (int)(damage * (1 - debuff));
                playerMpMap.put(playerId, Math.max(0, playerMpMap.get(playerId) - mpOut[0]));
                bossCurrentHp -= damage;

                battleLog.add("【" + player.getName() + "】对【" + boss.getName() + "】造成 " + damage + " 点伤害");

                if (bossCurrentHp <= 0) {
                    lastHitPlayerId = playerId;
                    lastHitPlayerName = player.getName();
                    break;
                }

                double hpPercent = (double)bossCurrentHp / bossTotalHp;
                if (hpPercent < 0.33 && currentFormIndex == 0) {
                    currentFormIndex = 2;
                    currentForm = bossForms.get(2);
                    boss.setName(currentForm.getName());
                    boss.setDescription(currentForm.getDescription());
                    boss.setAttack((int)(500 * currentForm.getAttackMultiplier()));
                    boss.setDefense((int)(300 * currentForm.getDefenseMultiplier()));
                    battleLog.add("⚠【" + boss.getName() + "】进入第三形态！属性大幅提升，玩家削弱15%！");
                } else if (hpPercent < 0.66 && currentFormIndex == 0) {
                    currentFormIndex = 1;
                    currentForm = bossForms.get(1);
                    boss.setName(currentForm.getName());
                    boss.setDescription(currentForm.getDescription());
                    boss.setAttack((int)(500 * currentForm.getAttackMultiplier()));
                    boss.setDefense((int)(300 * currentForm.getDefenseMultiplier()));
                    battleLog.add("⚠【" + boss.getName() + "】进入第二形态！属性提升，玩家削弱10%！");
                }
            }

            if (bossCurrentHp <= 0) break;

            List<Long> alivePlayers = new ArrayList<>();
            for (long pid : playerHpMap.keySet()) {
                if (playerHpMap.get(pid) > 0) alivePlayers.add(pid);
            }

            if (alivePlayers.isEmpty()) break;

            long targetPlayerId = alivePlayers.get(random.nextInt(alivePlayers.size()));
            Player targetPlayer = players.stream().filter(p -> p.getId() == targetPlayerId).findFirst().orElse(null);

            if (targetPlayer != null) {
                double debuff = currentForm.getPlayerDebuffPercent();
                int adjustedDefense = (int)(playerService.getFinalDefense(targetPlayer) * (1 - debuff));

                int mDamage = calculateMonsterDamageToPlayer(boss, targetPlayer, battleLog,
                        boss.getName(), targetPlayer.getName());

                playerHpMap.put(targetPlayerId, playerHpMap.get(targetPlayerId) - mDamage);
                battleLog.add("【" + boss.getName() + "】攻击【" + targetPlayer.getName() + "】，造成 " + mDamage + " 点伤害，剩余生命 " + Math.max(0, playerHpMap.get(targetPlayerId)));

                if (playerHpMap.get(targetPlayerId) <= 0) {
                    battleLog.add("【" + targetPlayer.getName() + "】被击败！");
                }
            }
        }

        RaidCombatResult result = new RaidCombatResult();
        result.setSuccess(true);
        result.setBossName(boss.getName());
        result.setBossForm(currentFormIndex);
        result.setTotalRounds(round);

        List<Long> defeatedPlayers = new ArrayList<>();
        for (long pid : playerHpMap.keySet()) {
            if (playerHpMap.get(pid) <= 0) {
                defeatedPlayers.add(pid);
            }
        }
        result.setDefeatedPlayers(defeatedPlayers);

        boolean allDefeated = playerHpMap.values().stream().allMatch(hp -> hp <= 0);
        boolean bossDefeated = bossCurrentHp <= 0;

        if (bossDefeated && !allDefeated) {
            result.setTeamWon(true);
            result.setLastHitPlayerId(lastHitPlayerId);
            result.setLastHitPlayerName(lastHitPlayerName);
            battleLog.add("---");
            battleLog.add("🎉 团队成功击败了【" + boss.getName() + "】！");
            battleLog.add("最终一击由【" + lastHitPlayerName + "】完成！");
            result.setMessage("团队成功击败了【" + boss.getName() + "】，最终一击由【" + lastHitPlayerName + "】完成！");
        } else {
            result.setTeamWon(false);
            battleLog.add("---");
            battleLog.add("💀 团队全军覆没，Boss战失败...");
            result.setMessage("团队全军覆没，挑战失败");
        }

        for (Player p : players) {
            p.setHp(Math.max(0, playerHpMap.get(p.getId())));
            playerService.updatePlayer(p.getId(), p);
        }

        result.setBattleLog(battleLog);
        return result;
    }
}
