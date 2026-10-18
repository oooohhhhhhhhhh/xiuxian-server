package com.mtxgdn.game.service;

import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.RealmBreakthroughResult;
import com.mtxgdn.game.entity.RealmConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 突破改造回归测试：<b>连败增益（pity）</b> + <b>凝实（极境）</b>。
 *
 * <p>钉住的语义：</p>
 * <ol>
 *   <li><b>连败增益</b>：渡劫失败 +1 次、成功率按次数加成、成功后清零、加成次数有上限；</li>
 *   <li><b>失败不推进境界</b>：只损失修为、重伤，境界不动；</li>
 *   <li><b>凝实</b>：仅在大境界瓶颈且修为圆满时可用，最多 3 层，
 *       每层提升基础属性但降低成功率、放大失败损失，渡劫成功后清零；</li>
 *   <li><b>预览不改变状态</b>，且与实际渡劫共用同一套公式。</li>
 * </ol>
 *
 * <p>渡劫本身是随机的，所以测试用 {@link RealmService#setRandomForTest} 注入一个
 * 固定掷骰的随机源 —— 否则"失败路径"只能靠反复重试撞出来，既慢又不稳。</p>
 */
class BreakthroughPityTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final RealmService realmService = new RealmService(ServiceRegistry.getPlayerService());
    private final PlayerService playerService = ServiceRegistry.getPlayerService();

    @BeforeAll
    static void registerItems() {
        com.mtxgdn.game.item.ItemScanner.scanAndRegister();
    }

    // ==================== 连败增益 ====================

    @Test
    @DisplayName("pity: 渡劫失败累积次数，境界不动、修为减少、重伤")
    void failureAccumulatesPity() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        long expBefore = experienceOf(playerId);
        forceRoll(9999); // 必失败（成功率上限 95%）

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);

        assertFalse(result.isSuccess(), "掷出 99.99% 必然失败");
        Player after = playerService.getPlayerById(playerId);
        assertEquals(9, after.getRealm(), "失败不得推进境界");
        assertEquals(2, after.getSubRealm());
        assertEquals(1, after.getBreakthroughPity(), "失败应累积 1 次连败");
        assertEquals(1, after.getHp(), "失败应重伤到 1 点生命");
        assertTrue(after.getExperience() < expBefore, "失败应损失修为");
        assertEquals(after.getExperience(), expBefore - result.getExpPenalty());
    }

    @Test
    @DisplayName("pity: 连续失败会逐次累加")
    void pityStacksAcrossFailures() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        forceRoll(9999);

        realmService.tryBreakthrough(userId);
        realmService.tryBreakthrough(userId);
        realmService.tryBreakthrough(userId);

        assertEquals(3, playerService.getPlayerById(playerId).getBreakthroughPity());
    }

    @Test
    @DisplayName("pity: 渡劫成功后连败增益清零")
    void successResetsPity() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setPity(playerId, 7);
        forceRoll(0); // 必成功

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);

        assertTrue(result.isSuccess(), String.valueOf(result.getMessage()));
        assertEquals(0, playerService.getPlayerById(playerId).getBreakthroughPity(),
                "成功后连败增益必须清零");
    }

    @Test
    @DisplayName("pity: 小境界突破必成，同样清零连败增益")
    void minorBreakthroughResetsPity() throws SQLException {
        long userId = createPlayer(3, 0, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setPity(playerId, 4);
        setExperience(playerId, requiredExp(3, 0));

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);

        assertTrue(result.isSuccess(), String.valueOf(result.getMessage()));
        assertFalse(result.isHasTribulation(), "小境界突破不该有渡劫");
        assertEquals(0, playerService.getPlayerById(playerId).getBreakthroughPity());
    }

    @Test
    @DisplayName("pity: 增益按次数进入成功率（预览可见）")
    void pityBonusEntersRate() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setPity(playerId, 3);

        RealmBreakthroughResult preview = realmService.previewBreakthrough(userId);

        double base = 10.0; // realm 9 的基础成功率
        double expected = base + 3 * RealmService.pityBonusPerFailure();
        assertEquals(base, preview.getBaseSuccessRate(), 0.001);
        assertEquals(3, preview.getPityCount());
        assertEquals(expected, preview.getFinalSuccessRate(), 0.001,
                "成功率应为 基础 + 失败次数 × 每次加成");
    }

    @Test
    @DisplayName("pity: 加成次数有上限，且最终成功率不超过 95%")
    void pityBonusIsCapped() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setPity(playerId, 999); // 远超上限

        RealmBreakthroughResult preview = realmService.previewBreakthrough(userId);

        assertEquals(95.0, preview.getFinalSuccessRate(), 0.001,
                "再怎么连败也留 5% 天意");
    }

    // ==================== 凝实（极境） ====================

    @Test
    @DisplayName("凝实: 小境界瓶颈不可凝实")
    void consolidateRejectsMinorBottleneck() throws SQLException {
        long userId = createPlayer(3, 0, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);

        RealmBreakthroughResult result = realmService.consolidate(userId);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("小境界"), result.getMessage());
        assertEquals(0, playerService.getPlayerById(playerId).getConsolidation());
    }

    @Test
    @DisplayName("凝实: 修为未圆满时拒绝")
    void consolidateRequiresFullExp() throws SQLException {
        long userId = createPlayer(0, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setExperience(playerId, 0);

        RealmBreakthroughResult result = realmService.consolidate(userId);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("圆满"), result.getMessage());
    }

    @Test
    @DisplayName("凝实: 修为圆满时凝实一层，基础属性按层提升")
    void consolidateGrantsLayerAndStats() throws SQLException {
        long userId = createPlayer(0, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setExperience(playerId, requiredExp(0, 2));

        Player before = playerService.getPlayerById(playerId);
        int attackBefore = playerService.getFinalAttack(before);

        RealmBreakthroughResult result = realmService.consolidate(userId);

        assertTrue(result.isSuccess(), String.valueOf(result.getMessage()));
        Player after = playerService.getPlayerById(playerId);
        assertEquals(1, after.getConsolidation());
        int attackAfter = playerService.getFinalAttack(after);
        assertTrue(attackAfter > attackBefore,
                "凝实应提升最终攻击力：" + attackBefore + " -> " + attackAfter);
        // 每层 +8%：20 攻击 → 21.6 → 四舍五入 22
        int perLayer = com.mtxgdn.util.AppConfig.getInt("breakthrough.consolidate_stat_bonus", 8);
        assertEquals((int) Math.round(after.getAttack() * (1 + perLayer / 100.0)), attackAfter);
    }

    @Test
    @DisplayName("凝实: 最多 3 层，第 4 次拒绝")
    void consolidateCapsAtMaxLayers() throws SQLException {
        long userId = createPlayer(0, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setExperience(playerId, requiredExp(0, 2));

        for (int i = 1; i <= 3; i++) {
            RealmBreakthroughResult r = realmService.consolidate(userId);
            assertTrue(r.isSuccess(), "第 " + i + " 层应成功: " + r.getMessage());
        }
        RealmBreakthroughResult fourth = realmService.consolidate(userId);

        assertFalse(fourth.isSuccess(), "第 4 层应被拒绝");
        assertTrue(fourth.getMessage().contains("极限"), fourth.getMessage());
        assertEquals(3, playerService.getPlayerById(playerId).getConsolidation());
    }

    @Test
    @DisplayName("凝实: 每层按比例打折渡劫成功率")
    void consolidateLowersSuccessRate() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);

        double rateBefore = realmService.previewBreakthrough(userId).getFinalSuccessRate();
        realmService.consolidate(userId);
        double rateAfter = realmService.previewBreakthrough(userId).getFinalSuccessRate();

        int penalty = RealmService.consolidateRatePenalty();
        assertEquals(rateBefore * (100 - penalty) / 100.0, rateAfter, 0.001,
                "凝实应按层数乘算打折成功率");
        assertTrue(rateAfter < rateBefore);
    }

    @Test
    @DisplayName("凝实: 渡劫失败的修为损失按层数放大")
    void consolidateAmplifiesFailureLoss() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        realmService.consolidate(userId);
        realmService.consolidate(userId);
        realmService.consolidate(userId);
        forceRoll(9999);

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);

        assertFalse(result.isSuccess());
        long required = requiredExp(9, 2);
        long expected = (long) (required / 3.0 * (1 + 3 * RealmService.consolidatePunishBonus()));
        assertEquals(expected, result.getExpPenalty(),
                "3 层凝实的失败损失应为基准的 1.9 倍");
    }

    @Test
    @DisplayName("凝实: 渡劫成功后层数清零")
    void successResetsConsolidation() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        realmService.consolidate(userId);
        realmService.consolidate(userId);
        assertEquals(2, playerService.getPlayerById(playerId).getConsolidation());
        forceRoll(0);

        RealmBreakthroughResult result = realmService.tryBreakthrough(userId);

        assertTrue(result.isSuccess(), String.valueOf(result.getMessage()));
        Player after = playerService.getPlayerById(playerId);
        assertEquals(0, after.getConsolidation(), "成功后极境层数必须清零");
        assertTrue(after.getRealm() > 9, "境界应当推进");
    }

    // ==================== 预览 ====================

    @Test
    @DisplayName("预览: 不改变任何状态")
    void previewDoesNotMutateState() throws SQLException {
        long userId = createPlayer(9, 2, 0, "FIRE_WOOD_EARTH");
        long playerId = playerIdOf(userId);
        setPity(playerId, 2);
        setExperience(playerId, 12345);
        long expBefore = experienceOf(playerId);

        realmService.previewBreakthrough(userId);
        realmService.previewBreakthrough(userId);

        Player after = playerService.getPlayerById(playerId);
        assertEquals(2, after.getBreakthroughPity());
        assertEquals(expBefore, after.getExperience());
        assertEquals(9, after.getRealm());
    }

    @Test
    @DisplayName("预览: 小境界突破直接告知必成")
    void previewReportsGuaranteedMinorBreakthrough() throws SQLException {
        long userId = createPlayer(3, 0, 0, "FIRE_WOOD_EARTH");
        setExperience(playerIdOf(userId), requiredExp(3, 0));

        RealmBreakthroughResult preview = realmService.previewBreakthrough(userId);

        assertTrue(preview.isSuccess());
        assertFalse(preview.isHasTribulation());
        assertEquals(100.0, preview.getFinalSuccessRate(), 0.001);
    }

    @Test
    @DisplayName("预览: 修为不足时给出还差多少")
    void previewReportsMissingExp() throws SQLException {
        long userId = createPlayer(3, 0, 0, "FIRE_WOOD_EARTH");
        setExperience(playerIdOf(userId), 0);

        RealmBreakthroughResult preview = realmService.previewBreakthrough(userId);

        assertFalse(preview.isSuccess());
        assertTrue(preview.getMessage().contains("修为不足"), preview.getMessage());
    }

    // ==================== 辅助 ====================

    /** 注入一个固定掷骰的随机源：{@code roll} 决定渡劫成败。 */
    private void forceRoll(int roll) {
        realmService.setRandomForTest(new Random() {
            @Override
            public int nextInt(int bound) {
                return bound == 10000 ? roll : 0;
            }
        });
    }

    private long requiredExp(int realm, int subRealm) {
        RealmConfig next = GameConfigLoader.getNextRealmConfig(realm, subRealm);
        assertNotNull(next, "境界配置缺失: " + realm + "/" + subRealm);
        return next.getRequiredExp();
    }

    private long playerIdOf(long userId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "角色不存在: userId=" + userId);
                return rs.getLong(1);
            }
        }
    }

    private long experienceOf(long playerId) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT experience FROM players WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void setExperience(long playerId, long exp) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE players SET experience = ? WHERE id = ?")) {
            ps.setLong(1, exp);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private void setPity(long playerId, int pity) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE players SET breakthrough_pity = ? WHERE id = ?")) {
            ps.setInt(1, pity);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private long createPlayer(int realm, int subRealm, int spirit, String root) throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "pityuser" + n;
        // 修为直接给到突破门槛的 10 倍：连败几次也够扣，不必每步手动补
        long exp = requiredExp(realm, subRealm) * 10;
        long userId;
        long playerId;
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO users (username, password) VALUES (?, ?)")) {
                ps.setString(1, username);
                ps.setString(2, "x");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "用户写入失败");
                    userId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO players (user_id, name, spiritual_root, realm, sub_realm, hp, max_hp, "
                            + "mp, max_mp, attack, defense, speed, spirit, experience, "
                            + "last_breakthrough_time) VALUES (?, ?, ?, ?, ?, 100, 100, 50, 50, "
                            + "20, 10, 10, ?, ?, 0)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
                ps.setString(3, root);
                ps.setInt(4, realm);
                ps.setInt(5, subRealm);
                ps.setInt(6, spirit);
                ps.setLong(7, exp);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "角色写入失败");
                    playerId = rs.getLong(1);
                }
            }
        }
        // 大境界突破还要扣灵石，直接按门槛的两倍发下去。
        // 注意必须放在上面的连接关闭之后：addSpiritStones 会自己再取一条连接，
        // 在持有连接时嵌套取连接会把连接池耗死。
        RealmConfig next = GameConfigLoader.getNextRealmConfig(realm, subRealm);
        if (next != null && next.getRequiredSpiritStones() > 0) {
            ServiceRegistry.getItemService()
                    .addSpiritStones(playerId, next.getRequiredSpiritStones() * 2);
        }
        return userId;
    }
}
