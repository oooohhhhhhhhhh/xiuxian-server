package com.mtxgdn.game.service;

import com.mtxgdn.game.daily.DailyFortune;
import com.mtxgdn.game.daily.DailyFortunes;
import com.mtxgdn.game.daily.DailyInfo;
import com.mtxgdn.game.daily.MorningCultivationResult;
import com.mtxgdn.db.DailyDAO;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.i18n.I18n;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.util.GameLogger;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Random;
import com.mtxgdn.i18n.I18n;

public class DailyService {

    private static final GameLogger LOG = GameLogger.getLogger(DailyService.class);
    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();
    private final Random random = new Random();
    /** {@code player_daily} 的读写入口（无状态，可共享）。 */
    private static final DailyDAO DAILY_DAO = new DailyDAO();

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

    public MorningCultivationResult doMorningCultivation(long playerId) {
        Player player = playerService.getPlayerById(playerId);
        if (player == null) {
            return MorningCultivationResult.rejected(I18n.t("daily.character_not_found"));
        }

        LocalDate today = LocalDate.now();
        String todayStr = today.toString();

        int hour = LocalTime.now().getHour();
        if (hour < 5 || hour >= 11) {
            return MorningCultivationResult.rejected(
                    I18n.t("daily.morning_cultivation_must_be_performed", hour));
        }

        DailyDAO.PlayerDaily daily = getOrCreateDaily(playerId);
        if (daily.lastMorningCultivation != null && daily.lastMorningCultivation.equals(todayStr)) {
            return MorningCultivationResult.rejected(
                    I18n.t("daily.you_have_already_done_morning"));
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

        // 签到 upsert 的双方言分支（ON CONFLICT / ON DUPLICATE KEY）与
        // total_active_days 的累加式都在 DailyDAO 里，逐字保留；
        // 事务内只用 Connection 版，不再另开连接。
        final long finalTotalExp = totalExp;
        final long finalTotalSpiritStones = totalSpiritStones;
        DatabaseManager.runTransaction(conn -> {
            DAILY_DAO.recordMorningCultivation(conn, playerId, todayStr, consecutive);
            playerService.addExperience(conn, playerId, finalTotalExp);
            itemService.addSpiritStones(conn, playerId, finalTotalSpiritStones);
            return null;
        });

        checkSpiritualRootResonance(playerId, player);

        // 季节加成整段作为一个占位符 {2}：它在中文里是括号括起来的尾巴，
        // 英文里大概率要换一种说法（可能前置成分、可能换行），
        // 把"整个尾巴"交出去比拆成"（{2}加成 +{3}）"更容易让翻译重排语序。
        return MorningCultivationResult.done(
                I18n.t("daily.cultivate.success", totalExp, totalSpiritStones,
                        seasonBonusExp > 0
                                ? "（" + currentSeason.getDisplayName() + "加成 +" + seasonBonusExp + "）"
                                : ""),
                totalExp, totalSpiritStones, consecutive,
                phenom.getDisplayName(), phenom.getDescription(),
                currentSeason.getDisplayName(), seasonMultiplier);
    }

    /**
     * 行级“跨日复位”：当 last_daily_reset 不是今天（或为空）时，将四个今日机缘计数与奖励标记、PVP 次数全部归零并刷新日期，
     * 保证每个机缘目标与 PVP 次数在每天首次触发/查询时都从 0 开始。
     * （此前只有“触发当列”会跨日归 1，其余列因共享 last_daily_reset 而跨日失效，机缘奖励标记也从不复位。）
     * 灵根共鸣里程碑（resonance7/30）为终身一次性发放，不在此复位范围内。
     */
    public void ensureDailyReset(long playerId) {
        String today = LocalDate.now().toString();
        DAILY_DAO.resetDaily(playerId, today);
    }

    /** 静态入口：供 CombatService 等跨系统在读取/累加 PVP 次数前调用，保证 player_daily 全行按日复位。 */
    public static void ensureDailyResetStatic(long playerId) {
        ServiceRegistry.getDailyService().ensureDailyReset(playerId);
    }

    public void onExplore(long playerId, String eventType) {
        ensureDailyReset(playerId);
        DAILY_DAO.incrementDailyCount(playerId, "exploration_count");
        checkAndAwardDailyFortune(playerId);
    }

    public void onBattle(long playerId) {
        ensureDailyReset(playerId);
        DAILY_DAO.incrementDailyCount(playerId, "battle_count");
        checkAndAwardDailyFortune(playerId);
    }

    public void onSecretRealm(long playerId) {
        ensureDailyReset(playerId);
        DAILY_DAO.incrementDailyCount(playerId, "secret_realm_count");
        checkAndAwardDailyFortune(playerId);
    }

    public void onSkillLearn(long playerId) {
        ensureDailyReset(playerId);
        DAILY_DAO.incrementDailyCount(playerId, "skill_learn_count");
        checkAndAwardDailyFortune(playerId);
    }

    private void checkAndAwardDailyFortune(long playerId) {
        DailyDAO.PlayerDaily daily = getOrCreateDaily(playerId);
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

    /** 把某个机缘奖励 / 共鸣里程碑标记为"已发放"（列名必须是服务层的受信字面量）。 */
    private void markFortuneClaimed(long playerId, String column) {
        DAILY_DAO.markFortuneClaimed(playerId, column);
    }

    public DailyInfo getDailyInfo(long playerId) {
        // 先按日复位（跨天时计数与机缘奖励标记归零），再读取展示
        ensureDailyReset(playerId);
        DailyDAO.PlayerDaily daily = getOrCreateDaily(playerId);
        CelestialPhenomenon phenom = getTodayPhenomenon();

        LocalDate today = LocalDate.now();
        boolean doneCultivation = daily.lastMorningCultivation != null
                && daily.lastMorningCultivation.equals(today.toString());

        DailyFortunes fortunes = new DailyFortunes(
                new DailyFortune(daily.explorationCount, 3, daily.explorationRewarded),
                new DailyFortune(daily.battleCount, 1, daily.battleRewarded),
                new DailyFortune(daily.secretRealmCount, 1, daily.secretRealmRewarded),
                new DailyFortune(daily.skillLearnCount, 1, daily.skillLearnRewarded));

        String dailyTask;
        String taskReward;
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

        return new DailyInfo(
                phenom.getDisplayName(),
                phenom.getDescription(),
                (int) ((phenom.getCultivationMultiplier() - 1) * 100) + "%",
                (int) ((phenom.getExplorationMultiplier() - 1) * 100) + "%",
                (int) ((phenom.getSpiritStoneMultiplier() - 1) * 100) + "%",
                doneCultivation,
                daily.consecutiveDays,
                daily.totalActiveDays,
                fortunes,
                dailyTask,
                // 无奖励时原本 put(null)：Gson 默认跳过 null 分量，序列化结果一致
                taskReward.isEmpty() ? null : taskReward);
    }

    private void checkSpiritualRootResonance(long playerId, Player player) {
        DailyDAO.PlayerDaily daily = getOrCreateDaily(playerId);
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

    /**
     * 读出每日行；没有行就先建一行再返回全零对象。
     *
     * <p>"读"与"建行"的顺序与原实现一致：先查，查不到才插。
     * 两条 SQL 都在 {@link DailyDAO} 里（{@code getDaily} / {@code insertDaily}）。</p>
     */
    private DailyDAO.PlayerDaily getOrCreateDaily(long playerId) {
        DailyDAO.PlayerDaily daily = DAILY_DAO.getDaily(playerId);
        if (daily != null) {
            return daily;
        }
        DAILY_DAO.insertDaily(playerId);
        return new DailyDAO.PlayerDaily();
    }

    // PlayerDaily 已搬到 DailyDAO（它就是 player_daily 表的行映射）。

}
