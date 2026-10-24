package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宗门金库与工资模块的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code SectTreasuryDomain} 原来 406 行里 5 处
 * {@code DatabaseManager.getConnection()}，而声望下限 0 的双方言分支
 * （SQLite {@code MAX(0, ...)} / MySQL {@code GREATEST(0, ...)}）拼在服务层。</p>
 */
class SectTreasuryDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return SectTreasuryDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/SectTreasuryDomain.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/SectTreasuryDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of("salaryIntervalHours", "salarySweepSeconds", "nextSalaryTime",
                "expectedSalary", "sweepSalaries", "paySalary", "expectedSalary(",
                "removeSpiritStones", "addSpiritStones", "getSectMembers");
    }

    @Test
    @DisplayName("声望下限 0 的双方言分支搬进了 DAO，且服务层不再有方言判断")
    void prestigeClampMovedToDao() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("MAX(0, prestige + ?)"), "SQLite 的取大分支不能丢");
        assertTrue(dao.contains("GREATEST(0, prestige + ?)"), "MySQL 的取大分支不能丢");
        assertEquals(1, countOccurrences(dao, "isSqlite()"),
                "两个分支共用一个 isSqlite() 判断：自取连接版直接委托，不再各判一次");
        assertFalse(stripComments(read(servicePath())).contains("isSqlite()"),
                "方言选择不该留在服务层");
    }

    @Test
    @DisplayName("金库扣减仍是条件更新：并发下不会扣成负数、不会发两次工资")
    void conditionalDeductKept() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("AND treasury >= ?"),
                "「余额够不够」与「扣减 + 记时」必须在同一条 UPDATE 里完成，"
                        + "改成先 SELECT 再 UPDATE 会让自动扫描与手动发放各发一次");
        // 自取连接版直接委托给 conn 版，所以条件语句只写一遍
        assertEquals(1, countOccurrences(dao, "AND treasury >= ?"),
                "自取连接版委托给 Connection 版，SQL 只写一遍");
        assertTrue(dao.contains("return ps.executeUpdate() > 0;"),
                "executeUpdate() == 0 必须变成返回值：调用方靠它判断「被并发抢先扣过了」");
    }

    @Test
    @DisplayName("工资计时从「未开启」才开始，这个口径写在 SQL 里")
    void salaryTimingRuleKept() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertTrue(dao.contains("CASE WHEN last_salary_time = 0 AND ? > 0"),
                "last_salary_time = 0 表示「档位刚设定、还没开始计时」，"
                        + "此时才把起点设成现在；否则宗主一设定档位就会被立刻发一次");
        // perMember 绑两次（写入列 + CASE 条件），但绑定代码只写一遍：自取连接版委托给 conn 版
        assertEquals(1, countOccurrences(dao, "ps.setLong(1, perMember);"),
                "写入列的参数绑定只写一遍，少一次说明 SQL 被抄成了两份");
        assertTrue(dao.contains("ps.setLong(2, perMember);"), "CASE 的条件参数也要绑");
        assertTrue(dao.contains("ps.setLong(3, now);"), "起点时间作为参数收下，DAO 不自己取");
    }

    @Test
    @DisplayName("DAO 不自己取时间：结算时刻由调用方给定")
    void daoDoesNotReadClock() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertFalse(dao.contains("System.currentTimeMillis()"),
                "时间戳取值的时机归调用方，DAO 自己取会让一次重试里的时间悄悄变化");
        assertTrue(dao.contains("last_salary_time = ?"),
                "结算时间仍是参数：金库为 0、发不出钱时也要推进周期");
    }

    @Test
    @DisplayName("五处失败都只让服务层降级，DAO 统一抛 RuntimeException")
    void failuresSurfaceAsRuntimeException() throws IOException {
        String dao = stripComments(read(daoPath()));
        assertEquals(5, countOccurrences(dao, "new RuntimeException("),
                "五个自取连接版的重载都要把 SQLException 包成 RuntimeException");
        // 自取连接版必须 catch SQLException 才能包，5 个自取连接版 = 5 个 catch。
        // Connection 版一律 throws SQLException，不该有 catch。
        assertEquals(5, countOccurrences(dao, "catch (SQLException e) {"),
                "五个自取连接版各有一个 catch；Connection 版必须 throws，不该有 catch");
        assertEquals(5, countOccurrences(dao, "throws SQLException"),
                "五个 Connection 版都要把 SQLException 抛给调用方，让事务决定回滚");
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("SectSupport.LOG.warn(\"更新宗门声望失败: \""),
                "声望更新失败只打 warn：声望是附属收益，不该让捐献整体失败");
        assertTrue(service.contains("SectSupport.LOG.warn(\"更新宗门工资结算时间失败: \""),
                "推进工资时间失败只打 warn");
        assertTrue(service.contains("SectSupport.LOG.error(\"设定宗门工资失败: \""),
                "设定工资失败要打 error 并返回失败文案");
        assertTrue(service.contains("SectSupport.LOG.error(\"宗门工资扣减金库失败: \""),
                "扣金库失败要打 error 并返回失败文案");
    }

    @Test
    @DisplayName("捐灵石失败仍会把灵石退回去")
    void donateStillRefundsOnFailure() throws IOException {
        String service = stripComments(read(servicePath()));
        assertTrue(service.contains("itemService.addSpiritStones(playerId, amount)"),
                "扣了灵石却没进金库是最坏结果，必须退回去");
        assertTrue(service.contains("SectSupport.LOG.error(\"宗门金库入账失败，已退回灵石: \""),
                "退回灵石要有 error 日志，否则玩家只会看到失败却不知道灵石回来了");
        assertFalse(service.contains("treasury = treasury + ?"),
                "入账语句本身不该再出现在服务层");
        assertFalse(service.contains("UPDATE sects SET"), "服务层不该再写任何 sects 表 SQL");
    }
}
