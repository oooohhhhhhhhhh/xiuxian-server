package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.common.service.ServiceRegistry;
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
import com.mtxgdn.i18n.I18n;

public class DailyService {

    private static final GameLogger LOG = GameLogger.getLogger(DailyService.class);
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();
    private final Random random = new Random();

    /**
     * 今日天象的缓存。必须是 <b>static</b>：天象是全局天气，不是某个服务实例的私有状态。
     *
     * <p><b>原来的三个问题</b>（实例字段 + 首次访问时随机）：</p>
     * <ol>
     *   <li><b>不全局</b>：直接 new DailyService 实例的地方散布在
     *       {@code OfflineRewardService} / {@code CombatService} / {@code ExplorationService} /
     *       {@code SkillService} 等处，每个新实例都是 {@code phenomenonDate == null}，
     *       于是各自给"今天"抽一个不同的天象 —— 同一时刻，
     *       界面显示的天象和掉落结算用的天象可以不是同一个。</li>
     *   <li><b>不持久</b>：重启后重新抽，玩家眼里的"今日天象"说变就变。</li>
     *   <li><b>不线程安全</b>：注释写着"线程安全"，但两个线程同时进
     *       {@code if (phenomenonDate == null)} 会各抽一次，后写的覆盖先写的。</li>
     * </ol>
     *
     * <p>修复：改成 static 缓存 + 按日期播种的确定性抽取 ——
     * 同一天在任意实例、任意线程、任意次重启下都得到同一个天象，
     * 既解决了全局一致性，也顺带解决了持久化，还不用新增一张表。</p>
     */
    private static volatile CelestialPhenomenon todayPhenomenon;
    private static volatile LocalDate phenomenonDate;

    public CelestialPhenomenon getTodayPhenomenon() {
        LocalDate today = LocalDate.now();
        // 双检：热路径（每次掉落/修炼结算都会问一次）不加锁
        CelestialPhenomenon cached = todayPhenomenon;
        if (cached != null && today.equals(phenomenonDate)) {
            return cached;
        }
        synchronized (DailyService.class) {
            if (todayPhenomenon == null || !today.equals(phenomenonDate)) {
                todayPhenomenon = pickPhenomenonFor(today);
                phenomenonDate = today;
            }
            return todayPhenomenon;
        }
    }

    /**
     * 按日期确定性抽取天象：同一天永远是同一个。
     *
     * <p>用 {@code toEpochDay()} 播种而不是每次 {@code new Random()}，
     * 是为了让"今日天象"这件事在重启、多实例、并发下都不再是一个随机量。</p>
     */
    static CelestialPhenomenon pickPhenomenonFor(LocalDate date) {
        CelestialPhenomenon[] values = CelestialPhenomenon.values();
        return values[new Random(date.toEpochDay()).nextInt(values.length)];
    }

    /**
     * 静态入口：获取今日天象（供修炼/掉落等全局结算使用）。
     *
     * <p>现在"全局"和"线程安全"都是真的：缓存本身是 static 的，
     * 任意实例、任意线程拿到的都是同一个值。</p>
     */
    public static CelestialPhenomenon getTodayPhenomenonStatic() {
        return ServiceRegistry.getDailyService().getTodayPhenomenon();
    }

    public Map<String, Object> doMorningCultivation(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            result.put("success", false);
            result.put("message", I18n.t("daily.h59a76b2c"));
            return result;
        }

        LocalDate today = LocalDate.now();
        String todayStr = today.toString();

        int hour = LocalTime.now().getHour();
        if (hour < 5 || hour >= 11) {
            result.put("success", false);
            result.put("message", I18n.t("daily.h0e0dc5c8", hour));
            return result;
        }

        PlayerDaily daily = getOrCreateDaily(playerId);
        if (daily.lastMorningCultivation != null && daily.lastMorningCultivation.equals(todayStr)) {
            result.put("success", false);
            result.put("message", I18n.t("daily.h131eebf4"));
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
        // 全局季节加成（季节系统已同步至全局）
        Season currentSeason = SeasonService.getCurrentSeason();
        double seasonMultiplier = SeasonService.getMorningCultivationMultiplier();
        long seasonBonusExp = (long)(totalExp * (seasonMultiplier - 1));
        totalExp += seasonBonusExp;
        long baseSpiritStones = (player.getRealm() + 1) * 10L;
        long totalSpiritStones = phenom.applySpiritStone(baseSpiritStones);
        if (player.getSpiritualRoot() != null && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.SPIRIT_STONE_DROP)) {
            totalSpiritStones = (long)(totalSpiritStones * (1 + player.getSpiritualRoot().getEffectValue()));
        }
        totalSpiritStones = (long)(totalSpiritStones * SeasonService.getSpiritStoneMultiplier());

        String sql;
        if (DatabaseManager.isSqlite()) {
            sql = """
                INSERT INTO player_daily (player_id, last_morning_cultivation, consecutive_days, total_active_days)
                VALUES (?, ?, ?, 1)
                ON CONFLICT(player_id) DO UPDATE SET
                last_morning_cultivation = excluded.last_morning_cultivation,
                consecutive_days = excluded.consecutive_days,
                total_active_days = total_active_days + 1
                """;
        } else {
            sql = """
                INSERT INTO player_daily (player_id, last_morning_cultivation, consecutive_days, total_active_days)
                VALUES (?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE
                last_morning_cultivation = VALUES(last_morning_cultivation),
                consecutive_days = VALUES(consecutive_days),
                total_active_days = total_active_days + 1
                """;
        }

