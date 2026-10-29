package com.mtxgdn.game.service;

import com.mtxgdn.game.worldboss.WorldBossChallengeResult;
import com.mtxgdn.TestBase;
import com.mtxgdn.common.service.ServiceRegistry;
import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.WorldBoss;
import com.mtxgdn.game.entity.WorldBossDamage;
import com.mtxgdn.game.item.ItemScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界 Boss（B6）回归测试。
 *
 * <p>钉住的六条不变量：</p>
 * <ol>
 *   <li><b>池子生命周期</b>：按配置数量生成、TTL 内稳定、到期重建；</li>
 *   <li><b>血量全服共享</b>：A 打掉的血，B 再看就少了那么多；</li>
 *   <li><b>伤害分红</b>：{@code 灵石 = 预算 × 本次伤害 / 最大血量}，精确到整数；</li>
 *   <li><b>超发熔断</b>：单次奖励超过阈值时按 {@code 阈值 / (5 + rand(6))} 折算；</li>
 *   <li><b>挑战冷却</b>：同一玩家连续挑战只会有一次真正进入战斗；</li>
 *   <li><b>击杀归属</b>：补最后一刀的人才被记为击杀者。</li>
 * </ol>
 */
class WorldBossTest extends TestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long HOUR_MS = 3600_000L;

    private final WorldBossService worldBossService = new WorldBossService();

    @BeforeAll
    static void registerItems() {
        // 灵石本身就是一件物品：不先扫描注册，所有涉及灵石的断言都会以"物品不存在"炸掉
        ItemScanner.scanAndRegister();
    }

    // ==================== 池子生命周期 ====================

    @Test
    @DisplayName("池子: 按配置数量生成，满血且未到期")
    void poolSpawnsConfiguredBossCount() {
        String scope = newScope();
        long now = System.currentTimeMillis();

        List<WorldBoss> pool = worldBossService.getPool(scope, now);

        assertEquals(WorldBossService.bossCount(), pool.size());
        for (int i = 0; i < pool.size(); i++) {
            WorldBoss boss = pool.get(i);
            assertEquals(i + 1, boss.getSlot(), "池内序号应从 1 连续递增");
            assertTrue(boss.getMaxHp() > 0, "血量必须为正");
            assertEquals(boss.getMaxHp(), boss.getHp(), "新生成的 Boss 应满血");
            assertTrue(boss.getExpiresAt() > now, "未到期");
            assertTrue(boss.getStoneBudget() > 0);
            assertTrue(boss.getExpBudget() > 0);
            assertFalse(boss.isDead());
            assertNotNull(boss.getName());
        }
    }

    @Test
    @DisplayName("池子: TTL 内重复读取不会重建")
    void poolStableWithinTtl() {
        String scope = newScope();
        long now = System.currentTimeMillis();

        List<Long> first = ids(worldBossService.getPool(scope, now));
        List<Long> second = ids(worldBossService.getPool(scope, now + 60_000));

        assertEquals(first, second, "TTL 内必须返回同一批 Boss（无内存态，靠库里的一致性）");
    }

    @Test
    @DisplayName("池子: 到期后整池重建（换一批新的）")
    void poolRebuildsAfterExpiry() {
        String scope = newScope();
        long now = System.currentTimeMillis();
        List<Long> first = ids(worldBossService.getPool(scope, now));

        long afterExpiry = now + (WorldBossService.respawnHours() + 1) * HOUR_MS;
        List<Long> second = ids(worldBossService.getPool(scope, afterExpiry));

        assertEquals(first.size(), second.size());
        Set<Long> overlap = new HashSet<>(first);
        overlap.retainAll(second);
        assertTrue(overlap.isEmpty(), "到期后应换成一批全新的 Boss，旧 id 不应残留");
    }

    @Test
    @DisplayName("池子: 管理员刷新立即换一批")
    void refreshReplacesPool() {
        String scope = newScope();
        long now = System.currentTimeMillis();
        List<Long> before = ids(worldBossService.getPool(scope, now));

        List<Long> after = ids(worldBossService.refreshPool(scope, now));

        assertFalse(before.equals(after), "刷新应换一批 Boss");
        assertEquals(before.size(), after.size());
    }

    // ==================== 超发熔断 ====================

    @Test
    @DisplayName("熔断: 正常奖励原样通过")
    void breakerPassesThroughNormalReward() {
        assertEquals(1234L, worldBossService.applyOverflowBreaker(1234L));
        assertEquals(0L, worldBossService.applyOverflowBreaker(0L));
        long cap = WorldBossService.maxSingleReward();
        assertEquals(cap, worldBossService.applyOverflowBreaker(cap), "刚好等于阈值不应被折算");
    }

    @Test
    @DisplayName("熔断: 超阈值奖励被折算到阈值/(5+rand(6))")
    void breakerCapsHugeReward() {
        long cap = WorldBossService.maxSingleReward();
        long reduced = worldBossService.applyOverflowBreaker(cap * 50);

        assertTrue(reduced > 0, "折算后仍应有奖励");
        assertTrue(reduced <= cap / 5, "折算上限为 阈值/5，实际 " + reduced);
        assertTrue(reduced >= cap / 10, "折算下限为 阈值/10，实际 " + reduced);
    }

    // ==================== 挑战与共享血量 ====================

    @Test
    @DisplayName("挑战: 一击必杀时按满额预算分红，并记录击杀者")
    void oneShotPaysFullBudgetAndAttributesKiller() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1000, 1000, 10_000, 10_000);
        long playerId = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);

        WorldBossChallengeResult result = worldBossService.challenge(playerId, scope, "1", now);

        assertTrue(result.success(), result.message());
        assertEquals(1000L, result.damage(), "一击必杀应打满整条血");
        assertEquals(true, result.killed());
        assertEquals(0L, result.hpRemaining());
        assertEquals(10_000L, result.stones(), "满伤害应拿到全部灵石预算");
        // 满伤害 + 击杀加成 20%
        assertEquals(12_000L, result.exp());
        assertEquals(false, result.capped());

        WorldBoss after = worldBossService.getBossById(boss.getId());
        assertEquals(0L, after.getHp());
        assertTrue(after.isDead());
        assertEquals(playerId, after.getKillerPlayerId());
        assertTrue(after.getKilledAt() > 0);
    }

    @Test
    @DisplayName("挑战: 伤害不足时按比例分红（精确整数公式）")
    void partialDamagePaysProportionally() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        long maxHp = 1_000_000L;
        long stoneBudget = 50_000L;
        long expBudget = 90_000L;
        overrideBoss(boss.getId(), 0, maxHp, maxHp, stoneBudget, expBudget);
        // 攻击力压到很低：30 回合也打不掉多少，血量堆高保证不会被打死
        long playerId = createPlayer(0, 0, 200, 5_000_000, 100);

        WorldBossChallengeResult result = worldBossService.challenge(playerId, scope, "1", now);

        assertTrue(result.success(), result.message());
        long damage = result.damage();
        assertTrue(damage > 0 && damage < maxHp, "应当是部分伤害，实际 " + damage);
        assertEquals(stoneBudget * damage / maxHp, result.stones(),
                "灵石必须严格等于 预算 × 伤害 / 最大血量");
        assertEquals(expBudget * damage / maxHp, result.exp(),
                "修为同理（未击杀，无击杀加成）");
        assertEquals(maxHp - damage, result.hpRemaining());
    }

    @Test
    @DisplayName("挑战: 血量全服共享 —— 后打的玩家从更低的血量起算")
    void sharedHpAcrossPlayers() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        long maxHp = 1_000_000L;
        overrideBoss(boss.getId(), 0, maxHp, maxHp, 50_000, 90_000);
        long first = createPlayer(0, 0, 200, 5_000_000, 100);
        long second = createPlayer(0, 0, 200, 5_000_000, 100);

        WorldBossChallengeResult r1 = worldBossService.challenge(first, scope, "1", now);
        long hpAfterFirst = r1.hpRemaining();
        assertEquals(maxHp - r1.damage(), hpAfterFirst);
        assertEquals(hpAfterFirst, worldBossService.getBossById(boss.getId()).getHp(),
                "共享血量必须落库");

        // 第二个玩家必须在剩余血量的基础上继续打
        long later = now + WorldBossService.challengeCooldownMs() + 1000;
        WorldBossChallengeResult r2 = worldBossService.challenge(second, scope, "1", later);
        assertTrue(r2.success(), r2.message());
        long hpAfterSecond = r2.hpRemaining();
        assertTrue(hpAfterSecond < hpAfterFirst, "第二个人打完后血量应继续下降");
        assertEquals(hpAfterFirst - r2.damage(), hpAfterSecond,
                "第二个人是在第一个人打剩的血量上继续扣的");
        assertEquals(hpAfterSecond, worldBossService.getBossById(boss.getId()).getHp(),
                "库里的血量必须等于最后一次挑战返回的剩余血量");
    }

    @Test
    @DisplayName("挑战: 冷却期内第二次挑战被拒绝")
    void cooldownBlocksSecondChallenge() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1_000_000, 1_000_000, 50_000, 90_000);
        long playerId = createPlayer(0, 0, 200, 5_000_000, 100);

        WorldBossChallengeResult first = worldBossService.challenge(playerId, scope, "1", now);
        assertTrue(first.success(), first.message());

        WorldBossChallengeResult second = worldBossService.challenge(playerId, scope, "1", now + 1000);
        assertFalse(second.success(), "冷却期内不应再次进入战斗");
        assertTrue(second.message().contains("冷却"),
                second.message());
    }

    @Test
    @DisplayName("挑战: 冷却过后可以再次挑战")
    void cooldownExpiresAllowsChallengeAgain() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1_000_000, 1_000_000, 50_000, 90_000);
        long playerId = createPlayer(0, 0, 200, 5_000_000, 100);

        assertTrue(worldBossService.challenge(playerId, scope, "1", now).success());

        long later = now + WorldBossService.challengeCooldownMs() + 1000;
        WorldBossChallengeResult again = worldBossService.challenge(playerId, scope, "1", later);
        assertTrue(again.success(), again.message());
    }

    @Test
    @DisplayName("挑战: 已被击杀的 Boss 不能再打")
    void deadBossRejected() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1000, 1000, 10_000, 10_000);
        long playerId = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);
        assertTrue(worldBossService.challenge(playerId, scope, "1", now).success());

        long other = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);
        WorldBossChallengeResult result = worldBossService.challenge(other, scope, "1",
                now + WorldBossService.challengeCooldownMs() + 1000);

        assertFalse(result.success());
        assertTrue(result.message().contains("已被击杀"),
                result.message());
    }

    @Test
    @DisplayName("挑战: 境界差距过大被拒绝")
    void realmGapRejected() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        // Boss 拉到 9 大境界（序位 27），玩家停在 0 大境界（序位 0）
        overrideBoss(boss.getId(), 9, 1_000_000, 1_000_000, 50_000, 90_000);
        long playerId = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);

        WorldBossChallengeResult result = worldBossService.challenge(playerId, scope, "1", now);

        assertFalse(result.success());
        assertTrue(result.message().contains("境界"),
                result.message());
        assertEquals(1_000_000L, worldBossService.getBossById(boss.getId()).getHp(),
                "被拒绝的挑战不得扣血");
    }

    @Test
    @DisplayName("挑战: 闭关中不可挑战")
    void cultivatingPlayerRejected() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1_000_000, 1_000_000, 50_000, 90_000);
        long playerId = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE players SET is_cultivating = 1 WHERE id = ?")) {
            ps.setLong(1, playerId);
            ps.executeUpdate();
        }

        WorldBossChallengeResult result = worldBossService.challenge(playerId, scope, "1", now);

        assertFalse(result.success());
        assertTrue(result.message().contains("闭关"),
                result.message());
    }

    // ==================== 台账与排行 ====================

    @Test
    @DisplayName("台账: 累计伤害与次数递增，排行榜按伤害降序")
    void ledgerAccumulatesAndLeaderboardOrders() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 5_000_000, 5_000_000, 50_000, 90_000);
        long weak = createPlayer(0, 0, 200, 5_000_000, 100);
        long strong = createPlayer(0, 0, 400, 5_000_000, 100);

        WorldBossChallengeResult r1 = worldBossService.challenge(weak, scope, "1", now);
        long later = now + WorldBossService.challengeCooldownMs() + 1000;
        WorldBossChallengeResult r2 = worldBossService.challenge(strong, scope, "1", later);

        assertTrue(r1.success());
        assertTrue(r2.success());

        WorldBossDamage weakRow = worldBossService.getDamage(boss.getId(), weak);
        assertNotNull(weakRow);
        assertEquals(r1.damage(), weakRow.getDamage());
        assertEquals(1, weakRow.getHits());
        assertEquals(now, weakRow.getLastHitAt());

        List<WorldBossDamage> board = worldBossService.getLeaderboard(boss.getId(), 10);
        assertEquals(2, board.size());
        long d0 = board.get(0).getDamage();
        long d1 = board.get(1).getDamage();
        assertTrue(d0 >= d1, "排行榜必须按伤害降序");
    }

    @Test
    @DisplayName("台账: 第二次挑战累加而不是覆盖")
    void ledgerAccumulatesAcrossHits() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 50_000_000, 50_000_000, 50_000, 90_000);
        long playerId = createPlayer(0, 0, 200, 5_000_000, 100);

        WorldBossChallengeResult r1 = worldBossService.challenge(playerId, scope, "1", now);
        long later = now + WorldBossService.challengeCooldownMs() + 1000;
        WorldBossChallengeResult r2 = worldBossService.challenge(playerId, scope, "1", later);

        long d1 = r1.damage();
        long d2 = r2.damage();
        WorldBossDamage row = worldBossService.getDamage(boss.getId(), playerId);
        assertEquals(d1 + d2, row.getDamage(), "累计伤害应为两次之和");
        assertEquals(2, row.getHits());
    }

    @Test
    @DisplayName("查询: 找不到的 Boss 返回空而不是抛异常")
    void unknownBossQueryReturnsNull() {
        String scope = newScope();
        long now = System.currentTimeMillis();
        worldBossService.getPool(scope, now);

        assertNull(worldBossService.getBossById(-12345L));
    }

    // ==================== 辅助 ====================

    private static String newScope() {
        return "test-scope-" + SEQ.incrementAndGet();
    }

    private static List<Long> ids(List<WorldBoss> pool) {
        List<Long> out = new ArrayList<>();
        for (WorldBoss boss : pool) {
            out.add(boss.getId());
        }
        return out;
    }

    /** 把生成出来的 Boss 数值改成测试想要的样子，避免断言依赖随机生成结果。 */
    private void overrideBoss(long bossId, int realm, long hp, long maxHp,
                              long stoneBudget, long expBudget) throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE world_bosses SET realm = ?, hp = ?, max_hp = ?, attack = ?, defense = ?, "
                             + "speed = ?, stone_budget = ?, exp_budget = ? WHERE id = ?")) {
            ps.setInt(1, realm);
            ps.setLong(2, hp);
            ps.setLong(3, maxHp);
            ps.setInt(4, 10);
            ps.setInt(5, 0);
            ps.setInt(6, 1);
            ps.setLong(7, stoneBudget);
            ps.setLong(8, expBudget);
            ps.setLong(9, bossId);
            ps.executeUpdate();
        }
    }

    private long createPlayer(int realm, int subRealm, int attack, int maxHp, int speed) throws SQLException {
        int n = SEQ.incrementAndGet();
        String username = "wbuser" + n;
        try (Connection conn = DatabaseManager.getConnection()) {
            long userId;
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
                    "INSERT INTO players (user_id, name, realm, sub_realm, hp, max_hp, mp, max_mp, "
                            + "attack, defense, speed, spirit, experience, last_active_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 500, 500, ?, 0, ?, 20, 0, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, "道友" + n);
                ps.setInt(3, realm);
                ps.setInt(4, subRealm);
                ps.setInt(5, maxHp);
                ps.setInt(6, maxHp);
                ps.setInt(7, attack);
                ps.setInt(8, speed);
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM players WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "角色写入失败");
                    return rs.getLong(1);
                }
            }
        }
    }

    @Test
    @DisplayName("分红: 灵石确实发放到玩家账上")
    void stonesActuallyCredited() throws SQLException {
        String scope = newScope();
        long now = System.currentTimeMillis();
        WorldBoss boss = worldBossService.getPool(scope, now).get(0);
        overrideBoss(boss.getId(), 0, 1000, 1000, 10_000, 10_000);
        long playerId = createPlayer(0, 0, 1_000_000, 1_000_000, 10_000);
        long before = ServiceRegistry.getItemService().getSpiritStoneCount(playerId);

        WorldBossChallengeResult result = worldBossService.challenge(playerId, scope, "1", now);

        assertTrue(result.success(), result.message());
        long gained = ServiceRegistry.getItemService().getSpiritStoneCount(playerId) - before;
        assertEquals(result.stones(), gained,
                "战报里的灵石数必须与实际入账一致");
    }
}
