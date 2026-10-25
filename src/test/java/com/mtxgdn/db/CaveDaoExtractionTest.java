package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洞府域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code CaveService} 原来 3 处
 * {@code DatabaseManager.getConnection()}，另有 4 处写在
 * {@code runTransaction} lambda 里的 SQL。</p>
 *
 * <p>这个域最要紧的一条是「开辟洞府不能在事务内回读刚插入的行」——
 * 那是个修过的真 bug：事务未提交时另一条连接看不到该行，于是返回 null
 * 报"开辟失败"，但插入其实已经提交。</p>
 */
class CaveDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return CaveDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/CaveService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/CaveDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 等级与加成的换算公式留在服务层（业务规则）
                "Cave.getMaxSpiritEnergyForLevel(1)",
                "Cave.getCultivationBonusForLevel(newLevel)",
                "Cave.getStorageBonusForLevel(newLevel)",
                "Cave.CREATE_COST_SPIRIT_STONES",
                "Cave.MIN_REALM_CREATE",
                "Cave.MAX_LEVEL",
                "Cave.getLevelUpCost(cave.getLevel())",
                // 收取冷却与阵法加成
                "long collectInterval = 5 * 60 * 1000L;",
                "getTotalSpiritEnergyBoost(playerId)",
                "spiritEnergy * (100 + formationBoost) / 100",
                // 事务编排
                "insertCave(conn, playerId, name,",
                "lockSpiritEnergy(conn, cave.getId())",
                "resetSpiritEnergy(conn, cave.getId(), now)",
                // 定时任务的模块门控
                "isEnabledOrUnknown(\"cave\")",
                "DatabaseManager.isReady()");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "CaveService", "PlayerService", "ItemService", "ServiceRegistry",
                "FormationService", "I18n", "GameConfigLoader", "BuiltInPluginRegistry",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("开辟洞府只返回 id，不在事务内回读整行")
    void insertReturnsIdNotRow() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public long insertCave(Connection conn, long playerId, String name,");
        assertTrue(body.contains("Statement.RETURN_GENERATED_KEYS"),
                "只拿自增键");
        assertTrue(body.contains("return keys.next() ? keys.getLong(1) : 0L"),
                "拿不到返回 0（原实现返回 null）：不是异常，调用方据此报「开辟洞府失败」");
        assertFalse(body.contains("SELECT * FROM caves"),
                "事务内另一条连接看不到未提交的插入，回读会得到 null 并让调用方误报失败");
        assertTrue(body.contains("+ \"cultivation_bonus, storage_bonus, last_collect_time) "
                        + "VALUES (?, ?, 1, 0, ?, ?, ?, ?)\""),
                "level 写死 1、spirit_energy 写死 0：新洞府总是 1 级、空灵能");
    }

    @Test
    @DisplayName("服务层在事务提交之后才回读整行")
    void serviceReadsRowAfterCommit() throws IOException {
        String service = stripComments(read(servicePath()));
        String body = bodyOf(service, "public Map<String, Object> createCave(long playerId, String caveName)");
        int txEnd = body.indexOf("});");
        int readBack = body.indexOf("getCaveById(caveId)");
        assertTrue(txEnd >= 0 && readBack > txEnd,
                "先提交事务、再查整行：原注释写明这是修过的 bug —— "
                        + "事务内用另一条连接查刚插入的行会看不到它");
        assertTrue(body.contains("caveId != null && caveId > 0 ? getCaveById(caveId) : null"),
                "id 为 0 与 null 都当失败");
    }

    @Test
    @DisplayName("收取灵气先锁行再读，且锁从句由 dialect 给")
    void collectLocksRowFirst() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public SpiritEnergyRow lockSpiritEnergy(Connection conn, long caveId)");
        assertTrue(body.contains("SELECT spirit_energy, last_collect_time FROM caves WHERE id = ? "),
                "只取两列");
        assertTrue(body.contains("DatabaseManager.getDialect().getForUpdateClause()"),
                "FOR UPDATE 不能少：没有这把锁，两个并发收取会读到同一份灵能、各发一次灵石");
        assertTrue(body.contains("SpiritEnergyRow row = new SpiritEnergyRow();"),
                "先给全 0");
        assertTrue(body.contains("if (rs.next()) {"),
                "读不到行时两列都是 0：调用方随后看到 lastCollectTime == 0，"
                        + "跳过冷却判断直接走「灵能不足」分支");
    }

    @Test
    @DisplayName("清零与写收取时间是同一条 UPDATE")
    void resetIsSingleStatement() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void resetSpiritEnergy(Connection conn, long caveId, long now)");
        assertTrue(body.contains("UPDATE caves SET spirit_energy = 0, last_collect_time = ? WHERE id = ?"),
                "两个字段必须同一条 UPDATE：拆开会留下「灵能已清零但冷却没开始」的窗口，"
                        + "玩家可以立刻再收一次");
        assertEquals(1, countOccurrences(body, "executeUpdate()"),
                "只有一条语句");
    }

    @Test
    @DisplayName("灵气自增的两种方言")
    void incrementHasTwoDialects() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("\"UPDATE caves SET spirit_energy = MIN(max_spirit_energy, "
                        + "spirit_energy + 10)\""),
                "SQLite 没有 LEAST，用 MIN");
        assertTrue(code.contains("\"UPDATE caves SET spirit_energy = LEAST(max_spirit_energy, "
                        + "spirit_energy + 10)\""),
                "MySQL 用 LEAST");
        assertTrue(code.contains("DatabaseManager.isSqlite()"),
                "方言分支留在 DAO：它是纯粹的方言差异，不是业务判断");

        // 全表一条 UPDATE，不带 player_id
        String body = bodyOf(code, "public void incrementSpiritEnergy(Connection conn)");
        assertFalse(body.contains("player_id"),
                "全表一条 UPDATE：它服务所有玩家，每个 tick 一次。"
                        + "这是刻意的低频全表写（配合 10 秒周期），不是遗漏了 WHERE");
        assertTrue(body.contains("+ 10"), "+10 是字面量：改了要连着调度周期一起改");
    }

    @Test
    @DisplayName("升级同时更新等级、上限、两个加成")
    void levelUpUpdatesAllFourColumns() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void updateCaveLevel(Connection conn, long caveId, int newLevel,");
        assertTrue(body.contains("UPDATE caves SET level = ?, max_spirit_energy = ?, "),
                "等级与上限");
        assertTrue(body.contains("+ \"cultivation_bonus = ?, storage_bonus = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?\""),
                "两个加成 + updated_at（数据库侧算的，不是绑定参数）");
        assertTrue(body.contains("ps.setLong(5, caveId)"), "第五个参数是 id");
    }

    @Test
    @DisplayName("条件扣减灵气：WHERE spirit_energy >= ?")
    void consumeIsConditional() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public boolean consumeSpiritEnergy(Connection conn, long caveId, long auraCost)");
        assertTrue(body.contains("UPDATE caves SET spirit_energy = spirit_energy - ? "),
                "减法");
        assertTrue(body.contains("+ \"WHERE id = ? AND spirit_energy >= ?\""),
                "条件扣减：灵气不足时命中 0 行，整个事务失败。"
                        + "SQLite 没有 GREATEST(0, ...)，也不能先减再回查 —— 那会扣成负数");
        assertTrue(body.contains("return ps.executeUpdate() > 0"), "命中行数即判据");
        assertTrue(body.contains("ps.setLong(3, auraCost)"),
                "auraCost 绑定两次（减数与判据各一次）");
    }

    @Test
    @DisplayName("caves 表只归 CaveDAO（EconomyDAO 不再碰它）")
    void cavesTableHasOneOwner() throws IOException {
        String economy = stripComments(read("src/main/java/com/mtxgdn/db/EconomyDAO.java"));
        assertFalse(economy.contains("UPDATE caves"),
                "caves 表原来两个所有者（EconomyDAO 的 consumeCaveAura + CaveDAO），"
                        + "现在只归 CaveDAO");
        assertFalse(economy.contains("consumeCaveAura"),
                "方法已改名为 CaveDAO.consumeSpiritEnergy");
        String cave = stripComments(read(daoPath()));
        assertTrue(cave.contains("public boolean consumeSpiritEnergy("),
                "条件扣减在 CaveDAO");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(2, countOccurrences(code, "throw new RuntimeException(\"查询洞府失败\", e)"),
                "findCaveByPlayerId 与 findCaveById 各一次");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"更新洞府灵气失败\", e)"),
                "灵气自增");
    }

    @Test
    @DisplayName("DAO 不依赖服务层，也不读业务常量")
    void daoIsLayerClean() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("import com.mtxgdn.game.service"),
                "DAO 只管 caves 表：等级换算、阵法加成、冷却时长都在服务层");
        assertFalse(code.contains("ServiceRegistry"), "不取任何服务");
        assertFalse(code.contains("Cave.CREATE_COST"), "业务常量留在服务层");
        assertFalse(code.contains("I18n"), "文案留在服务层");
    }

    @Test
    @DisplayName("DAO 不自己取「现在」")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("System.currentTimeMillis()"),
                "「现在」全部由调用方给（lastCollectTime / resetSpiritEnergy 的 now）");
        assertEquals(1, countOccurrences(code, "updated_at = CURRENT_TIMESTAMP"),
                "只有升级那条 UPDATE 用数据库侧时间");
    }
}