        final long finalTotalExp = totalExp;
        final long finalTotalSpiritStones = totalSpiritStones;
        DatabaseManager.runTransaction(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                ps.setString(2, todayStr);
                ps.setInt(3, consecutive);
                ps.executeUpdate();
            }
            playerService.addExperience(conn, playerId, finalTotalExp);
            itemService.addSpiritStones(conn, playerId, finalTotalSpiritStones);
            return null;
        });

        checkSpiritualRootResonance(playerId, player);

        result.put("success", true);
        // 季节加成整段作为一个占位符 {2}：它在中文里是括号括起来的尾巴，
        // 英文里大概率要换一种说法（可能前置成分、可能换行），
        // 把"整个尾巴"交出去比拆成"（{2}加成 +{3}）"更容易让翻译重排语序。
        result.put("message", I18n.t("daily.cultivate.success", totalExp, totalSpiritStones,
                seasonBonusExp > 0
                        ? "（" + currentSeason.getDisplayName() + "加成 +" + seasonBonusExp + "）"
                        : ""));
        result.put("expGained", totalExp);
        result.put("spiritStonesGained", totalSpiritStones);
        result.put("consecutiveDays", consecutive);
        result.put("phenomenon", phenom.getDisplayName());
        result.put("phenomenonDesc", phenom.getDescription());
        result.put("season", currentSeason.getDisplayName());
        result.put("seasonMultiplier", seasonMultiplier);
        return result;
    }

    /** 计数型每日字段的跨方言累加：行级“跨日复位”由 {@link #ensureDailyReset} 负责，此处只做同日累加。 */
    private static String dailyCountIncrementSql(String countColumn) {
        if (DatabaseManager.isSqlite()) {
            return "INSERT INTO player_daily (player_id, " + countColumn + ", last_daily_reset) VALUES (?, 1, CURRENT_DATE) " +
                    "ON CONFLICT(player_id) DO UPDATE SET " + countColumn + " = " + countColumn + " + 1, " +
                    "last_daily_reset = CURRENT_DATE";
        }
        return "INSERT INTO player_daily (player_id, " + countColumn + ", last_daily_reset) VALUES (?, 1, CURRENT_DATE) " +
                "ON DUPLICATE KEY UPDATE " + countColumn + " = " + countColumn + " + 1, " +
                "last_daily_reset = CURRENT_DATE";
    }

    /**
     * 行级“跨日复位”：当 last_daily_reset 不是今天（或为空）时，将四个今日机缘计数与奖励标记、PVP 次数全部归零并刷新日期，
     * 保证每个机缘目标与 PVP 次数在每天首次触发/查询时都从 0 开始。
     * （此前只有“触发当列”会跨日归 1，其余列因共享 last_daily_reset 而跨日失效，机缘奖励标记也从不复位。）
     * 灵根共鸣里程碑（resonance7/30）为终身一次性发放，不在此复位范围内。
     */
    public void ensureDailyReset(long playerId) {
        String today = LocalDate.now().toString();
        String sql = "UPDATE player_daily SET " +
                "exploration_count = 0, battle_count = 0, secret_realm_count = 0, skill_learn_count = 0, pvp_count = 0, " +
                "exploration_rewarded = 0, battle_rewarded = 0, secret_realm_rewarded = 0, skill_learn_rewarded = 0, " +
                "last_daily_reset = ? WHERE player_id = ? AND (last_daily_reset IS NULL OR last_daily_reset <> ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, today);
            ps.setLong(2, playerId);
            ps.setString(3, today);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("每日状态复位失败", e);
        }
    }

    /** 静态入口：供 CombatService 等跨系统在读取/累加 PVP 次数前调用，保证 player_daily 全行按日复位。 */
    public static void ensureDailyResetStatic(long playerId) {
        ServiceRegistry.getDailyService().ensureDailyReset(playerId);
    }

    public void onExplore(long playerId, String eventType) {
        ensureDailyReset(playerId);
        String sql = dailyCountIncrementSql("exploration_count");
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
        ensureDailyReset(playerId);
        String sql = dailyCountIncrementSql("battle_count");
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
        ensureDailyReset(playerId);
        String sql = dailyCountIncrementSql("secret_realm_count");
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
        ensureDailyReset(playerId);
        String sql = dailyCountIncrementSql("skill_learn_count");
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
        // 先按日复位（跨天时计数与机缘奖励标记归零），再读取展示
        ensureDailyReset(playerId);
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

        String dailyTask = "";
        String taskReward = "";
        if (!daily.explorationRewarded && daily.explorationCount < 3) {
            dailyTask = "探索秘境 3 次";
            taskReward = "经验值";
        } else if (!daily.battleRewarded && daily.battleCount < 1) {
            dailyTask = "参与战斗 1 次";
            taskReward = "金币";
        } else if (!daily.secretRealmRewarded && daily.secretRealmCount < 1) {
            dailyTask = "进入秘境 1 次";
            taskReward = "灵石";
        } else if (!daily.skillLearnRewarded && daily.skillLearnCount < 1) {
            dailyTask = "学习技能 1 次";
            taskReward = "经验值";
        } else {
            dailyTask = "今日机缘已全部完成";
            taskReward = "";
        }
        result.put("dailyTask", dailyTask);
        result.put("taskReward", taskReward.isEmpty() ? null : taskReward);

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
