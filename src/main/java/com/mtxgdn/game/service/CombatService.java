package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.BossForm;
import com.mtxgdn.game.entity.MapLocation;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.entity.RaidCombatResult;
import com.mtxgdn.game.entity.Skill;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.combat.BattleLogGenerator;
import com.mtxgdn.game.service.SeasonService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatService {

    private final PlayerService playerService;
    private final SkillService skillService;
    private final Random random = new Random();

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

    /** 战斗中的可变状态：HP/MP 通过引用传递，回合函数直接修改，避免值拷贝导致判定失效 */
    private static class BattleState {
        int playerHp;
        int playerMp;
        int monsterHp;
        /** 本场战斗减伤百分比（防御型技能，如金刚护体） */
        int damageReductionPercent;
        /** 已装备功法的伤害加成倍率（≥1，combat_damage_bonus 之和折算） */
        double techDamageMult = 1.0;
        /** 已装备功法的减伤比例（0~0.9，damage_reduction 之和折算） */
        double techReduction = 0.0;
        /** 已激活阵法的防御加成（% 减伤，护山大阵/迷踪阵），激活期间全程生效 */
        int formationReductionPct = 0;

        BattleState(int playerHp, int playerMp, int monsterHp, double techDamageMult, double techReduction,
                    int formationReductionPct) {
            this.playerHp = playerHp;
            this.playerMp = playerMp;
            this.monsterHp = monsterHp;
            this.techDamageMult = techDamageMult;
            this.techReduction = techReduction;
            this.formationReductionPct = formationReductionPct;
        }
    }

    /** 技能回合冷却：skillId -> 上次使用回合（0 = 未使用） */
    private static class SkillCooldown {
        final Map<Long, Integer> lastUsedRound = new HashMap<>();

        boolean isReady(Skill skill, int currentRound) {
            Integer last = lastUsedRound.get(skill.getId());
            return last == null || currentRound - last >= 2;
        }

        void markUsed(Skill skill, int currentRound) {
            lastUsedRound.put(skill.getId(), currentRound);
        }
    }

    private final Map<Long, PendingChallenge> pendingChallenges = new ConcurrentHashMap<>();

    /** PVP 每日参与次数限制（发起+接受合计，持久化到数据库，重启不丢失） */
    private static final int DAILY_PVP_LIMIT = 10;

    private static boolean canParticipatePvp(long playerId) {
        int count = getDailyPvpCount(playerId);
        return count < DAILY_PVP_LIMIT;
    }

    private static void recordPvpParticipation(long playerId) {
        incrementDailyPvpCount(playerId);
    }

    /** 读取玩家今日PVP次数（跨天自动清零） */
    private static int getDailyPvpCount(long playerId) {
        // 先执行 player_daily 行级按日复位，保证 PVP 次数在跨天后从 0 开始
        ServiceRegistry.getDailyService().ensureDailyReset(playerId);
        String today = java.time.LocalDate.now().toString();
        String sql = "SELECT pvp_count FROM player_daily WHERE player_id = ? AND last_daily_reset = ?";
        try (java.sql.Connection conn = com.mtxgdn.db.DatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("pvp_count");
                }
            }
        } catch (java.sql.SQLException e) {
            // 表或列可能不存在（升级前），记录日志并降级为0
            System.err.println("[Combat] 读取PVP次数失败: " + e.getMessage());
        }
        return 0;
    }

    /** 原子递增玩家今日PVP次数（跨天自动重置为1） */
    private static void incrementDailyPvpCount(long playerId) {
        // 先执行行级按日复位，确保上一日的 last_daily_reset 已刷新到今天（否则共享行会导致其它列跨日失效）
        ServiceRegistry.getDailyService().ensureDailyReset(playerId);
        String today = java.time.LocalDate.now().toString();
        String sql;
        if (com.mtxgdn.db.DatabaseManager.isSqlite()) {
            sql = "INSERT INTO player_daily (player_id, pvp_count, last_daily_reset) VALUES (?, 1, ?) " +
                  "ON CONFLICT(player_id) DO UPDATE SET " +
                  "pvp_count = CASE WHEN last_daily_reset = ? THEN pvp_count + 1 ELSE 1 END, " +
                  "last_daily_reset = ?";
        } else {
            sql = "INSERT INTO player_daily (player_id, pvp_count, last_daily_reset) VALUES (?, 1, ?) " +
                  "ON DUPLICATE KEY UPDATE " +
                  "pvp_count = IF(last_daily_reset = ?, pvp_count + 1, 1), " +
                  "last_daily_reset = ?";
        }
        try (java.sql.Connection conn = com.mtxgdn.db.DatabaseManager.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, today);
            ps.setString(3, today);
            ps.setString(4, today);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            System.err.println("[Combat] 记录PVP次数失败: " + e.getMessage());
        }
    }

    public CombatService() {
        this.playerService = ServiceRegistry.getPlayerService();
        this.skillService = ServiceRegistry.getSkillService();
    }

    /**
     * 险地掠夺的最低危险度：危险度 ≥3（高危 / 极危）才算"险地"。
     * <p>门槛设在 3 而不是 1，是为了让 PK 收益只存在于真正的凶地 ——
     * 否则全图都会变成猎杀场，低危地带也会被大号堵门。</p>
     */
    private static final int PK_LOOT_MIN_DANGER = 3;
    /** 险地掠夺比例：每 1 级危险度夺走败者 5% 灵石（危险度 3 → 15%，4 → 20%）。 */
    private static final double PK_LOOT_RATIO_PER_DANGER = 0.05;

    /**
     * 安全区禁止私斗：任一方身处安全区即拒绝（地图上的 🛡 标记此前只是展示，未真正生效）。
     *
     * <p>判定只看 {@code is_safe_zone}，与危险度无关：危险度决定"赢了能抢多少"，
     * 安全区决定"能不能打"。两者分离，避免管理员把某地危险度误填成 0 就意外开放了 PK。</p>
     *
     * @return null 表示允许切磋；否则返回可直接回复玩家的原因
     */
    private String checkSafeZone(long challengerPlayerId, long targetPlayerId) {
        MapService mapService = ServiceRegistry.getMapService();

        MapLocation challengerZone = mapService.getLocation(mapService.getPlayerLocationId(challengerPlayerId));
        if (challengerZone != null && challengerZone.isSafeZone()) {
            return "【" + challengerZone.getName() + "】乃安全区，城中禁止私斗，请先离开此地再切磋";
        }

        MapLocation targetZone = mapService.getLocation(mapService.getPlayerLocationId(targetPlayerId));
        if (targetZone != null && targetZone.isSafeZone()) {
            return "对方身处安全区【" + targetZone.getName() + "】，无法对其发起挑战";
        }

        return null;
    }

    /**
     * 险地 PK 掠夺：胜者从败者身上夺走一部分灵石。
     *
     * <p>只有危险度 ≥{@link #PK_LOOT_MIN_DANGER} 的地点才触发，比例随危险度递增
     * （见 {@link #PK_LOOT_RATIO_PER_DANGER}）。这是"危险度"第一次真正影响 PVP 收益 ——
     * 此前危险度只是地图上的一个展示字段。</p>
     *
     * <p>扣款以 {@code removeSpiritStones} 的返回值为准，扣不动就一分不夺：
     * 先加后扣或按读取值直接发放，都会在并发下凭空造出灵石。</p>
     *
     * @return 实际掠夺到的灵石数量；不触发或失败时为 0
     */
    private long grantDangerZonePvpLoot(Player winner, Player loser, BattleLogGenerator logGenerator) {
        if (winner == null || loser == null) return 0;

        MapService mapService = ServiceRegistry.getMapService();
        int danger = mapService.getDangerLevel(mapService.getPlayerLocationId(winner.getId()));
        if (danger < PK_LOOT_MIN_DANGER) return 0;

        ItemService itemService = ServiceRegistry.getItemService();
        long loserStones = itemService.getSpiritStoneCount(loser.getId());
        if (loserStones <= 0) return 0;

        long loot = Math.max(1, Math.round(loserStones * PK_LOOT_RATIO_PER_DANGER * danger));
        loot = Math.min(loot, loserStones);
        if (!itemService.removeSpiritStones(loser.getId(), loot)) {
            return 0;
        }
        itemService.addSpiritStones(winner.getId(), loot);
        logGenerator.add("☠ " + mapService.getDangerName(danger) + "之地，"
                + winner.getName() + " 自 " + loser.getName() + " 身上夺得 " + loot + " 灵石");
        return loot;
    }

    public CombatResult createChallenge(long challengerPlayerId, long targetPlayerId) {
        Player challenger = playerService.getPlayerById(challengerPlayerId);
        Player target = playerService.getPlayerById(targetPlayerId);

        if (challenger == null) return CombatResult.failure("挑战者角色不存在");
        if (target == null) return CombatResult.failure("对手角色不存在");
        if (challengerPlayerId == targetPlayerId) return CombatResult.failure("不能挑战自己");
        if (challenger.getHp() <= 0) return CombatResult.failure("你的生命值不足，无法发起挑战");
        if (target.getHp() <= 0) return CombatResult.failure("对手已阵亡，无法挑战");

        // 安全区禁止私斗（放在每日次数校验之前，避免被安全区拦下还白扣一次挑战次数）
        String safeZoneError = checkSafeZone(challengerPlayerId, targetPlayerId);
        if (safeZoneError != null) return CombatResult.failure(safeZoneError);

        // PVP 每日次数限制（发起方）
        if (!canParticipatePvp(challengerPlayerId)) {
            return CombatResult.failure("今日切磋次数已用完（每天最多 " + DAILY_PVP_LIMIT + " 次），明日再来吧");
        }
        recordPvpParticipation(challengerPlayerId);

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

    public CombatResult acceptChallenge(long playerId) {
        PendingChallenge challenge = pendingChallenges.remove(playerId);
        if (challenge == null || challenge.isExpired()) {
            return CombatResult.failure("没有待接受的挑战，可能已超时");
        }
        if (challenge.targetPlayerId != playerId) {
            return CombatResult.failure("你无法接受此挑战");
        }
        // 安全区禁止私斗：应战方或挑战方任一方在安全区都打不起来
        String safeZoneError = checkSafeZone(challenge.challengerPlayerId, challenge.targetPlayerId);
        if (safeZoneError != null) return CombatResult.failure(safeZoneError);
        // PVP 每日次数限制（接受方）
        if (!canParticipatePvp(playerId)) {
            return CombatResult.failure("今日切磋次数已用完（每天最多 " + DAILY_PVP_LIMIT + " 次），明日再来吧");
        }
        recordPvpParticipation(playerId);
        return executePvp(challenge.challengerPlayerId, challenge.targetPlayerId);
    }

    public String rejectChallenge(long targetPlayerId) {
        PendingChallenge challenge = pendingChallenges.remove(targetPlayerId);
        if (challenge == null || challenge.isExpired()) {
            return null;
        }
        return challenge.challengerName;
    }

    public CombatResult pvpChallenge(long challengerPlayerId, long targetPlayerId) {
        // 安全区禁止私斗（先于每日次数校验，避免被拦下还白扣次数）
        String safeZoneError = checkSafeZone(challengerPlayerId, targetPlayerId);
        if (safeZoneError != null) return CombatResult.failure(safeZoneError);
        // PVP 每日次数限制（发起方）
        if (!canParticipatePvp(challengerPlayerId)) {
            return CombatResult.failure("今日切磋次数已用完（每天最多 " + DAILY_PVP_LIMIT + " 次），明日再来吧");
        }
        recordPvpParticipation(challengerPlayerId);
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

        // 兜底：任何入口（REST / WS / 适配器 / 插件）走到实际开打前都再校验一次安全区
        String safeZoneError = checkSafeZone(challengerPlayerId, targetPlayerId);
        if (safeZoneError != null) return CombatResult.failure(safeZoneError);

        List<Skill> challengerSkills = skillService.getPlayerSkills(challenger.getId());
        List<Skill> targetSkills = skillService.getPlayerSkills(target.getId());

        Set<Long> challengerUsedSkillIds = new HashSet<>();
        Set<Long> targetUsedSkillIds = new HashSet<>();
        SkillCooldown challengerCooldown = new SkillCooldown();
        SkillCooldown targetCooldown = new SkillCooldown();

        // 双方真实 HP/MP 状态（回合内实时修改）；功法战斗加成战前各取一次，避免每回合查库
        double chalDmgMult = 1 + ServiceRegistry.getTechniqueService().getEquippedCombatDamageBonus(challenger.getId());
        double chalRed = ServiceRegistry.getTechniqueService().getEquippedDamageReduction(challenger.getId());
        int chalFormationRed = ServiceRegistry.getFormationService().getTotalDefenseBonus(challenger.getId());
        double tgtDmgMult = 1 + ServiceRegistry.getTechniqueService().getEquippedCombatDamageBonus(target.getId());
        double tgtRed = ServiceRegistry.getTechniqueService().getEquippedDamageReduction(target.getId());
        int tgtFormationRed = ServiceRegistry.getFormationService().getTotalDefenseBonus(target.getId());
        BattleState challengerState = new BattleState(challenger.getHp(), challenger.getMp(), 0, chalDmgMult, chalRed, chalFormationRed);
        BattleState targetState = new BattleState(target.getHp(), target.getMp(), 0, tgtDmgMult, tgtRed, tgtFormationRed);

        String challengerTactic = getTactic(challenger);
        String targetTactic = getTactic(target);

        BattleLogGenerator logGenerator = new BattleLogGenerator();
        logGenerator.addVs(challenger.getName(), target.getName());

        SpiritualRoot challengerRoot = challenger.getSpiritualRoot();
        SpiritualRoot targetRoot = target.getSpiritualRoot();
        // 开局运转防御型技能（金刚护体等）
        tryUseDefensiveSkill(challenger, challengerSkills, challengerState, challengerUsedSkillIds, logGenerator,
                challenger.getName(), challengerRoot, challengerCooldown, 0);
        tryUseDefensiveSkill(target, targetSkills, targetState, targetUsedSkillIds, logGenerator,
                target.getName(), targetRoot, targetCooldown, 0);

        boolean challengerFirst = playerService.getFinalSpeed(challenger) >= playerService.getFinalSpeed(target);
        int maxRounds = 20;
        int round = 0;
        String winner = null;

        int[] mpOut = new int[1];

        while (round < maxRounds && challengerState.playerHp > 0 && targetState.playerHp > 0) {
            round++;
            applyRegeneration(challenger, challengerRoot, challengerState);
            applyRegeneration(target, targetRoot, targetState);

            logGenerator.addRound(round);

            if (challengerFirst) {
                winner = processPvpRound(logGenerator, challenger, target, challengerSkills, challengerUsedSkillIds,
                        challengerState, targetState, challengerTactic, mpOut, winner,
                        challengerRoot, challengerCooldown, round);
                if (winner != null) break;

                winner = processPvpRound(logGenerator, target, challenger, targetSkills, targetUsedSkillIds,
                        targetState, challengerState, targetTactic, mpOut, winner,
                        targetRoot, targetCooldown, round);
                if (winner != null) break;
            } else {
                winner = processPvpRound(logGenerator, target, challenger, targetSkills, targetUsedSkillIds,
                        targetState, challengerState, targetTactic, mpOut, winner,
                        targetRoot, targetCooldown, round);
                if (winner != null) break;

                winner = processPvpRound(logGenerator, challenger, target, challengerSkills, challengerUsedSkillIds,
                        challengerState, targetState, challengerTactic, mpOut, winner,
                        challengerRoot, challengerCooldown, round);
                if (winner != null) break;
            }
        }

        if (winner == null) {
            winner = determinePvpWinner(challengerState.playerHp, targetState.playerHp, challenger.getName(), target.getName());
        }

        boolean challengerWon = winner.equals(challenger.getName());
        boolean isTie = winner.equals("平局");
        Player actualWinner = challengerWon ? challenger : (isTie ? null : target);
        Player actualLoser = challengerWon ? target : (isTie ? null : challenger);
        long expReward = actualWinner != null ? calculateExpReward(actualWinner, actualLoser, challengerWon, isTie) : 0;
        long goldReward = actualWinner != null ? random.nextLong(50, 200) : 0;
        // 今日天象战斗收益倍率（星辰耀天 ×1.2 / 血月当空 ×1.3 / 万籁俱寂 ×1.05）
        if (actualWinner != null) {
            expReward = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic().applyCombat(expReward);
            goldReward = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic().applyCombat(goldReward);
        }

        if (actualWinner != null && expReward > 0) playerService.addExperience(actualWinner.getId(), expReward);
        if (actualWinner != null && goldReward > 0) playerService.addGold(actualWinner.getId(), goldReward);

        // 危险度分级的 PVP 后果：险地（危险度≥3）里胜者可以掠夺败者的灵石。
        // 放在经验/金币之后、胜负日志之前，这样掠夺记录会出现在战斗日志里。
        long pvpLoot = actualWinner != null && !isTie
                ? grantDangerZonePvpLoot(actualWinner, actualLoser, logGenerator)
                : 0;

        // PVP 胜场计数 + 称号检查（擂台王者）
        if (actualWinner != null && !isTie) {
            try {
                playerService.incrementPvpWins(actualWinner.getId());
                actualWinner.setPvpWins(actualWinner.getPvpWins() + 1);
                new com.mtxgdn.game.service.TitleService().checkAndGrantTitles(actualWinner);
            } catch (Exception ignored) {
            }
        }

        addSkillProficiency(challenger.getId(), challengerUsedSkillIds);
        addSkillProficiency(target.getId(), targetUsedSkillIds);

        ServiceRegistry.getDailyService().onBattle(challenger.getId());
        ServiceRegistry.getDailyService().onBattle(target.getId());

        // 装备磨损：PVP 对双方都扣耐久
        try {
            ServiceRegistry.getDurabilityService().applyWear(challenger.getId(), DurabilityService.getPvpWear());
            ServiceRegistry.getDurabilityService().applyWear(target.getId(), DurabilityService.getPvpWear());
        } catch (Exception ignored) {
        }

        // 持久化双方真实战后 HP/MP（不再用战前旧值覆盖）
        challenger.setHp(Math.max(0, challengerState.playerHp));
        challenger.setMp(Math.max(0, challengerState.playerMp));
        target.setHp(Math.max(0, targetState.playerHp));
        target.setMp(Math.max(0, targetState.playerMp));
        playerService.updatePlayer(challenger.getId(), challenger);
        playerService.updatePlayer(target.getId(), target);

        logGenerator.addRounds(round);
        if (challengerWon) {
            logGenerator.addVictory(challenger.getName());
            logGenerator.addReward(expReward, goldReward);
        } else if (!isTie) {
            logGenerator.addVictory(target.getName());
            logGenerator.addReward(expReward, goldReward);
        } else {
            logGenerator.addDraw();
        }

        CombatResult combatResult = buildCombatResult(true, winner, challengerWon, isTie,
                challenger.getName(), target.getName(),
                challengerState.playerHp, targetState.playerHp, round, expReward, goldReward, logGenerator.getLogs());
        combatResult.setSpiritStonesLooted(pvpLoot);

        // 插件事件：PVP 战斗结束
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMBAT_ENDED)
                        .data("playerId", challengerPlayerId)
                        .data("targetPlayerId", targetPlayerId)
                        .data("winner", winner)
                        .build());
        return combatResult;
    }

    private String processPvpRound(BattleLogGenerator logGenerator, Player attacker, Player defender,
                                    List<Skill> skills, Set<Long> usedSkillIds,
                                    BattleState attackerState, BattleState defenderState, String tactic,
                                    int[] mpOut, String currentWinner,
                                    SpiritualRoot attackerRoot, SkillCooldown cooldown, int round) {
        // 血量低于 50% 且法力充足时优先使用治疗技能
        if (tryUseHealSkill(attacker, skills, attackerState, usedSkillIds, logGenerator,
                attacker.getName(), mpOut, attackerRoot, cooldown, round)) {
            return currentWinner;
        }

        int damage = calculatePvpDamage(attacker, defender, skills, attackerState.playerMp, logGenerator,
                attacker.getName(), defender.getName(), usedSkillIds, mpOut, tactic, cooldown, round);
        attackerState.playerMp = Math.max(0, attackerState.playerMp - mpOut[0]);
        // 功法战斗伤害加成（攻击方已装备功法的 combat_damage_bonus 之和）
        if (attackerState.techDamageMult > 1.0) {
            damage = (int) (damage * attackerState.techDamageMult);
        }
        // 防守方减伤：防御型技能减伤百分比 + 功法 damage_reduction 减伤
        damage = applyDamageReduction(damage, defenderState.damageReductionPercent);
        if (defenderState.techReduction > 0) {
            damage = Math.max(1, (int) (damage * (1 - defenderState.techReduction)));
        }
        // 阵法防御加成（% 减伤，护山大阵/迷踪阵）
        if (defenderState.formationReductionPct > 0) {
            damage = Math.max(1, (int) (damage * (1 - defenderState.formationReductionPct / 100.0)));
        }
        defenderState.playerHp -= damage;
        logGenerator.addDamage(defender.getName(), damage, defenderState.playerHp, defender.getMaxHp());

        if (defenderState.playerHp <= 0) {
            defenderState.playerHp = 0;
            return attacker.getName();
        }

        // 伤害反弹：防守方带反弹 Buff 时反弹部分伤害给攻击方
        int reflectPercent = ServiceRegistry.getBuffService().getReflectionPercent(defender.getId());
        if (reflectPercent > 0 && damage > 0) {
            int reflected = Math.max(1, damage * reflectPercent / 100);
            attackerState.playerHp -= reflected;
            logGenerator.addDamage(attacker.getName(), reflected, attackerState.playerHp, attacker.getMaxHp());
            if (attackerState.playerHp <= 0) {
                attackerState.playerHp = 0;
                return defender.getName();
            }
        }

        return currentWinner;
    }

    private int calculatePvpDamage(Player attacker, Player defender, List<Skill> skills, int currentMp,
                                    BattleLogGenerator logGenerator, String attackerName, String defenderName,
                                    Set<Long> usedSkillIds, int[] mpCostOut, String battleTactic,
                                    SkillCooldown cooldown, int round) {
        SpiritualRoot attackerRoot = attacker.getSpiritualRoot();
        SpiritualRoot defenderRoot = defender.getSpiritualRoot();

        boolean isAggressive = "aggressive".equals(battleTactic);
        boolean isDefensive = "defensive".equals(battleTactic);
        String tacticText = isAggressive ? "猛攻" : isDefensive ? "沉稳" : "";

        int mpReserve = isDefensive ? Math.max(0, currentMp / 2) : 0;

        Skill attackSkill = selectAttackSkill(skills, currentMp, mpReserve, attackerRoot, isAggressive, cooldown, round);

        int baseDamage;
        if (attackSkill != null) {
            baseDamage = calculateSkillDamage(attackSkill, attackerRoot, mpCostOut);
            logGenerator.addSkillUse(attackerName, attackSkill.getName(), attackSkill.getLevel(), false, tacticText);
            usedSkillIds.add(attackSkill.getId());
            cooldown.markUsed(attackSkill, round);
        } else {
            mpCostOut[0] = 0;
            baseDamage = playerService.getFinalAttack(attacker);
            logGenerator.addNormalAttack(attackerName, false);
        }

        baseDamage = applyDamageBoosts(baseDamage, attackerRoot);

        int defense = playerService.getFinalDefense(defender);
        // 暴击率随速度收益递减，上限 60%（原 speed/200 后期必暴击）
        double critChance = Math.min(0.6, playerService.getFinalSpeed(attacker) / 400.0);
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
        }

        int variance = random.nextInt(-5, 6);
        int finalDamage = Math.max(1, baseDamage + variance - defense / 3);

        if (defenderRoot != null && defenderRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            finalDamage = (int)(finalDamage * (1 - defenderRoot.getEffectValue()));
        }

        return Math.max(1, finalDamage);
    }

    private Skill selectAttackSkill(List<Skill> skills, int currentMp, int mpReserve,
                                     SpiritualRoot attackerRoot, boolean isAggressive,
                                     SkillCooldown cooldown, int round) {
        if (skills.isEmpty() || currentMp <= mpReserve) {
            return null;
        }

        List<Skill> atkSkills = new ArrayList<>();
        for (Skill skill : skills) {
            if (!skill.isAttackSkill()) continue;
            int scaledMpCost = calculateScaledMpCost(skill, attackerRoot);
            if (scaledMpCost <= currentMp && cooldown.isReady(skill, round)) {
                atkSkills.add(skill);
            }
        }

        if (atkSkills.isEmpty()) {
            return null;
        }

        // 默认（均衡）与猛攻都优先选择伤害最高的可用技能；防守由 mpReserve 控制保留法力
        atkSkills.sort((a, b) -> Integer.compare(
                calculateScaledDamage(b), calculateScaledDamage(a)));

        return atkSkills.get(0);
    }

    private int calculateScaledDamage(Skill skill) {
        return skill.getDamage() + (int)(skill.getDamage() * (skill.getLevel() - 1) * 0.15);
    }

    private int calculateScaledMpCost(Skill skill, SpiritualRoot attackerRoot) {
        int scaledMpCost = skill.getMpCost() + (int)(skill.getMpCost() * (skill.getLevel() - 1) * 0.15);
        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.MP_COST_REDUCTION)) {
            scaledMpCost = (int)(scaledMpCost * (1 - attackerRoot.getEffectValue()));
        }
        return scaledMpCost;
    }

    private int calculateSkillDamage(Skill skill, SpiritualRoot attackerRoot, int[] mpCostOut) {
        int skillLevel = skill.getLevel();
        int scaledMpCost = calculateScaledMpCost(skill, attackerRoot);
        mpCostOut[0] = scaledMpCost;

        int baseDamage = skill.getDamage() + (int)(skill.getDamage() * (skillLevel - 1) * 0.15);
        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.SKILL_DAMAGE)) {
            baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
        }
        return baseDamage;
    }

    private int applyDamageBoosts(int baseDamage, SpiritualRoot attackerRoot) {
        if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_BOOST)) {
            baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
        }
        return baseDamage;
    }

    private void applyRegeneration(Player player, SpiritualRoot root, BattleState state) {
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
            int regen = (int)(player.getMaxHp() * root.getEffectValue());
            state.playerHp = Math.min(player.getMaxHp(), state.playerHp + regen);
        }
    }

    /**
     * 血量低于 50% 且法力充足时使用治疗技能（回春术/甘霖普降等），返回是否使用了治疗。
     */
    private boolean tryUseHealSkill(Player player, List<Skill> skills, BattleState state,
                                    Set<Long> usedSkillIds, BattleLogGenerator logGenerator,
                                    String attackerName, int[] mpCostOut, SpiritualRoot root,
                                    SkillCooldown cooldown, int round) {
        if (skills == null || skills.isEmpty()) return false;
        if (state.playerHp > player.getMaxHp() / 2) return false;
        for (Skill skill : skills) {
            if (!skill.isHealSkill()) continue;
            int scaledMpCost = calculateScaledMpCost(skill, root);
            if (scaledMpCost <= state.playerMp && cooldown.isReady(skill, round)) {
                state.playerMp -= scaledMpCost;
                int healAmount = skill.getHealAmount() + (int)(skill.getHealAmount() * (skill.getLevel() - 1) * 0.15);
                state.playerHp = Math.min(player.getMaxHp(), state.playerHp + healAmount);
                logGenerator.addSkillUse(attackerName, skill.getName(), skill.getLevel(), false, "治疗");
                usedSkillIds.add(skill.getId());
                mpCostOut[0] = scaledMpCost;
                cooldown.markUsed(skill, round);
                return true;
            }
        }
        return false;
    }

    private String determinePvpWinner(int challengerHp, int targetHp, String challengerName, String targetName) {
        if (challengerHp > targetHp) return challengerName;
        else if (targetHp > challengerHp) return targetName;
        else return "平局";
    }

    private long calculateExpReward(Player winner, Player loser, boolean challengerWon, boolean isTie) {
        if (isTie) return 0;
        int realmDiff = winner.getRealm() - loser.getRealm();
        long baseExp = 50 + loser.getLevel() * 10L;
        if (realmDiff < 0) {
            baseExp *= 2;
        }
        return baseExp + random.nextLong(0, 30);
    }

    private void addSkillProficiency(long playerId, Set<Long> usedSkillIds) {
        for (long skillId : usedSkillIds) {
            skillService.addProficiency(playerId, skillId, 15);
        }
    }

    private CombatResult buildCombatResult(boolean success, String winner, boolean challengerWon, boolean isTie,
                                           String challengerName, String targetName,
                                           int challengerHp, int targetHp, int rounds,
                                           long expReward, long goldReward, List<String> battleLog) {
        CombatResult result = new CombatResult();
        result.setSuccess(success);
        result.setWinner(winner);
        result.setChallengerWon(challengerWon);
        result.setChallengerName(challengerName);
        result.setTargetName(targetName);
        result.setChallengerRemainingHp(Math.max(0, challengerHp));
        result.setTargetRemainingHp(Math.max(0, targetHp));
        result.setTotalRounds(rounds);
        result.setExpReward(expReward);
        result.setGoldReward(goldReward);
        result.setBattleLog(battleLog);
        return result;
    }

    private String getTactic(Player player) {
        return player.getBattleStrategy() != null ? player.getBattleStrategy() : "balanced";
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
        SkillCooldown cooldown = new SkillCooldown();

        // 真实战斗状态：HP/MP 在回合中实时更新，避免值拷贝导致判定失效；功法/阵法加成战前取一次
        double playerDmgMult = 1 + ServiceRegistry.getTechniqueService().getEquippedCombatDamageBonus(player.getId());
        double playerRed = ServiceRegistry.getTechniqueService().getEquippedDamageReduction(player.getId());
        int playerFormationRed = ServiceRegistry.getFormationService().getTotalDefenseBonus(player.getId());
        BattleState state = new BattleState(player.getHp(), player.getMp(), monster.getHp(), playerDmgMult, playerRed, playerFormationRed);
        int initialHp = player.getHp();
        int initialMp = player.getMp();

        BattleLogGenerator logGenerator = new BattleLogGenerator();
        if (monster.isBoss()) {
            logGenerator.addBossAppearance(monster.getName(), monster.getDescription());
        } else {
            logGenerator.addMonsterAppearance(monster.getName(), monster.getDescription(),
                    monster.getAttack(), monster.getDefense(), monster.getHp());
        }

        SpiritualRoot playerRoot = player.getSpiritualRoot();
        // 开局自动运转防御型功法技能（金刚护体等，本场战斗减伤）
        tryUseDefensiveSkill(player, playerSkills, state, usedSkillIds, logGenerator,
                player.getName(), playerRoot, cooldown, 0);

        boolean playerFirst = playerService.getFinalSpeed(player) >= monster.getSpeed();
        int maxRounds = 30;
        int round = 0;
        boolean playerWon = false;

        int[] mpOut = new int[1];

        while (round < maxRounds && state.playerHp > 0 && state.monsterHp > 0) {
            round++;

            if (playerRoot != null && playerRoot.hasEffect(SpiritualRoot.SpecialEffect.REGENERATION)) {
                int regen = (int)(player.getMaxHp() * playerRoot.getEffectValue());
                state.playerHp = Math.min(player.getMaxHp(), state.playerHp + regen);
            }

            if (playerFirst) {
                playerWon = processPveRoundPlayerFirst(logGenerator, player, monster, playerSkills, usedSkillIds,
                        state, mpOut, playerRoot, cooldown, round);
            } else {
                playerWon = processPveRoundMonsterFirst(logGenerator, player, monster, playerSkills, usedSkillIds,
                        state, mpOut, playerRoot, cooldown, round);
            }

            if (playerWon || state.playerHp <= 0) break;
        }

        // 装备磨损：打一场扣一次耐久（PVE 与 PVP 消耗量分开配置）
        try {
            ServiceRegistry.getDurabilityService().applyWear(playerId, DurabilityService.getPveWear());
        } catch (Exception ignored) {
        }

        PveCombatResult result = buildPveResult(player, monster, playerWon, state.playerHp, state.monsterHp, state.playerMp, round,
                playerSkills, usedSkillIds, initialHp, initialMp, logGenerator.getLogs());

        // 插件事件：PVE 战斗结束
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMBAT_ENDED)
                        .data("playerId", playerId)
                        .data("monsterName", monster.getName())
                        .data("playerWon", playerWon)
                        .build());
        return result;
    }

    private boolean processPveRoundPlayerFirst(BattleLogGenerator logGenerator, Player player, Monster monster,
                                               List<Skill> skills, Set<Long> usedSkillIds,
                                               BattleState state, int[] mpOut, SpiritualRoot playerRoot,
                                               SkillCooldown cooldown, int round) {
        // 血量过低时优先治疗
        if (tryUseHealSkill(player, skills, state, usedSkillIds, logGenerator,
                player.getName(), mpOut, playerRoot, cooldown, round)) {
            return false;
        }

        int damage = calculatePlayerDamageToMonster(player, monster, skills, state.playerMp, logGenerator,
                player.getName(), monster.getName(), usedSkillIds, mpOut, cooldown, round);
        state.playerMp = Math.max(0, state.playerMp - mpOut[0]);
        // 功法战斗伤害加成（combat_damage_bonus 之和）
        if (state.techDamageMult > 1.0) {
            damage = (int) (damage * state.techDamageMult);
        }
        state.monsterHp -= damage;
        logGenerator.addMonsterDamage(monster.getName(), damage, state.monsterHp);

        if (state.monsterHp <= 0) {
            return true;
        }

        int mDamage = calculateMonsterDamageToPlayer(monster, player, logGenerator,
                monster.getName(), player.getName());
        mDamage = applyDamageReduction(mDamage, state.damageReductionPercent);
        // 功法减伤（damage_reduction 之和）
        if (state.techReduction > 0) {
            mDamage = Math.max(1, (int) (mDamage * (1 - state.techReduction)));
        }
        // 阵法防御加成（% 减伤，护山大阵/迷踪阵）
        if (state.formationReductionPct > 0) {
            mDamage = Math.max(1, (int) (mDamage * (1 - state.formationReductionPct / 100.0)));
        }
        state.playerHp -= mDamage;
        logGenerator.addPlayerDamage(mDamage, state.playerHp);

        // 伤害反弹：反弹丹 Buff 将部分怪物伤害反弹给怪物
        if (reflectMonsterDamage(player, monster, mDamage, state, logGenerator)) {
            return true;
        }

        return false;
    }

    private boolean processPveRoundMonsterFirst(BattleLogGenerator logGenerator, Player player, Monster monster,
                                                 List<Skill> skills, Set<Long> usedSkillIds,
                                                 BattleState state, int[] mpOut, SpiritualRoot playerRoot,
                                                 SkillCooldown cooldown, int round) {
        int mDamage = calculateMonsterDamageToPlayer(monster, player, logGenerator,
                monster.getName(), player.getName());
        mDamage = applyDamageReduction(mDamage, state.damageReductionPercent);
        // 功法减伤（damage_reduction 之和）
        if (state.techReduction > 0) {
            mDamage = Math.max(1, (int) (mDamage * (1 - state.techReduction)));
        }
        // 阵法防御加成（% 减伤，护山大阵/迷踪阵）
        if (state.formationReductionPct > 0) {
            mDamage = Math.max(1, (int) (mDamage * (1 - state.formationReductionPct / 100.0)));
        }
        state.playerHp -= mDamage;
        logGenerator.addPlayerDamage(mDamage, state.playerHp);

        if (state.playerHp <= 0) {
            return false;
        }

        // 伤害反弹：怪物先攻时反弹可能直接反杀怪物
        if (reflectMonsterDamage(player, monster, mDamage, state, logGenerator)) {
            return true;
        }

        if (tryUseHealSkill(player, skills, state, usedSkillIds, logGenerator,
                player.getName(), mpOut, playerRoot, cooldown, round)) {
            return false;
        }

        int damage = calculatePlayerDamageToMonster(player, monster, skills, state.playerMp, logGenerator,
                player.getName(), monster.getName(), usedSkillIds, mpOut, cooldown, round);
        state.playerMp = Math.max(0, state.playerMp - mpOut[0]);
        // 功法战斗伤害加成（combat_damage_bonus 之和）
        if (state.techDamageMult > 1.0) {
            damage = (int) (damage * state.techDamageMult);
        }
        state.monsterHp -= damage;
        logGenerator.addMonsterDamage(monster.getName(), damage, state.monsterHp);

        return state.monsterHp <= 0;
    }

    /** 按减伤百分比折算伤害 */
    private int applyDamageReduction(int damage, int reductionPercent) {
        if (damage <= 0 || reductionPercent <= 0) {
            return damage;
        }
        return Math.max(1, damage * (100 - Math.min(90, reductionPercent)) / 100);
    }

    /**
     * 防御型技能（skill_type=2，如金刚护体/风驰电掣）：运转后本场战斗减伤，
     * 减伤百分比 = 蓝耗 × 0.5%（上限 30%）。
     */
    private void tryUseDefensiveSkill(Player player, List<Skill> skills, BattleState state,
                                      Set<Long> usedSkillIds, BattleLogGenerator logGenerator,
                                      String attackerName, SpiritualRoot root,
                                      SkillCooldown cooldown, int round) {
        if (skills == null) return;
        for (Skill skill : skills) {
            if (skill.getSkillType() != 2) continue;
            int cost = calculateScaledMpCost(skill, root);
            if (cost <= state.playerMp && cooldown.isReady(skill, round)) {
                state.playerMp -= cost;
                int reduction = Math.min(30, cost * 50 / 100);
                state.damageReductionPercent = Math.max(state.damageReductionPercent, reduction);
                logGenerator.addSkillUse(attackerName, skill.getName(), skill.getLevel(), false, "护体");
                usedSkillIds.add(skill.getId());
                cooldown.markUsed(skill, round);
            }
        }
    }

    /** 伤害反弹：将怪物造成的部分伤害反弹给怪物，返回怪物是否被反杀 */
    private boolean reflectMonsterDamage(Player player, Monster monster, int mDamage,
                                         BattleState state, BattleLogGenerator logGenerator) {
        int reflectPercent = ServiceRegistry.getBuffService().getReflectionPercent(player.getId());
        if (reflectPercent <= 0 || state.monsterHp <= 0 || mDamage <= 0) {
            return false;
        }
        int reflected = Math.max(1, mDamage * reflectPercent / 100);
        state.monsterHp -= reflected;
        logGenerator.addMonsterDamage(monster.getName(), reflected, state.monsterHp);
        return state.monsterHp <= 0;
    }

    private int calculatePlayerDamageToMonster(Player attacker, Monster defender, List<Skill> skills,
                                               int currentMp, BattleLogGenerator logGenerator, String attackerName,
                                               String defenderName, Set<Long> usedSkillIds, int[] mpCostOut,
                                               SkillCooldown cooldown, int round) {
        SpiritualRoot attackerRoot = attacker.getSpiritualRoot();

        Skill attackSkill = null;
        if (!skills.isEmpty() && currentMp > 0) {
            for (Skill skill : skills) {
                int scaledMpCost = calculateScaledMpCost(skill, attackerRoot);
                if (skill.isAttackSkill() && scaledMpCost <= currentMp && cooldown.isReady(skill, round)) {
                    attackSkill = skill;
                    break;
                }
            }
        }

        int baseDamage;
        if (attackSkill != null) {
            int skillLevel = attackSkill.getLevel();
            int scaledMpCost = calculateScaledMpCost(attackSkill, attackerRoot);
            baseDamage = attackSkill.getDamage() + (int)(attackSkill.getDamage() * (skillLevel - 1) * 0.15);
            if (attackerRoot != null && attackerRoot.hasEffect(SpiritualRoot.SpecialEffect.SKILL_DAMAGE)) {
                baseDamage = (int)(baseDamage * (1 + attackerRoot.getEffectValue()));
            }
            logGenerator.addPlayerSkillUse(attackerName, attackSkill.getName(), skillLevel, scaledMpCost);
            usedSkillIds.add(attackSkill.getId());
            mpCostOut[0] = scaledMpCost;
            cooldown.markUsed(attackSkill, round);
        } else {
            mpCostOut[0] = 0;
            baseDamage = playerService.getFinalAttack(attacker) + playerService.getFinalSpirit(attacker);
            logGenerator.addPlayerNormalAttack(attackerName);
        }

        baseDamage = applyDamageBoosts(baseDamage, attackerRoot);

        // 暴击率随速度收益递减，上限 60%
        double critChance = Math.min(0.6, playerService.getFinalSpeed(attacker) / 400.0);
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
            logGenerator.addCrit();
        }

        int variance = random.nextInt(-5, 6);
        return Math.max(1, baseDamage + variance - defender.getDefense() / 3);
    }

    private int calculateMonsterDamageToPlayer(Monster attacker, Player defender, BattleLogGenerator logGenerator,
                                               String attackerName, String defenderName) {
        return calculateMonsterDamageToPlayer(attacker, defender, logGenerator, attackerName, defenderName, 0);
    }

    private int calculateMonsterDamageToPlayer(Monster attacker, Player defender, BattleLogGenerator logGenerator,
                                               String attackerName, String defenderName, double debuff) {
        SpiritualRoot defenderRoot = defender.getSpiritualRoot();

        int baseDamage = attacker.getAttack();
        int variance = random.nextInt(-4, 5);
        int adjustedDefense = (int)(playerService.getFinalDefense(defender) * (1 - debuff));
        int finalDamage = Math.max(1, baseDamage + variance - adjustedDefense / 3);

        if (defenderRoot != null && defenderRoot.hasEffect(SpiritualRoot.SpecialEffect.DAMAGE_REDUCTION)) {
            finalDamage = (int)(finalDamage * (1 - defenderRoot.getEffectValue()));
        }

        return Math.max(1, finalDamage);
    }

    private PveCombatResult buildPveResult(Player player, Monster monster, boolean playerWon,
                                            int playerHp, int monsterHp, int playerMp, int rounds,
                                            List<Skill> playerSkills, Set<Long> usedSkillIds,
                                            int initialHp, int initialMp,
                                            List<String> battleLog) {
        PveCombatResult result = new PveCombatResult();
        result.setSuccess(true);
        result.setMonsterName(monster.getName());
        result.setBoss(monster.isBoss());
        result.setPlayerHpRemaining(Math.max(0, playerHp));
        result.setMonsterHpRemaining(Math.max(0, monsterHp));
        result.setTotalRounds(rounds);

        ItemService itemService = ServiceRegistry.getItemService();

        if (playerWon) {
            result.setPlayerWon(true);

            long expReward = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic()
                    .applyCombat(monster.getExpReward());
            SpiritualRoot root = player.getSpiritualRoot();
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.MONSTER_EXP)) {
                expReward = (long)(expReward * (1 + root.getEffectValue()));
            }
            playerService.addExperience(player.getId(), expReward);
            result.setExpGained(expReward);

            long goldReward = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic()
                    .applyCombat(monster.getGoldReward());
            if (goldReward > 0) {
                playerService.addGold(player.getId(), goldReward);
                result.setGoldGained(goldReward);
            }

            long ssReward = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic()
                    .applySpiritStone(monster.getSpiritStoneReward());
            if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.SPIRIT_STONE_DROP)) {
                ssReward = (long)(ssReward * (1 + root.getEffectValue()));
            }
            if (ssReward > 0) {
                itemService.addSpiritStones(player.getId(), ssReward);
                result.setSpiritStonesGained(ssReward);
            }

            // 全局季节掉落倍率（秋季丰收等）
            double dropChance = monster.getLootChance() * SeasonService.getDropMultiplier();
            if (random.nextDouble() < dropChance) {
                String[] lootTable = monster.getLootTable();
                if (lootTable != null && lootTable.length > 0) {
                    String lootItem = lootTable[random.nextInt(lootTable.length)];
                    // 兼容带/不带命名空间前缀的 key（addItem 内部会自动补全 mtxgdn: 前缀）
                    if (ItemRegistry.resolve(lootItem) != null) {
                        itemService.addItem(player.getId(), lootItem, 1);
                        result.setItemGained(lootItem);
                        result.setItemQuantity(1);
                    }
                }
            }

            if (monster.isBoss()) {
                result.setMessage("击败了Boss【" + monster.getName() + "】");
            } else {
                StringBuilder msgBuilder = new StringBuilder("击败了【" + monster.getName() + "】，获得 " + expReward + " 经验");
                if (goldReward > 0) msgBuilder.append(", ").append(goldReward).append(" 金币");
                if (ssReward > 0) msgBuilder.append(", ").append(ssReward).append(" 灵石");
                if (result.getItemGained() != null) msgBuilder.append(", 掉落物品");
                result.setMessage(msgBuilder.toString());
            }
        } else {
            result.setPlayerWon(false);
            int hpLost = Math.max(0, initialHp - Math.max(0, playerHp));
            result.setMessage("被【" + monster.getName() + "】击败，损失了 " + hpLost + " 点生命值");
        }

        addSkillProficiency(player.getId(), usedSkillIds);
        // 持久化真实战后 HP/MP
        player.setHp(Math.max(0, playerHp));
        player.setMp(Math.max(0, playerMp));
        playerService.updatePlayer(player.getId(), player);

        result.setBattleLog(battleLog);
        return result;
    }

    public RaidCombatResult raidBossFight(List<Long> playerIds, List<BossForm> bossForms) {
        if (playerIds == null || playerIds.isEmpty()) {
            return RaidCombatResult.failure("团队成员不能为空");
        }

        List<Player> players = validateRaidPlayers(playerIds);
        if (players == null) {
            return RaidCombatResult.failure("团队中存在无效或已阵亡的成员");
        }

        int bossTotalHp = calculateBossTotalHp(bossForms);
        int bossCurrentHp = bossTotalHp;
        BossForm currentForm = bossForms.get(0);
        int currentFormIndex = 0;

        Monster boss = createBossMonster(currentForm, currentFormIndex, bossForms);

        BattleLogGenerator logGenerator = new BattleLogGenerator();
        logGenerator.addBossAppearance(boss.getName(), boss.getDescription());
        logGenerator.add("提示：进入Boss战，玩家属性削弱" + (int)(currentForm.getPlayerDebuffPercent() * 100) + "%");
        logGenerator.add("---");

        Map<Long, Integer> playerHpMap = initPlayerHpMap(players);
        Map<Long, Integer> playerMpMap = initPlayerMpMap(players);
        Map<Long, List<Skill>> playerSkillsMap = initPlayerSkillsMap(players);
        Map<Long, Set<Long>> playerUsedSkills = initPlayerUsedSkillsMap(players);
        Map<Long, SkillCooldown> raidCooldowns = new HashMap<>();
        Map<Long, String> playerNames = initPlayerNamesMap(players);
        // 各成员功法战斗加成（伤害倍率、减伤比例），战前一次读齐
        Map<Long, double[]> playerTech = initPlayerTechMap(players);

        long lastHitPlayerId = -1;
        String lastHitPlayerName = playerNames.getOrDefault(lastHitPlayerId, "");
        int round = 0;
        int maxRounds = 50;

        while (round < maxRounds && bossCurrentHp > 0 && hasAlivePlayers(playerHpMap)) {
            round++;
            logGenerator.addRound(round);

            for (Player player : players) {
                long playerId = player.getId();
                if (playerHpMap.get(playerId) <= 0) continue;

                double debuff = currentForm.getPlayerDebuffPercent();
                int[] mpOut = new int[1];
                SkillCooldown cooldown = raidCooldowns.computeIfAbsent(playerId, k -> new SkillCooldown());
                int damage = calculatePlayerDamageToMonster(player, boss, playerSkillsMap.get(playerId),
                        playerMpMap.get(playerId), logGenerator, player.getName(), boss.getName(),
                        playerUsedSkills.get(playerId), mpOut, cooldown, round);

                damage = (int)(damage * (1 - debuff));
                // 功法战斗伤害加成（combat_damage_bonus 之和）
                double[] tech = playerTech.get(playerId);
                if (tech != null && tech[0] > 1.0) {
                    damage = (int)(damage * tech[0]);
                }
                playerMpMap.put(playerId, Math.max(0, playerMpMap.get(playerId) - mpOut[0]));
                bossCurrentHp -= damage;

                logGenerator.addTeamPlayerAttack(player.getName(), boss.getName(), damage);

                if (bossCurrentHp <= 0) {
                    lastHitPlayerId = playerId;
                    lastHitPlayerName = player.getName();
                    break;
                }

                int nextFormIndex = checkBossFormChange(boss, bossCurrentHp, bossTotalHp, bossForms, currentFormIndex, logGenerator);
                if (nextFormIndex != currentFormIndex) {
                    currentFormIndex = nextFormIndex;
                    currentForm = bossForms.get(currentFormIndex);
                }
            }

            if (bossCurrentHp <= 0) break;

            long targetPlayerId = selectRandomAlivePlayer(playerHpMap);
            Player targetPlayer = findPlayerById(players, targetPlayerId);

            if (targetPlayer != null) {
                double debuff = currentForm.getPlayerDebuffPercent();
                int mDamage = calculateMonsterDamageToPlayer(boss, targetPlayer, logGenerator,
                        boss.getName(), targetPlayer.getName(), debuff);

                // 功法减伤（damage_reduction 之和）
                double[] tTech = playerTech.get(targetPlayerId);
                if (tTech != null && tTech[1] > 0) {
                    mDamage = Math.max(1, (int)(mDamage * (1 - tTech[1])));
                }
                // 阵法防御加成（% 减伤，护山大阵/迷踪阵）
                if (tTech != null && tTech[2] > 0) {
                    mDamage = Math.max(1, (int)(mDamage * (1 - tTech[2] / 100.0)));
                }
                playerHpMap.put(targetPlayerId, playerHpMap.get(targetPlayerId) - mDamage);
                logGenerator.addTeamPlayerDamage(boss.getName(), targetPlayer.getName(), mDamage, playerHpMap.get(targetPlayerId));

                if (playerHpMap.get(targetPlayerId) <= 0) {
                    logGenerator.addPlayerDefeated(targetPlayer.getName());
                }
            }
        }

        // 装备磨损：团队副本按 PVP 消耗对每位参战者结算
        try {
            for (long pid : playerIds) {
                ServiceRegistry.getDurabilityService().applyWear(pid, DurabilityService.getPvpWear());
            }
        } catch (Exception ignored) {
        }

        RaidCombatResult raidResult = buildRaidResult(boss, currentFormIndex, round, playerHpMap, players,
                bossCurrentHp, lastHitPlayerId, lastHitPlayerName, logGenerator.getLogs());

        // 插件事件：团队 Boss 战结束
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.COMBAT_ENDED)
                        .data("bossName", boss.getName())
                        .data("playerIds", playerIds)
                        .data("victory", raidResult.isSuccess())
                        .build());
        return raidResult;
    }

    private List<Player> validateRaidPlayers(List<Long> playerIds) {
        List<Player> players = new ArrayList<>();
        for (long playerId : playerIds) {
            Player player = playerService.getPlayerById(playerId);
            if (player == null || player.getHp() <= 0) {
                return null;
            }
            players.add(player);
        }
        return players;
    }

    private int calculateBossTotalHp(List<BossForm> bossForms) {
        int totalHp = 0;
        for (BossForm form : bossForms) {
            totalHp += (int)(form.getHpMultiplier() * 500 * (bossForms.indexOf(form) + 1));
        }
        return totalHp;
    }

    private Monster createBossMonster(BossForm form, int formIndex, List<BossForm> bossForms) {
        int hp = (int)(form.getHpMultiplier() * 500 * (formIndex + 1));
        return new Monster(form.getName(), hp,
                (int)(500 * form.getAttackMultiplier()),
                (int)(300 * form.getDefenseMultiplier()),
                (int)(100 * form.getSpeedMultiplier()),
                3, true, new String[0], 0.5, 0, 0, 0, form.getDescription());
    }

    private Map<Long, Integer> initPlayerHpMap(List<Player> players) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (Player p : players) {
            map.put(p.getId(), p.getHp());
        }
        return map;
    }

    private Map<Long, Integer> initPlayerMpMap(List<Player> players) {
        Map<Long, Integer> map = new LinkedHashMap<>();
        for (Player p : players) {
            map.put(p.getId(), p.getMp());
        }
        return map;
    }

    private Map<Long, List<Skill>> initPlayerSkillsMap(List<Player> players) {
        Map<Long, List<Skill>> map = new LinkedHashMap<>();
        for (Player p : players) {
            map.put(p.getId(), skillService.getPlayerSkills(p.getId()));
        }
        return map;
    }

    private Map<Long, Set<Long>> initPlayerUsedSkillsMap(List<Player> players) {
        Map<Long, Set<Long>> map = new LinkedHashMap<>();
        for (Player p : players) {
            map.put(p.getId(), new HashSet<>());
        }
        return map;
    }

    private Map<Long, String> initPlayerNamesMap(List<Player> players) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Player p : players) {
            map.put(p.getId(), p.getName());
        }
        return map;
    }

    /** 战前读取各成员功法/阵法战斗加成：[0]=功法伤害倍率（≥1），[1]=功法减伤比例（0~0.9），[2]=阵法防御加成%（护山大阵/迷踪阵） */
    private Map<Long, double[]> initPlayerTechMap(List<Player> players) {
        Map<Long, double[]> map = new HashMap<>();
        for (Player p : players) {
            double dmgMult = 1 + ServiceRegistry.getTechniqueService().getEquippedCombatDamageBonus(p.getId());
            double red = ServiceRegistry.getTechniqueService().getEquippedDamageReduction(p.getId());
            double formationPct = ServiceRegistry.getFormationService().getTotalDefenseBonus(p.getId());
            map.put(p.getId(), new double[]{dmgMult, red, formationPct});
        }
        return map;
    }

    private boolean hasAlivePlayers(Map<Long, Integer> playerHpMap) {
        return playerHpMap.values().stream().anyMatch(hp -> hp > 0);
    }

    private long selectRandomAlivePlayer(Map<Long, Integer> playerHpMap) {
        List<Long> alivePlayers = new ArrayList<>();
        for (long pid : playerHpMap.keySet()) {
            if (playerHpMap.get(pid) > 0) alivePlayers.add(pid);
        }
        return alivePlayers.isEmpty() ? -1 : alivePlayers.get(random.nextInt(alivePlayers.size()));
    }

    private Player findPlayerById(List<Player> players, long playerId) {
        return players.stream().filter(p -> p.getId() == playerId).findFirst().orElse(null);
    }

    /**
     * Boss 形态推进：按剩余血量百分比决定应处于的形态（血量 <66% 进入第二形态、<33% 进入第三形态），
     * 仅在形态升级时切换并返回最新形态序号（此前形参不回写导致 raidResult.bossForm 恒为 0）。
     */
    private int checkBossFormChange(Monster boss, int bossCurrentHp, int bossTotalHp,
                                    List<BossForm> bossForms, int currentFormIndex,
                                    BattleLogGenerator logGenerator) {
        double hpPercent = (double) bossCurrentHp / bossTotalHp;
        int targetForm;
        if (hpPercent < 0.33) {
            targetForm = 2;
        } else if (hpPercent < 0.66) {
            targetForm = 1;
        } else {
            targetForm = 0;
        }
        if (targetForm > currentFormIndex) {
            BossForm newForm = bossForms.get(targetForm);
            updateBossForm(boss, newForm);
            logGenerator.addBossFormChange(newForm.getName(), targetForm == 2 ? 15 : 10);
            return targetForm;
        }
        return currentFormIndex;
    }

    private void updateBossForm(Monster boss, BossForm form) {
        boss.setName(form.getName());
        boss.setDescription(form.getDescription());
        boss.setAttack((int)(500 * form.getAttackMultiplier()));
        boss.setDefense((int)(300 * form.getDefenseMultiplier()));
    }

    private RaidCombatResult buildRaidResult(Monster boss, int formIndex, int rounds,
                                               Map<Long, Integer> playerHpMap, List<Player> players,
                                               int bossHp, long lastHitPlayerId, String lastHitPlayerName,
                                               List<String> battleLog) {
        RaidCombatResult result = new RaidCombatResult();
        result.setSuccess(true);
        result.setBossName(boss.getName());
        result.setBossForm(formIndex);
        result.setTotalRounds(rounds);

        List<Long> defeatedPlayers = new ArrayList<>();
        for (long pid : playerHpMap.keySet()) {
            if (playerHpMap.get(pid) <= 0) {
                defeatedPlayers.add(pid);
            }
        }
        result.setDefeatedPlayers(defeatedPlayers);

        boolean allDefeated = playerHpMap.values().stream().allMatch(hp -> hp <= 0);
        boolean bossDefeated = bossHp <= 0;

        if (bossDefeated && !allDefeated) {
            result.setTeamWon(true);
            result.setLastHitPlayerId(lastHitPlayerId);
            result.setLastHitPlayerName(lastHitPlayerName);
            result.setMessage("团队成功击败了【" + boss.getName() + "】，最终一击由【" + lastHitPlayerName + "】完成！");
        } else {
            result.setTeamWon(false);
            result.setMessage("团队全军覆没，挑战失败");
        }

        for (Player p : players) {
            p.setHp(Math.max(0, playerHpMap.get(p.getId())));
            playerService.updatePlayer(p.getId(), p);
        }

        result.setBattleLog(battleLog);
        return result;
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
        /** 险地 PK 从败者身上掠夺到的灵石（危险度 <3 的地点恒为 0）。 */
        private long spiritStonesLooted;
        private List<String> battleLog;
        private String message;

        public CombatResult() {}

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
        public void setWinner(String winner) { this.winner = winner; }
        public boolean isChallengerWon() { return challengerWon; }
        public void setChallengerWon(boolean challengerWon) { this.challengerWon = challengerWon; }
        public String getChallengerName() { return challengerName; }
        public void setChallengerName(String challengerName) { this.challengerName = challengerName; }
        public String getTargetName() { return targetName; }
        public void setTargetName(String targetName) { this.targetName = targetName; }
        public int getChallengerRemainingHp() { return challengerRemainingHp; }
        public void setChallengerRemainingHp(int challengerRemainingHp) { this.challengerRemainingHp = challengerRemainingHp; }
        public int getTargetRemainingHp() { return targetRemainingHp; }
        public void setTargetRemainingHp(int targetRemainingHp) { this.targetRemainingHp = targetRemainingHp; }
        public int getTotalRounds() { return totalRounds; }
        public void setTotalRounds(int totalRounds) { this.totalRounds = totalRounds; }
        public long getExpReward() { return expReward; }
        public void setExpReward(long expReward) { this.expReward = expReward; }
        public long getGoldReward() { return goldReward; }
        public void setGoldReward(long goldReward) { this.goldReward = goldReward; }
        public long getSpiritStonesLooted() { return spiritStonesLooted; }
        public void setSpiritStonesLooted(long spiritStonesLooted) { this.spiritStonesLooted = spiritStonesLooted; }
        public List<String> getBattleLog() { return battleLog; }
        public void setBattleLog(List<String> battleLog) { this.battleLog = battleLog; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}