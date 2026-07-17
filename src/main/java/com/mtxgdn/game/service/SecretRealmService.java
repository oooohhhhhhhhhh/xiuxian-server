package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.BossForm;
import com.mtxgdn.game.entity.Monster;
import com.mtxgdn.game.entity.PveCombatResult;
import com.mtxgdn.game.entity.RaidCombatResult;
import com.mtxgdn.game.entity.SecretRealmResult;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.secretrealm.SecretRealm;
import com.mtxgdn.game.secretrealm.SecretRealmRegistry;

import java.util.*;

public class SecretRealmService {

    private final PlayerService playerService;
    private final ItemService itemService;
    private final Random random = new Random();

    public SecretRealmService() {
        this.playerService = new PlayerService();
        this.itemService = new ItemService();
    }

    public SecretRealmService(PlayerService playerService) {
        this.playerService = playerService;
        this.itemService = new ItemService();
    }

    public List<SecretRealm> getAvailableAreas(long userId) {
        Player player = playerService.getPlayerRaw(userId);
        if (player == null) {
            return List.of();
        }
        return SecretRealmRegistry.getByRealm(player.getRealm());
    }

    private SecretRealm resolveArea(String input, List<SecretRealm> availableAreas) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        try {
            int index = Integer.parseInt(input);
            if (index >= 1 && index <= availableAreas.size()) {
                return availableAreas.get(index - 1);
            }
        } catch (NumberFormatException e) {
        }
        for (SecretRealm realm : availableAreas) {
            String translatedName = realm.getName();
            if (translatedName != null && translatedName.equals(input)) {
                return realm;
            }
        }
        String lower = input.toLowerCase();
        for (SecretRealm realm : availableAreas) {
            String translatedName = realm.getName();
            if (translatedName != null && translatedName.toLowerCase().contains(lower)) {
                return realm;
            }
            if (realm.getFullKey().toLowerCase().contains(lower)) {
                return realm;
            }
        }
        return SecretRealmRegistry.resolve(input);
    }

    public SecretRealmResult enterSecretRealm(long userId, String areaName) {
        Player player = playerService.getPlayerRaw(userId);
        if (player == null) {
            return SecretRealmResult.failure("角色不存在，请先创建角色");
        }

        List<SecretRealm> availableAreas = getAvailableAreas(userId);
        SecretRealm area = resolveArea(areaName, availableAreas);

        if (area == null) {
            return SecretRealmResult.failure("未知的秘境: " + areaName);
        }

        if (player.getRealm() < area.getRequiredRealm()) {
            return SecretRealmResult.failure("你的境界不足，无法进入【" + areaName + "】，需要达到" + getRealmName(area.getRequiredRealm()) + "境界");
        }

        if (player.getHp() <= 0) {
            return SecretRealmResult.failure("你已重伤，无法进入秘境，请先恢复生命值");
        }

        long now = System.currentTimeMillis();
        boolean canEnter = com.mtxgdn.db.DatabaseManager.runTransaction(conn -> {
            String checkSql = "SELECT last_secret_realm_time FROM players WHERE id = ? FOR UPDATE";
            try (var ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, player.getId());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long lastTime = rs.getLong("last_secret_realm_time");
                        if (lastTime > 0 && (now - lastTime) < area.getCooldownMs()) {
                            return false;
                        }
                    }
                }
            }
            String updateSql = "UPDATE players SET last_secret_realm_time = ? WHERE id = ?";
            try (var ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, now);
                ps.setLong(2, player.getId());
                ps.executeUpdate();
            }
            return true;
        });

        if (!canEnter) {
            return SecretRealmResult.failure("你刚探索过【" + areaName + "】秘境，还需要等待一段时间才能再次进入");
        }

        SecretRealmResult result = generateRealmEvent(player, area);
        result.setArea(areaName);

        new DailyService().onSecretRealm(player.getId());

        return result;
    }

    private SecretRealmResult generateRealmEvent(Player player, SecretRealm area) {
        double roll = random.nextDouble();

        List<String> log = new ArrayList<>();
        log.add("你踏入了【" + area.getName() + "】秘境...");
        log.add(area.getDescription());
        log.add("---");

        SecretRealmResult result = new SecretRealmResult();
        result.setSuccess(true);
        result.setLog(log);

        if (roll < 0.20) {
            return handleTreasure(player, area, result, log);
        } else if (roll < 0.40) {
            return handleMonsterEncounter(player, area, result, log);
        } else if (roll < 0.50) {
            return handleBossEncounter(player, area, result, log);
        } else if (roll < 0.65) {
            return handleHerbFinding(player, area, result, log);
        } else if (roll < 0.78) {
            return handleCultivatorEncounter(player, area, result, log);
        } else if (roll < 0.88) {
            return handleRuinsDiscovery(player, area, result, log);
        } else if (roll < 0.95) {
            return handleTrap(player, area, result, log);
        } else {
            return handleNothing(player, area, result, log);
        }
    }

    private SecretRealmResult handleTreasure(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_treasure");
        result.setEventDescription("发现宝藏");

        int realm = player.getRealm() + 1;
        long gold = random.nextLong(realm * 50L, realm * 300L + 100);
        long spiritStones = random.nextLong(realm * 10L, realm * 80L + 20);
        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.SPIRIT_STONE_DROP)) {
            spiritStones = (long)(spiritStones * (1 + root.getEffectValue()));
        }

        playerService.addGold(player.getId(), gold);
        itemService.addSpiritStones(player.getId(), spiritStones);

        result.setGoldGained(gold);
        result.setSpiritStonesGained(spiritStones);

        log.add("✨ 你在秘境中发现了一个隐藏的宝箱！");
        log.add("获得了 " + gold + " 金币和 " + spiritStones + " 灵石！");
        result.setMessage("发现宝藏！获得了 " + gold + " 金币和 " + spiritStones + " 灵石");
        return result;
    }

    private SecretRealmResult handleMonsterEncounter(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_monster");
        result.setEventDescription("遭遇妖兽");

        Monster monster = Monster.random(player.getRealm(), random);
        CombatService combatService = new CombatService();
        PveCombatResult pveResult = combatService.pveFight(player.getId(), monster);

        result.setMonsterDefeated(pveResult.isPlayerWon());
        result.setMonsterName(monster.getName());
        result.setExpGained(pveResult.getExpGained());
        result.setGoldGained(pveResult.getGoldGained());
        result.setSpiritStonesGained(pveResult.getSpiritStonesGained());
        result.setItemGained(pveResult.getItemGained());
        result.setItemQuantity(pveResult.getItemQuantity());
        result.setMessage(pveResult.getMessage());

        if (pveResult.getBattleLog() != null) {
            log.addAll(pveResult.getBattleLog());
        }

        return result;
    }

    private SecretRealmResult handleBossEncounter(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_boss");
        result.setEventDescription("遭遇秘境守护者");

        Monster boss = Monster.createBossForRealm(area.getRequiredRealm(), random);
        CombatService combatService = new CombatService();
        PveCombatResult pveResult = combatService.pveFight(player.getId(), boss);

        result.setMonsterDefeated(pveResult.isPlayerWon());
        result.setMonsterName(boss.getName());
        result.setExpGained(pveResult.getExpGained());
        result.setGoldGained(pveResult.getGoldGained());
        result.setSpiritStonesGained(pveResult.getSpiritStonesGained());
        result.setItemGained(pveResult.getItemGained());
        result.setItemQuantity(pveResult.getItemQuantity());
        result.setMessage(pveResult.getMessage());

        if (pveResult.getBattleLog() != null) {
            log.addAll(pveResult.getBattleLog());
        }

        return result;
    }

    private SecretRealmResult handleHerbFinding(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_herb");
        result.setEventDescription("采集灵草");

        String herbItem = getHerbItem();
        int quantity = random.nextInt(1, 3);

        if (herbItem != null && ItemRegistry.contains(herbItem)) {
            itemService.addItem(player.getId(), herbItem, quantity);
            result.setItemGained(herbItem);
            result.setItemQuantity(quantity);
            log.add("🌿 你在秘境中发现了一株珍贵的灵草！");
            result.setMessage("采集到了灵草！");
        } else {
            long exp = (player.getRealm() + 1) * 30L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("🌿 你发现了一些普通的草药，虽然不算珍贵，但也有些收获。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("采集草药，获得 " + exp + " 点经验");
        }
        return result;
    }

    private SecretRealmResult handleCultivatorEncounter(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_cultivator");
        result.setEventDescription("遇到散修");

        double roll = random.nextDouble();
        if (roll < 0.5) {
            long exp = (player.getRealm() + 1) * 120L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("👤 你在秘境中遇到了一位路过的散修，他与你交流修炼心得，你获益匪浅。");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("与散修交流，获得 " + exp + " 点经验");
        } else if (roll < 0.8) {
            long spiritStones = (player.getRealm() + 1) * 30L;
            itemService.addSpiritStones(player.getId(), spiritStones);
            result.setSpiritStonesGained(spiritStones);
            log.add("👤 一位好心的散修送了你一些灵石作为见面礼。");
            log.add("获得了 " + spiritStones + " 灵石。");
            result.setMessage("散修赠礼，获得 " + spiritStones + " 灵石");
        } else {
            String giftItem = getRandomLootItem();
            if (giftItem != null && ItemRegistry.contains(giftItem) && random.nextDouble() < 0.6) {
                itemService.addItem(player.getId(), giftItem, 1);
                result.setItemGained(giftItem);
                result.setItemQuantity(1);
                log.add("👤 你遇到了一位云游四方的散修，临别时他赠予你一件物品。");
                result.setMessage("散修赠予了一件物品");
            } else {
                long gold = (player.getRealm() + 1) * 100L;
                playerService.addGold(player.getId(), gold);
                result.setGoldGained(gold);
                log.add("👤 你在秘境中遇到一位散修，他慷慨地与你分享了盘缠。");
                log.add("获得了 " + gold + " 金币。");
                result.setMessage("散修赠金，获得 " + gold + " 金币");
            }
        }
        return result;
    }

    private SecretRealmResult handleRuinsDiscovery(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_ruins");
        result.setEventDescription("发现遗迹");

        double roll = random.nextDouble();
        if (roll < 0.4) {
            String rareItem = getRareItem();
            if (rareItem != null && ItemRegistry.contains(rareItem)) {
                itemService.addItem(player.getId(), rareItem, 1);
                result.setItemGained(rareItem);
                result.setItemQuantity(1);
                log.add("🏛 你在遗迹深处发现了一件被封印的宝物！");
                result.setMessage("在遗迹中发现稀有宝物！");
            }
        } else if (roll < 0.75) {
            long spiritStones = (player.getRealm() + 1) * 150L;
            itemService.addSpiritStones(player.getId(), spiritStones);
            result.setSpiritStonesGained(spiritStones);
            log.add("🏛 你发现了一处上古遗迹，在里面找到了大量灵石！");
            log.add("获得了 " + spiritStones + " 灵石。");
            result.setMessage("遗迹寻宝，获得 " + spiritStones + " 灵石");
        } else {
            long exp = (player.getRealm() + 1) * 200L;
            playerService.addExperience(player.getId(), exp);
            result.setExpGained(exp);
            log.add("🏛 你在遗迹的石壁上发现了上古功法残篇，潜心参悟...");
            log.add("获得了 " + exp + " 点经验。");
            result.setMessage("参悟功法残篇，获得 " + exp + " 点经验");
        }
        return result;
    }

    private SecretRealmResult handleTrap(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_trap");
        result.setEventDescription("遭遇机关陷阱");

        int maxHp = player.getMaxHp();
        int hpLoss = maxHp / 10 + random.nextInt(maxHp / 8);
        int newHp = Math.max(1, player.getHp() - hpLoss);
        result.setHpLost(hpLoss);

        player.setHp(newHp);
        playerService.updatePlayer(player.getId(), player);

        log.add("⚠ 你不慎触发了秘境中的古老机关陷阱！损失了 " + hpLoss + " 点生命值。");
        result.setMessage("触发陷阱，损失了 " + hpLoss + " 点生命值");
        return result;
    }

    private SecretRealmResult handleNothing(Player player, SecretRealm area, SecretRealmResult result, List<String> log) {
        result.setEventType("encounter_nothing");
        result.setEventDescription("一无所获");

        long exp = (player.getRealm() + 1) * 10L;
        playerService.addExperience(player.getId(), exp);
        result.setExpGained(exp);

        log.add("💨 你在秘境中四处探索了一番，没有发现什么特别的东西...");
        log.add("不过漫步中也有感悟，获得了 " + exp + " 点经验。");
        result.setMessage("这次秘境探索没什么收获，获得了 " + exp + " 点经验");
        return result;
    }

    @SuppressWarnings("unused")
    private String generateMonsterName() {
        String[] prefixes = {"烈焰", "冰霜", "暗影", "血牙", "铁甲", "疾风", "毒雾", "岩石", "幽冥", "金翅"};
        String[] names = {"妖狼", "巨蟒", "魔蛛", "赤虎", "黑熊", "妖鹰", "石魔", "蛇妖", "蝎王", "魅狐"};
        return prefixes[random.nextInt(prefixes.length)] + names[random.nextInt(names.length)];
    }

    private String getRandomLootItem() {
        String[] commonItems = {"mtxgdn:spirit_grass", "mtxgdn:iron_ore", "mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:spirit_recovery_pill"};
        return commonItems[random.nextInt(commonItems.length)];
    }

    private String getHerbItem() {
        String[] herbs = {"mtxgdn:spirit_grass", "mtxgdn:healing_pill", "mtxgdn:mana_pill", "mtxgdn:spirit_spring_water", "mtxgdn:beast_core"};
        return herbs[random.nextInt(herbs.length)];
    }

    private String getRareItem() {
        String[] rareItems = {"mtxgdn:cultivation_elixir", "mtxgdn:scripture_page", "mtxgdn:spirit_sword", "mtxgdn:guardian_jade",
                "mtxgdn:basic_sword_manual", "mtxgdn:fire_dragon_art", "mtxgdn:jade_armor", "mtxgdn:power_buff_pill",
                "mtxgdn:speed_talisman", "mtxgdn:thunder_bolt_talisman", "mtxgdn:dragon_blood_crystal", "mtxgdn:spirit_stone_pouch",
                "mtxgdn:heavenly_jade", "mtxgdn:enhance_stone", "mtxgdn:protect_charm", "mtxgdn:tribulation_pill"};
        return rareItems[random.nextInt(rareItems.length)];
    }

    private String getRealmName(int realmId) {
        switch (realmId) {
            case 0: return "凡人";
            case 1: return "炼气期";
            case 2: return "筑基期";
            case 3: return "金丹期";
            case 4: return "元婴期";
            case 5: return "化神期";
            default: return "未知境界";
        }
    }

    public SecretRealmResult enterRaid(long leaderId, String areaName) {
        Player leader = playerService.getPlayerRaw(leaderId);
        if (leader == null) {
            return SecretRealmResult.failure("角色不存在，请先创建角色");
        }

        List<SecretRealm> availableAreas = getAvailableAreas(leaderId);
        SecretRealm area = resolveArea(areaName, availableAreas);
        if (area == null) {
            return SecretRealmResult.failure("未知的秘境: " + areaName);
        }

        if (!area.isRaid()) {
            return SecretRealmResult.failure("该秘境不支持团队副本模式");
        }

        TeamService teamService = TeamService.getInstance();
        TeamService.Team team = teamService.getTeam(leaderId);
        if (team == null) {
            return SecretRealmResult.failure("你需要先创建或加入一个团队");
        }

        if (!team.isLeader(leaderId)) {
            return SecretRealmResult.failure("只有队长可以发起副本挑战");
        }

        List<Long> memberIds = new ArrayList<>(team.getMemberIds());
        if (memberIds.size() < 2) {
            return SecretRealmResult.failure("团队人数不足，至少需要2人");
        }

        List<Player> members = new ArrayList<>();
        for (long memberId : memberIds) {
            Player member = playerService.getPlayerRaw(memberId);
            if (member == null) {
                return SecretRealmResult.failure("团队成员不存在");
            }
            if (member.getRealm() < area.getRequiredRealm()) {
                return SecretRealmResult.failure("团队成员【" + member.getName() + "】境界不足，无法进入");
            }
            if (member.getHp() <= 0) {
                return SecretRealmResult.failure("团队成员【" + member.getName() + "】已重伤，无法进入");
            }
            members.add(member);
        }

        long now = System.currentTimeMillis();
        for (Player member : members) {
            long lastTime = member.getLastSecretRealmTime();
            if (lastTime > 0 && (now - lastTime) < area.getCooldownMs()) {
                long remaining = (area.getCooldownMs() - (now - lastTime)) / 1000;
                return SecretRealmResult.failure("【" + member.getName() + "】刚探索过秘境，还需要等待 " + remaining + " 秒");
            }
        }

        for (Player member : members) {
            playerService.updateLastSecretRealmTime(member.getId(), now);
        }

        List<String> log = new ArrayList<>();
        log.add("🏰 团队进入了【" + area.getName() + "】秘境副本！");
        log.add("成员：" + members.stream().map(Player::getName).toList());
        log.add("---");

        SecretRealmResult result = new SecretRealmResult();
        result.setSuccess(true);
        result.setLog(log);
        result.setArea(areaName);

        int waveCount = Math.max(2, area.getRequiredRealm());
        result.setMessage("团队副本开始！共 " + waveCount + " 波小怪关卡");

        for (int wave = 1; wave <= waveCount; wave++) {
            log.add("=== 第" + wave + "波小怪 ===");

            int monsterCount = random.nextInt(1, 3);
            boolean waveCleared = true;

            for (int i = 0; i < monsterCount; i++) {
                Monster monster = Monster.random(area.getRequiredRealm(), random);
                log.add("⚔ 出现了【" + monster.getName() + "】！");

                boolean allDefeated = false;
                for (Player member : members) {
                    if (member.getHp() <= 0) continue;

                    CombatService combatService = new CombatService();
                    PveCombatResult pveResult = combatService.pveFight(member.getId(), monster);

                    if (pveResult.isPlayerWon()) {
                        log.add("【" + member.getName() + "】击败了【" + monster.getName() + "】！");
                        result.setExpGained(result.getExpGained() + pveResult.getExpGained());
                        result.setGoldGained(result.getGoldGained() + pveResult.getGoldGained());
                        result.setSpiritStonesGained(result.getSpiritStonesGained() + pveResult.getSpiritStonesGained());
                        break;
                    } else {
                        log.add("【" + member.getName() + "】被【" + monster.getName() + "】击败！");
                    }

                    allDefeated = members.stream().allMatch(p -> p.getHp() <= 0);
                    if (allDefeated) break;
                }

                if (allDefeated) {
                    waveCleared = false;
                    break;
                }
            }

            if (!waveCleared) {
                break;
            }

            log.add("第" + wave + "波小怪已全部清除！");
            if (wave < waveCount) {
                log.add("继续前进...");
            }
        }

        if (members.stream().anyMatch(p -> p.getHp() > 0)) {
            log.add("=== 最终Boss ===");
            log.add("所有小怪已清除，最终Boss出现！");

            List<BossForm> bossForms = BossForm.createFormsForRealm(area.getRequiredRealm());
            CombatService combatService = new CombatService();
            RaidCombatResult raidResult = combatService.raidBossFight(memberIds, bossForms);

            if (raidResult.getBattleLog() != null) {
                log.addAll(raidResult.getBattleLog());
            }

            if (raidResult.isTeamWon()) {
                result.setMonsterDefeated(true);
                result.setMonsterName(raidResult.getBossName());

                long baseExp = (area.getRequiredRealm() + 1) * 500L;
                long baseGold = (area.getRequiredRealm() + 1) * 300L;
                long baseSpiritStones = (area.getRequiredRealm() + 1) * 100L;

                int memberCount = members.size();
                double teamBonus = 1 + 0.2 * (memberCount - 1);
                long totalExp = (long)(baseExp * teamBonus);
                long totalGold = (long)(baseGold * teamBonus);
                long totalSpiritStones = (long)(baseSpiritStones * teamBonus);

                long expPerMember = totalExp / memberCount;
                long goldPerMember = totalGold / memberCount;
                long ssPerMember = totalSpiritStones / memberCount;

                for (Player member : members) {
                    if (member.getHp() > 0) {
                        playerService.addExperience(member.getId(), expPerMember);
                        playerService.addGold(member.getId(), goldPerMember);
                        itemService.addSpiritStones(member.getId(), ssPerMember);
                    }
                }

                result.setExpGained(expPerMember);
                result.setGoldGained(goldPerMember);
                result.setSpiritStonesGained(ssPerMember);

                BossForm finalForm = bossForms.get(raidResult.getBossForm());
                if (finalForm.getRewardTable() != null && finalForm.getRewardTable().length > 0) {
                    for (Player member : members) {
                        if (member.getHp() > 0 && random.nextDouble() < finalForm.getRewardChance()) {
                            String rewardItem = finalForm.getRewardTable()[random.nextInt(finalForm.getRewardTable().length)];
                            if (ItemRegistry.contains(rewardItem)) {
                                itemService.addItem(member.getId(), rewardItem, 1);
                                if (member.getId() == raidResult.getLastHitPlayerId()) {
                                    result.setItemGained(rewardItem);
                                    result.setItemQuantity(result.getItemQuantity() + 1);
                                }
                            }
                        }
                    }
                }

                if (raidResult.getLastHitPlayerId() > 0) {
            new TitleService().grantTitle(raidResult.getLastHitPlayerId(), "raid_conqueror");
            log.add("🏆 【" + raidResult.getLastHitPlayerName() + "】获得了「副本征服者」称号！");
        }

                result.setMessage("团队成功击败了【" + raidResult.getBossName() + "】！");
            } else {
                result.setMonsterDefeated(false);
                result.setMessage("团队挑战失败");
            }
        } else {
            result.setSuccess(false);
            result.setMessage("团队全军覆没");
        }

        new DailyService().onSecretRealm(leaderId);
        return result;
    }
}
