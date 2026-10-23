package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.RealmConfig;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.util.AppConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RealmService {

    private final PlayerService playerService;
    private Random random = new Random();

    /**
     * 仅测试用：注入确定性的随机源，让「渡劫成功 / 失败」这两条分支可以被精确覆盖。
     * <p>生产代码不要调用 —— 渡劫结果必须来自真随机。</p>
     */
    void setRandomForTest(Random random) {
        this.random = random;
    }

    private static final int[] BASE_SUCCESS_RATES = { 80, 80, 70, 60, 50, 40, 30, 20, 15, 10 };

    private static final long[] BREAKTHROUGH_COOLDOWN_SECONDS = {
            600,   // 凡人: 10分钟
            1800,  // 炼气: 30分钟
            3600,  // 筑基: 1小时
            7200,  // 金丹: 2小时
            14400, // 元婴: 4小时
            28800, // 化神: 8小时
            43200, // 合体: 12小时
            86400, // 大乘: 1天
            172800,// 渡劫: 2天
            259200 // 真仙: 3天
    };

    private static final String[][] TRIBULATION_TYPES = {
        {"雷劫", "九天神雷", "九天之上雷云翻滚，一道水桶粗的紫色天雷当头劈下！"},
        {"心魔劫", "心魔噬魂", "一股黑气自丹田涌出，眼前浮现过往种种执念，心神剧震..."},
        {"风火劫", "风火炼体", "阴风自脚下升起，三昧真火从天灵灌入，风火交加，肉身几欲崩溃！"},
        {"水劫", "玄冥弱水", "天空裂开一道缝隙，玄冥弱水倾泻而下，每一滴重逾千斤，欲将你碾为齑粉！"},
        {"五行劫", "五行颠倒", "金木水火土五行之力同时暴走，天地为之色变，大道规则混乱不堪！"},
        {"阴阳劫", "阴阳逆转", "黑白两道雷霆交织成太极图，缓缓压下，阴阳逆转之力撕扯灵魂！"},
        {"轮回劫", "六道轮回", "眼前浮现六道轮回之门，因果业力化作锁链缠身，欲将你拖入轮回深渊！"},
    };

    private static final int[][] TRIBULATION_REALMS = {
        {0, 1, 2},  // 雷劫: 凡人/炼气/筑基（突破至炼气/筑基/金丹）
        {3},        // 心魔劫: 金丹（突破至元婴）
        {4},        // 风火劫: 元婴（突破至化神）
        {5},        // 水劫: 化神（突破至合体）
        {6},        // 五行劫: 合体（突破至大乘）
        {7},        // 阴阳劫: 大乘（突破至渡劫）
        {8, 9},     // 轮回劫: 渡劫/真仙（突破至真仙/金仙）
    };

    /** 渡劫对应属性加成的上限（百分点）。 */
    private static final double MAX_STAT_BONUS = 10.0;

    /** 渡劫成功率的硬上限：再怎么堆增益也留 5% 的天意。 */
    private static final double MAX_SUCCESS_RATE = 95.0;

    /** 渡劫成功率的硬下限：凝实与各种减益叠加后也不至于归零。 */
    private static final double MIN_SUCCESS_RATE = 1.0;

    /**
     * 渡劫丹提供的渡劫成功率加成（百分点）。
     *
     * <p><b>为什么要有这个常量</b>：这个数字原来散在三处且互不一致 ——
     * 实现里硬编码 {@code 10.0}、{@code TribulationPillEffect} 的提示文案写 +10%、
     * 商店描述写 +10%，而语言包里的物品描述写的是 <b>+20%</b>，
     * 另外还有一个从来没人调用的 {@code RealmBreakEffect} 也写 +20%。
     * 玩家看到的是 +20%、实际拿到的是 +10%，属于直接对玩家撒谎。</p>
     *
     * <p>现在实现、提示文案、物品描述都以这里为唯一口径，
     * 回归测试 {@code TribulationPillConsistencyTest} 会校验语言包与常量一致。</p>
     */
    public static final double TRIBULATION_PILL_BONUS = 10.0;

    public RealmService(PlayerService playerService) {
        this.playerService = playerService;
    }

    /** 灵石与丹药的读写都走 ItemService：灵石有四档且各有硬上限，自己拼 SQL 一定会算错。 */
    private ItemService itemService() {
        return ServiceRegistry.getItemService();
    }

    // ==================== 配置 ====================

    /** 每次失败累加的成功率百分点（pity）。 */
    public static int pityBonusPerFailure() {
        return Math.max(0, AppConfig.getInt("breakthrough.pity_bonus", 20));
    }

    /** 失败增益的堆叠上限（次数）。 */
    public static int maxPityStack() {
        return Math.max(1, AppConfig.getInt("breakthrough.max_pity_stack", 10));
    }

    /** 每层凝实降低的成功率百分点（按乘算打折）。 */
    public static int consolidateRatePenalty() {
        return Math.max(0, AppConfig.getInt("breakthrough.consolidate_rate_penalty", 10));
    }

    /** 凝实层数上限。 */
    public static int consolidateMaxLayers() {
        return Math.max(1, AppConfig.getInt("breakthrough.consolidate_max_layers", 3));
    }

    /** 凝实后渡劫失败的修为损失放大系数（每层）。 */
    public static double consolidatePunishBonus() {
        String raw = AppConfig.get("breakthrough.consolidate_punish_bonus");
        if (raw == null || raw.isBlank()) {
            return 0.3;
        }
        try {
            return Math.max(0.0, Math.min(5.0, Double.parseDouble(raw.trim())));
        } catch (NumberFormatException e) {
            return 0.3;
        }
    }

    // ==================== 突破 ====================

    public RealmBreakthroughResult tryBreakthrough(long userId) {
        // 使用事务锁防止并发突破
        return DatabaseManager.runTransaction(conn -> {
            // 使用 SELECT FOR UPDATE 加悲观锁
            String lockSql = "SELECT * FROM players WHERE user_id = ? "
                    + DatabaseManager.getDialect().getForUpdateClause();
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return failure("玩家不存在");
                    }

                    // 从锁定的结果集中构建玩家对象
                    Player player = mapPlayerFromResultSet(rs);

                    long remainingCooldown = checkBreakthroughCooldown(player);
                    if (remainingCooldown > 0) {
                        String cooldownMsg = formatCooldown(remainingCooldown);
                        return failure("突破冷却中，还需等待 " + cooldownMsg + " 才能再次突破");
                    }

                    RealmConfig currentConfig = GameConfigLoader.getRealmConfig(player.getRealm(), player.getSubRealm());
                    if (currentConfig == null) {
                        return failure("当前境界配置不存在");
                    }

                    if (GameConfigLoader.isMaxRealm(player.getRealm(), player.getSubRealm())) {
                        return failure("已达最高境界，无法继续突破");
                    }

                    RealmConfig nextConfig = GameConfigLoader.getNextRealmConfig(player.getRealm(), player.getSubRealm());
                    if (nextConfig == null) {
                        return failure("已是最高境界");
                    }

                    if (player.getExperience() < nextConfig.getRequiredExp()) {
                        long need = nextConfig.getRequiredExp() - player.getExperience();
                        return failure("经验不足，还需要 " + need + " 经验值");
                    }

                    if (nextConfig.getRequiredSpiritStones() > 0) {
                        // 灵石有四档（下/中/上/极品）且各有硬上限，必须按"下品等值"汇总后再比。
                        // 旧实现只查 mtxgdn:spirit_stone —— 那是早已废弃的旧版灵石键，
                        // 结果是所有需要灵石的突破都恒报"灵石不足"。
                        long spiritStoneCount = getSpiritStoneCountWithLock(conn, player.getId());
                        if (spiritStoneCount < nextConfig.getRequiredSpiritStones()) {
                            long need = nextConfig.getRequiredSpiritStones() - spiritStoneCount;
                            return failure("灵石不足，还需要 " + need + " 灵石");
                        }
                    }

                    boolean isMajorBreakthrough = nextConfig.getId() > player.getRealm();
                    RealmBreakthroughResult result = new RealmBreakthroughResult();
                    result.setHpAdded(nextConfig.getHpBonus());
                    result.setMpAdded(nextConfig.getMpBonus());
                    result.setAttackAdded(nextConfig.getAttackBonus());
                    result.setDefenseAdded(nextConfig.getDefenseBonus());
                    result.setSpeedAdded(nextConfig.getSpeedBonus());
                    result.setSpiritAdded(nextConfig.getSpiritBonus());
                    result.setUnlockedSkillId(nextConfig.getUnlockSkillId());
                    result.setNewRealmName(nextConfig.getFullName());
                    result.setPityCount(player.getBreakthroughPity());
                    result.setConsolidation(player.getConsolidation());
                    result.setConsolidationCap(consolidateMaxLayers());

                    if (!isMajorBreakthrough) {
                        return applyBreakthroughSuccessWithConn(conn, player, nextConfig, result,
                            "突破成功！你已晋升为【" + nextConfig.getFullName() + "】", false);
                    }

                    return executeTribulationWithConn(conn, player, nextConfig, result);
                }
            }
        });
    }

    /**
     * 凝实（极境淬体）：修为圆满时不去渡劫，改为压制修为淬体。
     *
     * <p>这是给「卡在瓶颈」的玩家一条横向成长线：每层凝实永久提升基础属性
     * （读取时乘算，见 {@code PlayerService#getFinalAttack} 等），
     * 代价是渡劫成功率按层数打折、失败时的修为损失也被放大。
     * 渡劫成功后层数清零 —— 极境之力化为突破的踏脚石。</p>
     *
     * <p>只在<b>下一次是大境界渡劫</b>时开放：小境界突破本来就没有失败风险，
     * 凝实也就失去了意义。</p>
     */
    public RealmBreakthroughResult consolidate(long userId) {
        return DatabaseManager.runTransaction(conn -> {
            String lockSql = "SELECT * FROM players WHERE user_id = ? "
                    + DatabaseManager.getDialect().getForUpdateClause();
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return failure("玩家不存在");
                    }
                    Player player = mapPlayerFromResultSet(rs);

                    if (GameConfigLoader.isMaxRealm(player.getRealm(), player.getSubRealm())) {
                        return failure("已达最高境界，无需凝实");
                    }
                    RealmConfig nextConfig = GameConfigLoader.getNextRealmConfig(player.getRealm(), player.getSubRealm());
                    if (nextConfig == null) {
                        return failure("已是最高境界，无需凝实");
                    }
                    if (nextConfig.getId() <= player.getRealm()) {
                        return failure("小境界突破无需凝实，直接 /突破 即可进阶");
                    }
                    if (player.getExperience() < nextConfig.getRequiredExp()) {
                        long need = nextConfig.getRequiredExp() - player.getExperience();
                        return failure("修为尚未圆满，无法凝实。还需 " + need + " 点修为");
                    }

                    int layers = player.getConsolidation();
                    int cap = consolidateMaxLayers();
                    if (layers >= cap) {
                        return failure("已凝实 " + cap + " 层，达到极境极限。必须渡劫成功才能继续凝实");
                    }

                    player.setConsolidation(layers + 1);
                    updatePlayerWithConn(conn, player);

                    RealmBreakthroughResult result = new RealmBreakthroughResult();
                    result.setSuccess(true);
                    result.setNewRealmName(nextConfig.getFullName());
                    result.setConsolidation(player.getConsolidation());
                    result.setConsolidationCap(cap);
                    result.setPityCount(player.getBreakthroughPity());
                    result.addTribulationLog("你散去周身锋芒，将圆满的修为压回丹田...");
                    result.addTribulationLog("极境淬体：凝实第 " + player.getConsolidation() + " 层 / " + cap);
                    result.setMessage("凝实成功！你已踏入极境第 " + player.getConsolidation() + " 层\n"
                            + "基础属性提升（每层 +" + Math.max(0, AppConfig.getInt("breakthrough.consolidate_stat_bonus", 8))
                            + "%），但渡劫成功率将按层数打折、失败损失也会放大。\n"
                            + "渡劫成功后极境之力将化作突破的踏脚石（层数清零）。");
                    return result;
                }
            }
        });
    }

    /**
     * 突破预览：不掷骰、不改状态，只把当前的成功率构成摊开给玩家看。
     *
     * <p>玩家被概率劝退，往往不是因为失败本身，而是因为<b>看不见自己离成功有多远</b>。
     * 把基础率、各项加成、连败增益、凝实代价逐条列出来，失败一次看到数字上涨，
     * 才有继续修下去的动机。</p>
     */
    public RealmBreakthroughResult previewBreakthrough(long userId) {
        Player player = findPlayerByUserId(userId);
        if (player == null) {
            return failure("玩家不存在");
        }
        RealmBreakthroughResult result = new RealmBreakthroughResult();
        result.setConsolidation(player.getConsolidation());
        result.setConsolidationCap(consolidateMaxLayers());
        result.setPityCount(player.getBreakthroughPity());

        RealmConfig nextConfig = GameConfigLoader.getNextRealmConfig(player.getRealm(), player.getSubRealm());
        if (nextConfig == null) {
            return failure("已是最高境界，无法继续突破");
        }
        if (player.getExperience() < nextConfig.getRequiredExp()) {
            long need = nextConfig.getRequiredExp() - player.getExperience();
            return failure("修为不足，还需 " + need + " 点修为才能突破【" + nextConfig.getFullName() + "】");
        }
        if (nextConfig.getId() <= player.getRealm()) {
            // 小境界突破必成，没有概率可看
            result.setSuccess(true);
            result.setNewRealmName(nextConfig.getFullName());
            result.setHasTribulation(false);
            result.setFinalSuccessRate(100.0);
            result.setMessage("小境界突破必定成功，直接 /突破 即可晋升【" + nextConfig.getFullName() + "】");
            return result;
        }

        long remainingCooldown = checkBreakthroughCooldown(player);
        result.setHasTribulation(true);
        result.setNewRealmName(nextConfig.getFullName());
        if (remainingCooldown > 0) {
            result.addSuccessRateBreakdown("突破冷却中，还需 " + formatCooldown(remainingCooldown));
        }
        // 预览按"最不利"的天劫类型算（属性加成记 0），实际渡劫时天劫类型随机
        RateBreakdown rate = computeRate(player, null, 0);
        for (String line : rate.lines) {
            result.addSuccessRateBreakdown(line);
        }
        result.addSuccessRateBreakdown("天劫类型在渡劫时随机决定，对应属性加成 0~"
                + String.format("%.0f", MAX_STAT_BONUS) + "%");
        result.setBaseSuccessRate(rate.base);
        result.setFinalSuccessRate(rate.finalRate);
        // 用夹过上限的次数，保证"显示几次连败"与"实际加了多少成功率"一致
        result.setPityCount(rate.pityCount);
        result.setSuccess(true);
        result.setMessage("【" + nextConfig.getFullName() + "】渡劫预估\n"
                + "成功率 " + String.format("%.1f", rate.finalRate) + "% ~ "
                + String.format("%.1f", rate.finalRateWithMaxStat) + "%"
                + (rate.pityCount > 0 ? "（已连续失败 " + rate.pityCount + " 次，下次失败还会继续加）" : ""));
        return result;
    }

    private Player findPlayerByUserId(long userId) {
        com.mtxgdn.game.entity.PlayerInfo info = playerService.getPlayerByUserId(userId);
        if (info == null) {
            return null;
        }
        return playerService.getPlayerById(info.getId());
    }

    private Player mapPlayerFromResultSet(ResultSet rs) throws SQLException {
        Player player = new Player();
        player.setId(rs.getLong("id"));
        player.setUserId(rs.getLong("user_id"));
        player.setName(rs.getString("name"));
        player.setRealm(rs.getInt("realm"));
        player.setSubRealm(rs.getInt("sub_realm"));
        player.setExperience(rs.getLong("experience"));
        player.setHp(rs.getInt("hp"));
        player.setMaxHp(rs.getInt("max_hp"));
        player.setMp(rs.getInt("mp"));
        player.setMaxMp(rs.getInt("max_mp"));
        player.setAttack(rs.getInt("attack"));
        player.setDefense(rs.getInt("defense"));
        player.setSpeed(rs.getInt("speed"));
        player.setSpirit(rs.getInt("spirit"));
        player.setLastBreakthroughTime(rs.getLong("last_breakthrough_time"));
        try {
            player.setBreakthroughPity(rs.getInt("breakthrough_pity"));
        } catch (SQLException ignored) {
            // 老库尚未补列（理论上 initTable 已补），退化为 0
        }
        try {
            player.setConsolidation(rs.getInt("consolidation"));
        } catch (SQLException ignored) {
        }

        String rootStr = rs.getString("spiritual_root");
        if (rootStr != null && !rootStr.isEmpty()) {
            try {
                player.setSpiritualRoot(SpiritualRoot.valueOf(rootStr));
            } catch (Exception e) {
                // 忽略无效的灵根值
            }
        }
        return player;
    }

    private long getSpiritStoneCountWithLock(Connection conn, long playerId) throws SQLException {
        return itemService().getTotalSpiritStonesInLowGrade(conn, playerId);
    }

    private boolean hasItemWithLock(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String sql = "SELECT quantity FROM players_items WHERE player_id = ? AND item_key = ? "
                + DatabaseManager.getDialect().getForUpdateClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setString(2, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("quantity") >= quantity;
                }
            }
        }
        return false;
    }

    private void removeItemWithConn(Connection conn, long playerId, String itemKey, long quantity) throws SQLException {
        String sql = "UPDATE players_items SET quantity = quantity - ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_id = ? AND item_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, quantity);
            ps.setLong(2, playerId);
            ps.setString(3, itemKey);
            ps.executeUpdate();
        }
    }

    private void updatePlayerWithConn(Connection conn, Player player) throws SQLException {
        String sql = "UPDATE players SET realm = ?, sub_realm = ?, experience = ?, hp = ?, max_hp = ?, "
                + "mp = ?, max_mp = ?, attack = ?, defense = ?, speed = ?, spirit = ?, "
                + "last_breakthrough_time = ?, breakthrough_pity = ?, consolidation = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, player.getRealm());
            ps.setInt(2, player.getSubRealm());
            ps.setLong(3, player.getExperience());
            ps.setInt(4, player.getHp());
            ps.setInt(5, player.getMaxHp());
            ps.setInt(6, player.getMp());
            ps.setInt(7, player.getMaxMp());
            ps.setInt(8, player.getAttack());
            ps.setInt(9, player.getDefense());
            ps.setInt(10, player.getSpeed());
            ps.setInt(11, player.getSpirit());
            ps.setLong(12, player.getLastBreakthroughTime());
            ps.setInt(13, player.getBreakthroughPity());
            ps.setInt(14, player.getConsolidation());
            ps.setLong(15, player.getId());
            ps.executeUpdate();
        }
    }

    // ==================== 渡劫 ====================

    private RealmBreakthroughResult executeTribulationWithConn(Connection conn, Player player, RealmConfig nextConfig,
                                                              RealmBreakthroughResult result) throws Exception {
        result.setHasTribulation(true);

        String[] trib = pickTribulationForRealm(player.getRealm());
        String tribName = trib[0];
        String tribSubtitle = trib[1];
        String tribDesc = trib[2];

        result.setTribulationType(tribName);
        result.setTribulationDescription(tribDesc);

        result.addTribulationLog("你开始突破【" + nextConfig.getFullName() + "】...");
        result.addTribulationLog("天地感应，" + tribName + "降临！");
        result.addTribulationLog("——" + tribSubtitle + "——");
        result.addTribulationLog("【" + tribDesc + "】");

        double pillBonus = 0;
        if (hasItemWithLock(conn, player.getId(), "mtxgdn:tribulation_pill", 1)) {
            removeItemWithConn(conn, player.getId(), "mtxgdn:tribulation_pill", 1);
            pillBonus = TRIBULATION_PILL_BONUS;
            result.setTribulationItemUsed("tribulation_pill");
            result.addTribulationLog("你服下渡劫丹，体内灵力暴涨，对抗天劫的把握大增！");
        }

        RateBreakdown rate = computeRate(player, tribName, pillBonus);
        for (String line : rate.lines) {
            result.addSuccessRateBreakdown(line);
        }
        result.setBaseSuccessRate(rate.base);
        result.setFinalSuccessRate(rate.finalRate);
        result.setPityBonus(rate.pityBonus);
        result.setPityCount(rate.pityCount);
        result.setConsolidation(rate.consolidation);
        result.setConsolidationCap(consolidateMaxLayers());

        int roll = random.nextInt(10000);
        result.setRoll(roll);
        result.addTribulationLog("抵抗天劫几率: " + String.format("%.1f", rate.finalRate) + "%");

        boolean success = roll < (int) (rate.finalRate * 100);

        if (success) {
            result.addTribulationLog(tribName + "散去，天穹裂开一道缝隙，金光洒落！");
            result.addTribulationLog("劫后余生，道心更加坚定...");
            if (rate.consolidation > 0) {
                result.addTribulationLog("极境淬炼之积一朝化境，凝实之力尽数融入新境界。");
            }
            if (rate.pityCount > 0) {
                result.addTribulationLog("连败之厄就此了结，失败增益清零。");
            }
            return applyBreakthroughSuccessWithConn(conn, player, nextConfig, result,
                    "渡劫成功！你已晋升为【" + nextConfig.getFullName() + "】", true);
        }

        // ---- 失败：损失修为、重伤，并累积连败增益 ----
        int consolidation = player.getConsolidation();
        double punishMultiplier = 1.0 + consolidation * consolidatePunishBonus();
        long expLoss = (long) (nextConfig.getRequiredExp() / 3.0 * punishMultiplier);
        int hpLoss = player.getHp() > 1 ? player.getHp() - 1 : 0;

        int nextPity = Math.min(maxPityStack(), player.getBreakthroughPity() + 1);
        player.setBreakthroughPity(nextPity);

        result.setExpPenalty(expLoss);
        result.setHpPenalty(hpLoss);
        result.setPityCount(nextPity);
        result.addTribulationLog(tribName + "太过凶猛，你未能抵挡...");
        result.addTribulationLog("境界突破失败，修为大损！");
        result.addTribulationLog("损失经验 " + expLoss + " 点，身受重伤");
        if (consolidation > 0) {
            result.addTribulationLog("极境之躯在劫中受创更重（凝实 " + consolidation + " 层，损失放大 "
                    + String.format("%.0f", (punishMultiplier - 1) * 100) + "%）");
        }
        result.addTribulationLog("然此败亦有所得 —— 下次渡劫成功率 +"
                + String.format("%.0f", (double) nextPity * pityBonusPerFailure()) + "%（连败 " + nextPity + " 次）");

        player.setExperience(Math.max(0, player.getExperience() - expLoss));
        player.setHp(1);

        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
            int bonus = (int) (3 * root.getEffectValue());
            if (bonus < 1) bonus = 1;
            player.setAttack(player.getAttack() + (int) (bonus * (1 + root.getAttackBonus())));
            player.setDefense(player.getDefense() + (int) (bonus * (1 + root.getDefenseBonus())));
            player.setSpeed(player.getSpeed() + (int) (bonus * (1 + root.getSpeedBonus())));
            result.addTribulationLog("然混沌灵根在劫难中反而淬炼...获得少量属性提升！");
        }

        updatePlayerWithConn(conn, player);

        result.setSuccess(false);
        result.setMessage("渡劫失败！你在" + result.getTribulationType() + "中身受重伤，损失了 "
                + expLoss + " 点经验\n"
                + "连败增益已累积至 " + nextPity + " 次，下次渡劫成功率 +"
                + String.format("%.0f", (double) nextPity * pityBonusPerFailure()) + "%");
        return result;
    }

    /**
     * 成功率构成。实际渡劫与「预览」共用同一套公式 —— 两边各写一份是这类系统最容易
     * 出现「预览说 60%、实际按 40% 掷骰」的地方。
     */
    private static final class RateBreakdown {
        double base;
        double pityBonus;
        double consolidationMultiplier = 1.0;
        double finalRate;
        /** 天劫属性加成取满时的成功率（用于预览给出区间）。 */
        double finalRateWithMaxStat;
        int pityCount;
        int consolidation;
        List<String> lines = new ArrayList<>();
    }

    /**
     * @param tribName  天劫类型；{@code null} 表示预览（属性加成记 0）
     * @param pillBonus 渡劫丹加成（已确定要服下时传 10）
     */
    private RateBreakdown computeRate(Player player, String tribName, double pillBonus) {
        RateBreakdown r = new RateBreakdown();
        r.base = getBaseSuccessRate(player.getRealm());
        // 读的时候也夹一次上限：写侧有上限，但库里可能存在早期数据或手工改库的脏值，
        // 不夹的话加成会被放大成一个荒唐的数字（虽然最终率本来就有 95% 硬顶）。
        r.pityCount = Math.min(maxPityStack(), player.getBreakthroughPity());
        r.consolidation = player.getConsolidation();

        r.lines.add("基础成功率: " + String.format("%.0f", r.base) + "%");

        double spiritBonus = Math.min(15.0, player.getSpirit() / 50.0);
        if (spiritBonus > 0) {
            r.lines.add("灵力加成: +" + String.format("%.0f", spiritBonus) + "% (灵力 " + player.getSpirit() + ")");
        }

        double rootBonus = getSpiritualRootBonus(player.getSpiritualRoot(), player.getRealm());
        if (rootBonus > 0) {
            r.lines.add("灵根加成: +" + String.format("%.0f", rootBonus) + "%");
        } else if (rootBonus < 0) {
            r.lines.add("灵根减益: " + String.format("%.0f", rootBonus) + "%");
        }

        double statBonus = tribName == null ? 0.0 : Math.min(MAX_STAT_BONUS, getTribulationStatBonus(tribName, player));
        double maxStatBonus = tribName == null ? MAX_STAT_BONUS : statBonus;
        if (statBonus > 0) {
            r.lines.add(tribName + "对应属性加成: +" + String.format("%.1f", statBonus) + "%");
        }

        if (pillBonus > 0) {
            r.lines.add("渡劫丹加成: +" + String.format("%.0f", pillBonus) + "%");
        }

        // ---- 失败增益（pity）：连续失败越多次，下一次越接近必成 ----
        if (r.pityCount > 0) {
            r.pityBonus = r.pityCount * pityBonusPerFailure();
            r.lines.add("连败增益: +" + String.format("%.0f", r.pityBonus)
                    + "% (已连续失败 " + r.pityCount + " 次)");
        }

        double raw = r.base + spiritBonus + rootBonus + statBonus + pillBonus + r.pityBonus;
        double rawMax = r.base + spiritBonus + rootBonus + maxStatBonus + pillBonus + r.pityBonus;

        // ---- 凝实代价：成功率按层数乘算打折（与参照实现一致，作用在汇总之后） ----
        int penaltyPerLayer = consolidateRatePenalty();
        if (r.consolidation > 0 && penaltyPerLayer > 0) {
            int deducted = Math.min(9 * 10, r.consolidation * penaltyPerLayer);
            r.consolidationMultiplier = (100 - deducted) / 100.0;
            r.lines.add("极境凝实: ×" + String.format("%.2f", r.consolidationMultiplier)
                    + " (" + r.consolidation + " 层，-" + deducted + "%)");
        }

        r.finalRate = clampRate(Math.min(MAX_SUCCESS_RATE, raw) * r.consolidationMultiplier);
        r.finalRateWithMaxStat = clampRate(Math.min(MAX_SUCCESS_RATE, rawMax) * r.consolidationMultiplier);
        r.lines.add("最终成功率: " + String.format("%.1f", r.finalRate) + "%");
        return r;
    }

    private static double clampRate(double rate) {
        return Math.max(MIN_SUCCESS_RATE, Math.min(MAX_SUCCESS_RATE, rate));
    }

    private RealmBreakthroughResult applyBreakthroughSuccessWithConn(Connection conn, Player player, RealmConfig nextConfig,
                                                                   RealmBreakthroughResult result, String message,
                                                                   boolean majorBreakthrough) throws Exception {
        player.setRealm(nextConfig.getId());
        player.setSubRealm(nextConfig.getSubRealm());
        player.setMaxHp(player.getMaxHp() + nextConfig.getHpBonus());
        player.setHp(player.getMaxHp());
        player.setMaxMp(player.getMaxMp() + nextConfig.getMpBonus());
        player.setMp(player.getMaxMp());
        player.setAttack(player.getAttack() + nextConfig.getAttackBonus());
        player.setDefense(player.getDefense() + nextConfig.getDefenseBonus());
        player.setSpeed(player.getSpeed() + nextConfig.getSpeedBonus());
        player.setSpirit(player.getSpirit() + nextConfig.getSpiritBonus());
        player.setExperience(player.getExperience() - nextConfig.getRequiredExp());
        player.setLastBreakthroughTime(System.currentTimeMillis());
        // 任何一次成功突破都清空连败增益与极境层数：两者都是"为这一次渡劫攒的"
        player.setBreakthroughPity(0);
        player.setConsolidation(0);

        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER)) {
            int bonus = (int) (5 * root.getEffectValue());
            if (bonus < 1) bonus = 1;
            player.setAttack(player.getAttack() + (int) (bonus * (1 + root.getAttackBonus())));
            player.setDefense(player.getDefense() + (int) (bonus * (1 + root.getDefenseBonus())));
            player.setSpeed(player.getSpeed() + (int) (bonus * (1 + root.getSpeedBonus())));
        }

        updatePlayerWithConn(conn, player);

        if (nextConfig.getRequiredSpiritStones() > 0) {
            // 按档位从高到低扣，与发放时的分配策略对称（见 ItemService.addSpiritStonesAuto）。
            // 旧实现扣的是已废弃的 mtxgdn:spirit_stone，等于突破不花灵石。
            itemService().removeSpiritStones(conn, player.getId(), nextConfig.getRequiredSpiritStones());
        }

        // 大境界突破成功授予「破天者」称号 + 集中检查境界/战力类称号（不朽尊者/逆天者/大道之主/混沌帝尊/剑道宗师）
        if (majorBreakthrough) {
            try {
                ServiceRegistry.getTitleService().grantTitle(player.getId(), "sky_breaker");
            } catch (Exception ignored) {
                // 称号授予失败不影响突破
            }
        }
        try {
            ServiceRegistry.getTitleService().checkAndGrantTitles(player);
        } catch (Exception ignored) {
        }

        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    // ==================== 概率构成 ====================

    private double getBaseSuccessRate(int currentRealm) {
        if (currentRealm >= 0 && currentRealm < BASE_SUCCESS_RATES.length) {
            return BASE_SUCCESS_RATES[currentRealm];
        }
        return 80.0;
    }

    private double getSpiritualRootBonus(SpiritualRoot root, int currentRealm) {
        if (root == null) return 0;
        return switch (root.getTier()) {
            case PRIMORDIAL -> 35.0;
            case PERFECT -> 25.0;
            case HEAVENLY -> 15.0;
            case VARIANT -> 10.0;
            case DUAL -> 5.0;
            case TRIPLE -> 0;
            case QUAD -> -5.0;
            case MIXED -> root.hasEffect(SpiritualRoot.SpecialEffect.LATE_BLOOMER) && currentRealm >= 4 ? 15.0 : -10.0;
        };
    }

    private double getTribulationStatBonus(String tribulationType, Player player) {
        return switch (tribulationType) {
            case "雷劫" -> player.getDefense() / 500.0;
            case "心魔劫" -> player.getSpirit() / 400.0;
            case "风火劫" -> player.getSpeed() / 300.0;
            case "水劫" -> player.getDefense() / 350.0;
            case "五行劫" -> (player.getAttack() + player.getDefense() + player.getSpirit()) / 1200.0;
            case "阴阳劫" -> (player.getSpirit() * 2 + player.getDefense()) / 900.0;
            case "轮回劫" -> player.getSpirit() / 300.0;
            default -> 0;
        };
    }

    private String[] pickTribulationForRealm(int realm) {
        List<String[]> available = new ArrayList<>();
        for (int i = 0; i < TRIBULATION_TYPES.length; i++) {
            for (int r : TRIBULATION_REALMS[i]) {
                if (r == realm) {
                    available.add(TRIBULATION_TYPES[i]);
                    break;
                }
            }
        }
        if (available.isEmpty()) {
            return TRIBULATION_TYPES[random.nextInt(TRIBULATION_TYPES.length)];
        }
        return available.get(random.nextInt(available.size()));
    }

    private long checkBreakthroughCooldown(Player player) {
        long lastBreakthrough = player.getLastBreakthroughTime();
        if (lastBreakthrough <= 0) {
            return 0;
        }

        int realmIndex = player.getRealm();
        if (realmIndex >= BREAKTHROUGH_COOLDOWN_SECONDS.length) {
            realmIndex = BREAKTHROUGH_COOLDOWN_SECONDS.length - 1;
        }

        long cooldownSeconds = BREAKTHROUGH_COOLDOWN_SECONDS[realmIndex];
        long now = System.currentTimeMillis();
        long elapsedSeconds = (now - lastBreakthrough) / 1000;

        return Math.max(0, cooldownSeconds - elapsedSeconds);
    }

    private String formatCooldown(long remainingSeconds) {
        if (remainingSeconds < 60) {
            return remainingSeconds + "秒";
        } else if (remainingSeconds < 3600) {
            return (remainingSeconds / 60) + "分钟";
        } else if (remainingSeconds < 86400) {
            long hours = remainingSeconds / 3600;
            long minutes = (remainingSeconds % 3600) / 60;
            return hours + "小时" + (minutes > 0 ? minutes + "分钟" : "");
        } else {
            long days = remainingSeconds / 86400;
            long hours = (remainingSeconds % 86400) / 3600;
            return days + "天" + (hours > 0 ? hours + "小时" : "");
        }
    }

    private RealmBreakthroughResult failure(String message) {
        RealmBreakthroughResult result = new RealmBreakthroughResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}
