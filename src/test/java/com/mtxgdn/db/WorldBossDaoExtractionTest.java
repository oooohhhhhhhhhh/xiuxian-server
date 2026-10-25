package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界 Boss 域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code WorldBossService} 原来 6 处
 * {@code DatabaseManager.getConnection()}，另有 10 处写在
 * {@code runTransaction} lambda 里的 SQL。</p>
 *
 * <p>这个域的三条不变量直接决定了 SQL 的形状：全服共享同一份血量
 * （所以要 FOR UPDATE 锁行）、奖励按伤害分红（所以账本必须累加）、
 * 挑战冷却靠条件更新抢占（所以抢占是三段式）。</p>
 */
class WorldBossDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return WorldBossDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/WorldBossService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/WorldBossDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 分红公式与熔断必须留在服务层
                "row.stoneBudget * applied / row.maxHp",
                "row.expBudget * applied / row.maxHp",
                "(long) (row.expBudget * killBonusRatio())",
                "applyOverflowBreaker(stones)",
                "applyOverflowBreaker(exp)",
                // 配置读取
                "AppConfig.getInt(\"world_boss.hp_multiplier\"",
                "AppConfig.getInt(\"world_boss.max_single_reward\"",
                "AppConfig.get(\"world_boss.kill_bonus_ratio\")",
                // 境界分档与 Boss 生成
                "realmBand(slot, count, maxRealm)",
                "Monster.createBossForRealm(realm, random)",
                // 结算编排
                "lockBossForSettlement(conn, bossId)",
                "markBossKilled(conn, bossId, now, playerId)",
                "updateBossHp(conn, bossId, newHp)");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "PlayerService", "ItemService", "ServiceRegistry", "CombatService",
                "AppConfig", "I18n", "Monster", "PveCombatResult",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("结算锁行：FOR UPDATE 不能少，读不到抛 IllegalStateException")
    void settlementLockUsesForUpdate() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public BossSettlementRow lockBossForSettlement(Connection conn, long bossId)");
        assertTrue(body.contains("SELECT hp, max_hp, stone_budget, exp_budget FROM world_bosses WHERE id = ? "),
                "只取结算需要的四列");
        assertTrue(body.contains("DatabaseManager.getDialect().getForUpdateClause()"),
                "FOR UPDATE 不能少：否则两个并发挑战会读到同一个旧血量，"
                        + "各扣各的，最后血量比实际伤害之和还高（丢更新），"
                        + "而且两个人都以为自己补了最后一刀");
        assertTrue(body.contains("throw new IllegalStateException(\"世界Boss不存在: \" + bossId)"),
                "读不到行是调用方的编程错误，不是可以继续的运行时状况");
        assertTrue(body.contains("row.hp = rs.getLong(1)"),
                "位置下标而不是列名：这条 SELECT 的列序就是 hp / max_hp / stone_budget / exp_budget");
        assertTrue(body.contains("row.expBudget = rs.getLong(4)"), "第四列");
    }

    @Test
    @DisplayName("扣血与标记击杀是两条不同的 UPDATE")
    void twoHpUpdatesDiffer() throws IOException {
        String code = stripComments(read(daoPath()));
        String hp = bodyOf(code, "public void updateBossHp(Connection conn, long bossId, long newHp)");
        assertTrue(hp.contains("UPDATE world_bosses SET hp = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"),
                "未击杀只写 hp");
        assertFalse(hp.contains("killed_at"), "不碰击杀字段");

        String killed = bodyOf(code,
                "public void markBossKilled(Connection conn, long bossId, long now, long killerPlayerId)");
        assertTrue(killed.contains("UPDATE world_bosses SET hp = 0, killed_at = ?, killer_player_id = ?, "),
                "击杀把 killed_at 与 killer_player_id 一起写，hp 硬写 0");
        assertTrue(killed.contains("+ \"updated_at = CURRENT_TIMESTAMP WHERE id = ?\""),
                "两条 UPDATE 都用 CURRENT_TIMESTAMP，不合并：合并需要"
                        + "「把 killer 传 NULL」的分支，反而更容易错");
        assertTrue(killed.contains("ps.setLong(1, now)"),
                "killed_at 用 Java 侧的 now：要能注入测试时间，"
                        + "与 updated_at 的数据库侧时间语义不同");
    }

    @Test
    @DisplayName("账本写入先 UPDATE 再 INSERT，且是累加")
    void recordDamageIsAccumulatingUpsert() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public void recordDamage(Connection conn, long bossId, long playerId, long damage, long now)");
        assertTrue(body.contains("UPDATE world_boss_damage SET damage = damage + ?, hits = hits + 1, last_hit_at = ? "),
                "累加不能改成覆盖写：奖励按「预算 × 本次伤害 / 最大血量」分红，"
                        + "账本里的 damage 是排行榜与后续分红的依据");
        assertTrue(body.contains("if (ps.executeUpdate() > 0) {"),
                "先试 UPDATE，命中行数就是判据 —— 不是原子的 SELECT-then-UPDATE");
        assertTrue(body.contains("INSERT INTO world_boss_damage (boss_id, player_id, damage, hits, last_hit_at) "),
                "命中 0 行才 INSERT");
        assertTrue(body.contains("+ \"VALUES (?, ?, ?, 1, ?)\""),
                "INSERT 分支的 hits 写的是字面量 1：这是第一次命中，不是 0");
        assertFalse(body.contains("ON CONFLICT"), "避开 SQLite 的 upsert 方言");
        assertFalse(body.contains("ON DUPLICATE KEY"), "避开 MySQL 的 upsert 方言");
    }

    @Test
    @DisplayName("抢占是三段式，且 cutoff 夹到不低于 now - cooldown")
    void claimSlotIsThreeStage() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code,
                "public boolean claimChallengeSlot(Connection conn, long bossId, long playerId,");
        assertTrue(body.contains("long cutoff = now - Math.max(0, cooldownMs)"),
                "冷却配成负数时不能把 cutoff 推到未来（那会让所有人永远不能打）");
        assertTrue(body.contains("UPDATE world_boss_damage SET last_hit_at = ? "),
                "第一段：条件更新抢占");
        assertTrue(body.contains("+ \"WHERE boss_id = ? AND player_id = ? AND last_hit_at <= ?\""),
                "抢占条件就是冷却判据");
        assertTrue(body.contains("if (hasDamageRow(conn, bossId, playerId)) {"),
                "第二段：有账本行但没抢到 = 冷却未过");
        assertTrue(read(daoPath()).contains("return false; // 记录存在但冷却未过"), "拒");
        assertTrue(body.contains("+ \"VALUES (?, ?, 0, 0, ?)\""),
                "第三段：没有账本行就插一条零伤害零命中的");
        assertTrue(body.contains("} catch (SQLException duplicate) {"),
                "INSERT 撞唯一键说明并发对手刚插进去");
        assertEquals(2, countOccurrences(body, "tryClaimSlot(conn, update, bossId, playerId, now, cutoff)"),
                "撞键后回到条件 UPDATE 再判一次（首次 + 重试各一次）");
    }

    @Test
    @DisplayName("抢占的内部助手刻意是 private")
    void claimHelpersArePrivate() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("private boolean tryClaimSlot(Connection conn, String update,"),
                "它是 claimChallengeSlot 的一段，不是独立意图；"
                        + "公开它只会让人绕开三段式的前置判断");
        assertTrue(code.contains("private boolean hasDamageRow(Connection conn, long bossId, long playerId)"),
                "只服务于抢占的中间判断，对外读账本走 findDamage");
        assertTrue(code.contains("SELECT 1 FROM world_boss_damage WHERE boss_id = ? AND player_id = ?"),
                "SELECT 1 而不是 SELECT *：只判存在");
    }

    @Test
    @DisplayName("重建池子先删台账再删池")
    void rebuildDeletesLedgerBeforePool() throws IOException {
        String code = stripComments(read(daoPath()));
        String ledger = bodyOf(code, "public void deleteDamageForScope(Connection conn, String scope)");
        assertTrue(ledger.contains("DELETE FROM world_boss_damage WHERE boss_id IN "),
                "台账按子查询删");
        assertTrue(ledger.contains("+ \"(SELECT id FROM world_bosses WHERE scope = ?)\""),
                "条件是 boss_id IN (SELECT id ...)：顺序不能反 —— "
                        + "先删池会让子查询查不到 id，台账就永远删不掉，"
                        + "随着每次重建无限累积");

        String pool = bodyOf(code, "public void deleteBossesForScope(Connection conn, String scope)");
        assertTrue(pool.contains("DELETE FROM world_bosses WHERE scope = ?"), "按 scope 删池");

        // 服务层的调用顺序必须与注释一致
        String service = stripComments(read(servicePath()));
        int deleteLedger = service.indexOf("deleteDamageForScope(conn, scope)");
        int deletePool = service.indexOf("deleteBossesForScope(conn, scope)");
        assertTrue(deleteLedger >= 0 && deletePool >= 0, "两处都要被调用");
        assertTrue(deleteLedger < deletePool, "台账必须先删");
    }

    @Test
    @DisplayName("池子与 Boss 查询都是 LEFT JOIN players")
    void bossQueriesUseLeftJoin() throws IOException {
        String code = stripComments(read(daoPath()));
        String pool = bodyOf(code, "public List<WorldBoss> findPool(Connection conn, String scope)");
        assertTrue(pool.contains("SELECT b.*, p.name AS killer_name FROM world_bosses b "),
                "b.* 保留通配：映射靠列名而不是位置");
        assertTrue(pool.contains("+ \"LEFT JOIN players p ON b.killer_player_id = p.id \""),
                "LEFT JOIN 不能改 INNER JOIN：没被击杀的 Boss killer_player_id 是 0 或 NULL，"
                        + "INNER JOIN 会把它们整行滤掉，池子看起来是空的");
        assertTrue(pool.contains("+ \"WHERE b.scope = ? ORDER BY b.slot\""),
                "按 slot 排序：序号是玩家看到的「第几只」，顺序必须稳定");

        String byId = bodyOf(code, "public WorldBoss findBossById(Connection conn, long bossId)");
        assertTrue(byId.contains("LEFT JOIN players p ON b.killer_player_id = p.id WHERE b.id = ?"),
                "单只查询同样 LEFT JOIN");
        assertTrue(byId.contains("return rs.next() ? mapBoss(rs) : null"), "读不到返回 null");
    }

    @Test
    @DisplayName("排行榜：damage > 0、双排序键、LIMIT 夹到至少 1")
    void leaderboardShape() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public List<WorldBossDamage> findLeaderboard(Connection conn, long bossId, int limit)");
        assertTrue(body.contains("WHERE d.boss_id = ? AND d.damage > 0 "),
                "带 damage > 0：只抢占过冷却、一次都没打中的记录不该上榜");
        assertTrue(body.contains("ORDER BY d.damage DESC, d.player_id ASC LIMIT ?"),
                "第二排序键 player_id ASC 是必要的：伤害相同时没有它顺序不稳定，"
                        + "分页会漏人或重复");
        assertTrue(body.contains("ps.setInt(2, Math.max(1, limit))"),
                "LIMIT 是绑定参数且夹到至少 1：LIMIT 0 在部分数据库上返回全部行，不是零行");
    }

    @Test
    @DisplayName("killer_name / player_name 用 try/catch 兜")
    void optionalJoinColumnsAreGuarded() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(2, countOccurrences(read(daoPath()), "// 没有 JOIN players 的查询里没有这一列"),
                "两处映射都要兜：读不到就当没有，而不是让整个映射失败");
        assertTrue(code.contains("boss.setKillerName(rs.getString(\"killer_name\"))"), "Boss 侧");
        assertTrue(code.contains("d.setPlayerName(rs.getString(\"player_name\"))"), "账本侧");
    }

    @Test
    @DisplayName("境界上限：0 参方法，空表返回 0，下限由调用方夹")
    void realmCeilingShape() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public int findMaxPlayerRealm()");
        assertTrue(body.contains("SELECT MAX(realm) FROM players"), "全服最高境界");
        assertTrue(body.contains("return rs.next() ? rs.getInt(1) : 0"),
                "空表上 MAX 是 NULL，getInt 得到 0；境界下限由调用方夹（Math.max(3, ...)）");
        assertFalse(body.contains("Math.max(3"), "DAO 不夹下限：那是业务决定");

        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("return Math.max(3, WORLD_BOSS_DAO.findMaxPlayerRealm());"),
                "服务层夹到至少 3");
        assertTrue(service.contains("LOG.warn(\"读取全服最高境界失败，世界Boss按默认境界生成: \" + e)"),
                "失败降级到 3，文案逐字保留");
    }

    @Test
    @DisplayName("insertBoss 的 killed_at 是字面量 0")
    void insertBossWritesLiteralZero() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void insertBoss(Connection conn, WorldBoss boss)");
        assertTrue(body.contains("+ \"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)\""),
                "killed_at 写的是字面量 0 而不是占位符：表里用 0 表示「未被击杀」"
                        + "（不是 NULL），与 WorldBoss.isDead() 对应");
        assertTrue(body.contains("(scope, slot, name, description, realm, hp, max_hp, "), "十四列");
        assertTrue(body.contains("+ \"attack, defense, speed, stone_budget, exp_budget, expires_at, killed_at) \""),
                "列清单第二段");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询世界Boss失败\", e)"),
                "findPool 与 findBossById 各一次");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"读取全服最高境界失败\", e)"),
                "境界上限");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询世界Boss伤害记录失败\", e)"),
                "单条账本");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询世界Boss排行榜失败\", e)"),
                "排行榜");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"抢占世界Boss挑战冷却失败\", e)"),
                "抢占冷却");
    }

    @Test
    @DisplayName("DAO 不依赖服务层，也不读配置")
    void daoIsLayerClean() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("import com.mtxgdn.game.service"),
                "DAO 只管表：分红公式、熔断、境界分档都在服务层");
        assertFalse(code.contains("AppConfig"),
                "配置读取留在服务层：DAO 里的阈值会让测试没法注入");
        assertFalse(code.contains("I18n"), "文案留在服务层");
    }

    @Test
    @DisplayName("DAO 不自己取「现在」")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("System.currentTimeMillis()"),
                "「现在」全部由调用方给：结算要能注入测试时间");
        assertEquals(2, countOccurrences(code, "updated_at = CURRENT_TIMESTAMP"),
                "只有 updated_at 是数据库侧算的（两条 UPDATE），"
                        + "killed_at 用 Java 侧的 now");
    }
}
