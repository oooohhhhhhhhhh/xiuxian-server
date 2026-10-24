package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门战模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectWarDomain} 原来 222 行里 3 处
 * {@code DatabaseManager.getConnection()}，输家声望下限 0 的双方言分支
 * （SQLite {@code MAX(0, ...)} / MySQL {@code GREATEST(0, ...)}）
 * 拼在服务层，而"扣声望"这一步原本就在 {@code runTransaction} 的 lambda 体里。</p>
 */
class SectWarDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectWarDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectWarDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectWarDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("declareWar", "SECT_WAR_PLUGIN", "BuiltInPluginRegistry", "isEnabledOrUnknown",
                "selectFighters", "simulateDuel", "warCooldownMs", "attackerWins", "defenderWins",
                "battleLog", "logText", "WAR_WIN_PRESTIGE", "DECLARE_WAR_PRESTIGE_COST");
    }

    @Test
    @DisplayName("宣战仍是「扣灵石 + 扣声望」一个事务，声望那步用事务连接")
    void declareWarKeepsOneTransaction() throws IOException {
        String service = stripComments(read(servicePath()));
        assertEquals(1, countOccurrences(service, "DatabaseManager.runTransaction("),
                "宣战只该有一个事务：扣灵石与扣声望必须原子");
        String body = bodyOf(service, "Map<String, Object> declareWar(long attackerPlayerId, long defenderSectId)");
        int txStart = body.indexOf("DatabaseManager.runTransaction(");
        assertTrue(txStart >= 0, "宣战必须是事务");
        String txBody = body.substring(txStart);
        assertTrue(txBody.contains("itemService.removeSpiritStones(conn, attackerPlayerId, Sect.DECLARE_WAR_SPIRIT_STONE_COST)"),
                "扣灵石仍走 itemService 的 conn 重载");
        assertTrue(txBody.contains("WAR_DAO.deductPrestige(conn,"),
                "扣声望必须改用事务连接：自取连接会撞 SQLITE_BUSY_SNAPSHOT 且回滚不了");
        assertFalse(txBody.contains("DatabaseManager.getConnection()"),
                "事务块内绝不能再开连接");
        // 顺序不能反：先扣灵石再扣声望
        assertTrue(txBody.indexOf("itemService.removeSpiritStones(conn")
                        < txBody.indexOf("WAR_DAO.deductPrestige(conn"),
                "先扣灵石再扣声望");
    }

    @Test
    @DisplayName("输家声望下限 0 的双方言分支搬进了 DAO，服务层不再有方言判断")
    void loserPrestigeClampMovedToDao() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("MAX(0, prestige - ?)"), "SQLite 的取大分支不能丢");
        assertTrue(dao.contains("GREATEST(0, prestige - ?)"), "MySQL 的取大分支不能丢");
        assertEquals(1, countOccurrences(dao, "isSqlite()"),
                "结算只有一次方言判断：赢家加与输家扣共用同一条连接");
        assertFalse(stripComments(read(servicePath())).contains("isSqlite()"),
                "方言选择不该留在服务层");
        // 赢家那一侧保持原样：没有下限保护（奖励恒为正）
        assertTrue(dao.contains("UPDATE sects SET prestige = prestige + ? WHERE id = ?"),
                "赢家加声望仍是朴素累加，不要顺手套上 MAX(0, ...)");
    }

    @Test
    @DisplayName("声望结算的两条 UPDATE 共享一条连接：不会「加成功、扣失败」")
    void settlementSharesOneConnection() throws IOException {
        String dao = stripComments(read(daoPath()));
        // 只数 conn 版：自取连接版委托给它，SQL 与连接只在那里出现一次
        String body = bodyOf(dao, "public void settleWarPrestige(Connection conn,");
        assertEquals(2, countOccurrences(body, "conn.prepareStatement("),
                "赢家加、输家扣各一条 UPDATE，且共用调用方的那条连接");
        assertEquals(0, countOccurrences(body, "DatabaseManager.getConnection()"),
                "conn 版绝不自己开连接");
        assertTrue(body.contains("UPDATE sects SET prestige = prestige + ? WHERE id = ?"),
                "赢家加声望仍是朴素累加，不要顺手套上 MAX(0, ...)");
        assertTrue(body.contains("MAX(0, prestige - ?)") || body.contains("prestigeClamp()"),
                "输家那一侧走下限 0 的双方言分支");
    }

    @Test
    @DisplayName("战报写入刻意独立于事务，且异常直接往外抛")
    void warLogStaysOutsideTransaction() throws IOException {
        String dao = stripComments(read(daoPath()));
        String body = bodyOf(dao, "public void saveWarLog(Connection conn,");
        // INSERT 分两行拼接，逐段断言而不是断言拼接结果
        assertTrue(body.contains("INSERT INTO sect_wars (attacker_sect_id, defender_sect_id, winner_sect_id, "),
                "战报的列清单开头不能变");
        assertTrue(body.contains("attacker_wins, defender_wins, prestige_stake, battle_log) VALUES (?, ?, ?, ?, ?, ?, ?)"),
                "战报的列清单结尾与 7 个占位符不能变：少一个占位符运行期才炸");
        assertEquals(0, countOccurrences(body, "DatabaseManager.getConnection()"),
                "conn 版只跑在调用方的连接上");
        String service = stripComments(read(servicePath()));
        // 结算与战报都不得落进任何事务块
        int settleIdx = service.indexOf("WAR_DAO.settleWarPrestige(");
        int logIdx = service.indexOf("WAR_DAO.saveWarLog(");
        assertTrue(settleIdx > 0 && logIdx > settleIdx,
                "先结算声望、再写战报，两步都必须在事务块之外");
        String afterTx = service.substring(service.lastIndexOf("DatabaseManager.runTransaction("));
        assertTrue(afterTx.contains("WAR_DAO.settleWarPrestige(") && afterTx.contains("WAR_DAO.saveWarLog("),
                "结算与战报跟在事务后面，是两条独立短连接");
        assertEquals(1, countOccurrences(service, "DatabaseManager.runTransaction("),
                "仍然只有宣战那一个事务：结算与战报不许被并进去");
    }

    @Test
    @DisplayName("冷却是读 MAX(created_at)，不是最近一条战报")
    void cooldownReadsMaxCreatedAt() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("SELECT MAX(created_at) FROM sect_wars WHERE defender_sect_id = ?"),
                "读的是所有战报里最新的创建时间：只要插过一行就进入冷却");
        assertTrue(dao.contains("return rs.getTimestamp(1);"),
                "DAO 直接交出 Timestamp，「还剩多少小时、是否拒绝」的判断留在服务层");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("warCooldownMs"), "24 小时冷却的换算留在服务层");
        assertTrue(service.contains("Math.max(1, remaining / 3600000)"),
                "不足 1 小时按 1 小时展示的口径不能丢");
    }

    @Test
    @DisplayName("DAO 不认识战斗与宗门域的业务对象")
    void daoKnowsNoBusinessRules() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("PlayerService"), "DAO 不该依赖玩家服务");
        assertFalse(dao.contains("ItemService"), "DAO 不该依赖物品服务");
        assertFalse(dao.contains("SectDuelDomain"), "对拼规则是业务，留在服务层");
        assertFalse(dao.contains("I18n"), "DAO 不该做国际化");
        assertFalse(dao.contains("SectWarDomain"), "DAO 不该反向依赖域类");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("duelDomain.simulateDuel"), "逐场对拼仍留在服务层");
        assertTrue(service.contains("duelDomain.selectFighters"), "选出战成员仍留在服务层");
    }
}
