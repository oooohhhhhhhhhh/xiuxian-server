package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class DailyService {

    private static final GameLogger LOG = GameLogger.getLogger(DailyService.class);
    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final Random random = new Random();

    private CelestialPhenomenon todayPhenomenon;
    private LocalDate phenomenonDate;

    public CelestialPhenomenon getTodayPhenomenon() {
        LocalDate today = LocalDate.now();
        if (phenomenonDate == null || !phenomenonDate.equals(today)) {
            CelestialPhenomenon[] values = CelestialPhenomenon.values();
            todayPhenomenon = values[random.nextInt(values.length)];
            phenomenonDate = today;
        }
        return todayPhenomenon;
    }

    public Map<String, Object> doMorningCultivation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", "角色不存在");
            return result;
        }

        LocalDate today = LocalDate.now();
        String todayStr = today.toString();

        int hour = LocalTime.now().getHour();
        if (hour < 5 || hour >= 11) {
            result.put("success", false);
            result.put("message", "晨修须在卯时至巳时（5:00~11:00）进行，此时紫气东来，天地灵气最为纯净。现在是" + hour + "时，非晨修之时，待日出再试。");
            return result;
        }

        PlayerDaily daily = getOrCreateDaily(playerId);
        if (daily.lastMorningCultivation != null && daily.lastMorningCultivation.equals(todayStr)) {
            result.put("success", false);
            result.put("message", "今日已晨修，紫气已收入体内，明日再来吧");
            return result;
        }

        LocalDate yesterday = today.minusDays(1);
        int consecutive;
        if (daily.lastMorningCultivation != null && daily.lastMorningCultivation.equals(yesterday.toString())) {
            consecutive = daily.consecutiveDays + 1;
        } else {
            consecutive = 1;
        }

        CelestialPhenomenon phenom = getTodayPhenomenon();
        long baseExp = (player.getRealm() + 1) * 50L;
        long bonusExp = (long)(baseExp * (consecutive - 1) * 0.10);
        long totalExp = phenom.applyCultivation(baseExp + bonusExp);
        if (player.getSpiritualRoot() != null && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.CULTIVATION_EFFICIENCY)) {
            totalExp = (long)(totalExp * (1 + player.getSpiritualRoot().getEffectValue()));
        }
        long baseSpiritStones = (player.getRealm() + 1) * 10L;
        long totalSpiritStones = phenom.applySpiritStone(baseSpiritStones);
        if (player.getSpiritualRoot() != null && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.SPIRIT_STONE_DROP)) {
            totalSpiritStones = (long)(totalSpiritStones * (1 + player.getSpiritualRoot().getEffectValue()));
        }

        String sql = """
            INSERT INTO player_daily (player_id, last_morning_cultivation, consecutive_days, total_active_days)
            VALUES (?, ?, ?, 1)
            ON DUPLICATE KEY UPDATE
            last_morning_cultivation = VALUES(last_morning_cultivation),
            consecutive_days = VALUES(consecutive_days),
            total_active_days = total_active_days + 1
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, todayStr);
            ps.setInt(3, consecutive);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("晨修记录保存失败", e);
        }

        playerService.addExperience(playerId, totalExp);
        itemService.addSpiritStones(playerId, totalSpiritStones);

        checkSpiritualRootResonance(playerId, player);

        result.put("success", true);
        result.put("message", "晨曦初现，紫气东来！你盘膝而坐，运转功法，获得 " + totalExp + " 经验和 " + totalSpiritStones + " 灵石");
        result.put("expGained", totalExp);
        result.put("spiritStonesGained", totalSpiritStones);
        result.put("consecutiveDays", consecutive);
        result.put("phenomenon", phenom.getDisplayName());
        result.put("phenomenonDesc", phenom.getDescription());
        return result;
    }

    public void onExplore(long playerId, String eventType) {
        String sql = """
            INSERT INTO player_daily (player_id, exploration_count, last_daily_reset)
            VALUES (?, 1, CURRENT_DATE)
            ON DUPLICATE KEY UPDATE
            exploration_count = IF(last_daily_reset = CURRENT_DATE, exploration_count + 1, 1),
            last_daily_reset = CURRENT_DATE
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录游历次数失败", e);
        }
        checkAndAwardDailyFortune(playerId);
    }

    public void onBattle(long playerId) {
        String sql = """
            INSERT INTO player_daily (player_id, battle_count, last_daily_reset)
            VALUES (?, 1, CURRENT_DATE)
            ON DUPLICATE KEY UPDATE
            battle_count = IF(last_daily_reset = CURRENT_DATE, battle_count + 1, 1),
            last_daily_reset = CURRENT_DATE
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录战斗次数失败", e);
        }
        checkAndAwardDailyFortune(playerId);
    }

    public void onSecretRealm(long playerId) {
        String sql = """
            INSERT INTO player_daily (player_id, secret_realm_count, last_daily_reset)
            VALUES (?, 1, CURRENT_DATE)
            ON DUPLICATE KEY UPDATE
            secret_realm_count = IF(last_daily_reset = CURRENT_DATE, secret_realm_count + 1, 1),
            last_daily_reset = CURRENT_DATE
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录秘境次数失败", e);
        }
        checkAndAwardDailyFortune(playerId);
    }

    public void onSkillLearn(long playerId) {
        String sql = """
            INSERT INTO player_daily (player_id, skill_learn_count, last_daily_reset)
            VALUES (?, 1, CURRENT_DATE)
            ON DUPLICATE KEY UPDATE
            skill_learn_count = IF(last_daily_reset = CURRENT_DATE, skill_learn_count + 1, 1),
            last_daily_reset = CURRENT_DATE
            """;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("记录学习次数失败", e);
        }
        checkAndAwardDailyFortune(playerId);
    }

    private void checkAndAwardDailyFortune(long playerId) {
        PlayerDaily daily = getOrCreateDaily(playerId);
        if (daily.explorationCount >= 3 && !daily.explorationRewarded) {
            Player p = playerService.getPlayerById(playerId);
            long expReward = (p.getRealm() + 1) * 100L;
            playerService.addExperience(playerId, getTodayPhenomenon().applyCultivation(expReward));
            markFortuneClaimed(playerId, "exploration_rewarded");
        }
        if (daily.battleCount >= 1 && !daily.battleRewarded) {
            Player p = playerService.getPlayerById(playerId);
            long goldReward = (p.getRealm() + 1) * 200L;
            playerService.addGold(playerId, goldReward);
            markFortuneClaimed(playerId, "battle_rewarded");
        }
        if (daily.secretRealmCount >= 1 && !daily.secretRealmRewarded) {
            Player p = playerService.getPlayerById(playerId);
            long ssReward = (p.getRealm() + 1) * 100L;
            itemService.addSpiritStones(playerId, getTodayPhenomenon().applySpiritStone(ssReward));
            markFortuneClaimed(playerId, "secret_realm_rewarded");
        }
        if (daily.skillLearnCount >= 1 && !daily.skillLearnRewarded) {
            Player p = playerService.getPlayerById(playerId);
            long expReward = (p.getRealm() + 1) * 80L;
            playerService.addExperience(playerId, expReward);
            markFortuneClaimed(playerId, "skill_learn_rewarded");
        }
    }

    private void markFortuneClaimed(long playerId, String column) {
        String sql = "UPDATE player_daily SET " + column + " = 1 WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("标记机缘奖励失败", e);
        }
    }

    public Map<String, Object> getDailyInfo(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        PlayerDaily daily = getOrCreateDaily(playerId);
        CelestialPhenomenon phenom = getTodayPhenomenon();

        result.put("phenomenon", phenom.getDisplayName());
        result.put("phenomenonDesc", phenom.getDescription());
        result.put("cultivationBonus", (int)((phenom.getCultivationMultiplier() - 1) * 100) + "%");
        result.put("explorationBonus", (int)((phenom.getExplorationMultiplier() - 1) * 100) + "%");
        result.put("spiritStoneBonus", (int)((phenom.getSpiritStoneMultiplier() - 1) * 100) + "%");

        LocalDate today = LocalDate.now();
        boolean doneCultivation = daily.lastMorningCultivation != null
                && daily.lastMorningCultivation.equals(today.toString());
        result.put("morningCultivationDone", doneCultivation);
        result.put("consecutiveDays", daily.consecutiveDays);
        result.put("totalActiveDays", daily.totalActiveDays);

        Map<String, Object> fortunes = new LinkedHashMap<>();
        fortunes.put("exploration", Map.of("current", daily.explorationCount, "target", 3, "rewarded", daily.explorationRewarded));
        fortunes.put("battle", Map.of("current", daily.battleCount, "target", 1, "rewarded", daily.battleRewarded));
        fortunes.put("secretRealm", Map.of("current", daily.secretRealmCount, "target", 1, "rewarded", daily.secretRealmRewarded));
        fortunes.put("skillLearn", Map.of("current", daily.skillLearnCount, "target", 1, "rewarded", daily.skillLearnRewarded));
        result.put("fortunes", fortunes);

        return result;
    }

    private void checkSpiritualRootResonance(long playerId, Player player) {
        PlayerDaily daily = getOrCreateDaily(playerId);
        SpiritualRoot root = player.getSpiritualRoot();
        if (root == null) return;

        int totalDays = daily.totalActiveDays;
        boolean awarded = false;
        String resonanceMessage = null;

        if (totalDays == 7 && !daily.resonance7Awarded) {
            awardResonance(player, root, 7);
            awardResonance(player, root, 7);
            markFortuneClaimed(playerId, "resonance7_awarded");
            awarded = true;
            resonanceMessage = "七天精进不辍！你的" + root.getDisplayName() + "与天地灵气产生共鸣，根基更加稳固";
        }
        if (totalDays == 30 && !daily.resonance30Awarded) {
            awardResonance(player, root, 30);
            markFortuneClaimed(playerId, "resonance30_awarded");
            awarded = true;
            resonanceMessage = "三十日苦修不断！你的" + root.getDisplayName() + "迸发出耀眼光芒，修为大进";
        }

        if (awarded) {
            LOG.info(player.getName() + " " + resonanceMessage);
        }
    }

    private void awardResonance(Player player, SpiritualRoot root, int milestoneDays) {
        if (milestoneDays == 7) {
            playerService.addHp(player.getId(), root.applyHpBonus(50));
            playerService.addMp(player.getId(), root.applyMpBonus(25));
            playerService.addAttack(player.getId(), root.applyAttackBonus(5));
            playerService.addDefense(player.getId(), root.applyDefenseBonus(3));
            playerService.addSpeed(player.getId(), root.applySpeedBonus(3));
            playerService.addSpirit(player.getId(), root.applySpiritBonus(5));
        } else if (milestoneDays == 30) {
            playerService.addHp(player.getId(), root.applyHpBonus(100));
            playerService.addMp(player.getId(), root.applyMpBonus(50));
            playerService.addAttack(player.getId(), root.applyAttackBonus(10));
            playerService.addDefense(player.getId(), root.applyDefenseBonus(5));
            playerService.addSpeed(player.getId(), root.applySpeedBonus(5));
            playerService.addSpirit(player.getId(), root.applySpiritBonus(10));
        }
    }

    private PlayerDaily getOrCreateDaily(long playerId) {
        String sql = "SELECT * FROM player_daily WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapDaily(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询每日数据失败", e);
        }
        String insertSql = "INSERT INTO player_daily (player_id) VALUES (?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("创建每日数据失败", e);
        }
        return new PlayerDaily();
    }

    private PlayerDaily mapDaily(ResultSet rs) throws SQLException {
        PlayerDaily d = new PlayerDaily();
        d.lastMorningCultivation = rs.getString("last_morning_cultivation");
        d.consecutiveDays = rs.getInt("consecutive_days");
        d.totalActiveDays = rs.getInt("total_active_days");
        d.explorationCount = rs.getInt("exploration_count");
        d.battleCount = rs.getInt("battle_count");
        d.secretRealmCount = rs.getInt("secret_realm_count");
        d.skillLearnCount = rs.getInt("skill_learn_count");
        d.explorationRewarded = rs.getInt("exploration_rewarded") == 1;
        d.battleRewarded = rs.getInt("battle_rewarded") == 1;
        d.secretRealmRewarded = rs.getInt("secret_realm_rewarded") == 1;
        d.skillLearnRewarded = rs.getInt("skill_learn_rewarded") == 1;
        d.resonance7Awarded = rs.getInt("resonance7_awarded") == 1;
        d.resonance30Awarded = rs.getInt("resonance30_awarded") == 1;
        return d;
    }

    public static class PlayerDaily {
        String lastMorningCultivation;
        int consecutiveDays;
        int totalActiveDays;
        int explorationCount;
        int battleCount;
        int secretRealmCount;
        int skillLearnCount;
        boolean explorationRewarded;
        boolean battleRewarded;
        boolean secretRealmRewarded;
        boolean skillLearnRewarded;
        boolean resonance7Awarded;
        boolean resonance30Awarded;
    }
}
