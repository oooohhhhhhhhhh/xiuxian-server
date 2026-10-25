package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每日签到与日常域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code DailyService} 原来 8 处
 * {@code DatabaseManager.getConnection()}。这个域是"幂等判定 + 奖励发放"，
 * 任何一处 SQL 语义变化都会造成<b>重复领奖</b>或<b>漏发</b>。</p>
 *
 * <p>最容易踩的三个坑：把"今天是否已签到"的字符串比较改成日期类型比较、
 * 把 {@code total_active_days} 的累加式写成覆盖式、把跨日复位的 WHERE
 * 条件"顺手简化"。</p>
 */
class DailyDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return DailyDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/DailyService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/DailyDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // 「今天是否已签到」的判定在服务层：字符串相等
                "lastMorningCultivation",
                "LocalDate.now().toString()",
                // 连签天数：昨天签过就 +1
                "consecutive",
                // 共振里程碑的门槛与六维加成
                "totalDays == 7",
                "totalDays == 30",
                "awardResonance(",
                // 国际化与奖励计算
                "I18n.t(",
                "playerService.addExperience(",
                "itemService.addSpiritStones(",
                // 机缘的达标判定（count < target && !rewarded）
                "explorationCount < 3",
                "battleCount < 1");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                "I18n", "PlayerService", "ItemService", "GameConfigLoader",
                "DailyService", "ServiceRegistry",
                "LocalDate.now()", "System.currentTimeMillis()",
                "setAutoCommit(", "runTransaction(", "commit(", "rollback(");
    }

    @Test
    @DisplayName("六个意图：查询类成对，事务内写操作只有 conn 版")
    void overloadShape() throws IOException {
        String code = stripComments(read(daoPath()));
        // 三个自取连接版各委托一次 → 意图名出现 3 次
        for (String intent : new String[]{"getDaily", "insertDaily", "resetDaily",
                "incrementDailyCount", "markFortuneClaimed"}) {
            assertEquals(3, countOccurrences(code, intent + "("),
                    intent + " 应有自取连接版与 Connection 版两个重载，"
                            + "且自取连接版必须是整体委托（否则 SQL 会被复制两份）");
        }
        // recordMorningCultivation 只在 runTransaction 里被调用 → 只有 conn 版
        assertEquals(1, countOccurrences(code, "recordMorningCultivation("),
                "签到 upsert 与加经验、加灵石必须同生共死，刻意不给自取连接版");

        for (String sig : new String[]{
                "public PlayerDaily getDaily(long playerId)",
                "public void insertDaily(long playerId)",
                "public void resetDaily(long playerId, String today)",
                "public void incrementDailyCount(long playerId, String countColumn)",
                "public void markFortuneClaimed(long playerId, String column)"}) {
            String body = bodyOf(code, sig);
            assertTrue(body.contains("DatabaseManager.getConnection()"), sig + " 没有自己开连接");
            assertFalse(body.contains("SELECT ") || body.contains("INSERT INTO")
                            || body.contains("UPDATE "),
                    sig + " 自己写了 SQL：同一条语句复制两份，改一处漏一处");
        }
    }

    @Test
    @DisplayName("签到 upsert 的 total_active_days 是累加不是覆盖")
    void totalActiveDaysAccumulates() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "private static String morningCultivationSql()");
        assertTrue(body.contains("VALUES (?, ?, ?, 1)"),
                "首次签到的 total_active_days 初值是字面量 1");
        assertTrue(body.contains("total_active_days = total_active_days + 1"),
                "累加式：写成 excluded.total_active_days 会把天数覆盖成 1，"
                        + "灵根共鸣的 7 天 / 30 天里程碑就永远触发不了");
        assertTrue(body.contains("ON CONFLICT(player_id) DO UPDATE SET"), "SQLite 分支");
        assertTrue(body.contains("ON DUPLICATE KEY UPDATE"), "MySQL 分支");
        assertTrue(body.contains("excluded.last_morning_cultivation"),
                "SQLite 用 excluded. 前缀");
        assertTrue(body.contains("VALUES(last_morning_cultivation)"),
                "MySQL 用 VALUES() 前缀，两者不能互换");
        assertTrue(body.contains("excluded.consecutive_days"), "连签天数同理");
        assertTrue(body.contains("VALUES(consecutive_days)"), "同上");
    }

    @Test
    @DisplayName("跨日复位的 WHERE 一个字符都不能少")
    void resetWhereClauseIsComplete() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void resetDaily(Connection conn, long playerId, String today)");
        assertTrue(body.contains("WHERE player_id = ? AND (last_daily_reset IS NULL OR last_daily_reset <> ?)"),
                "少了 IS NULL 分支，老库里该列为 NULL 的行永远复位不了；"
                        + "少了 <> ?，每次调用都会把四个计数和奖励标记再归零一次，"
                        + "玩家当天的进度会凭空消失");
        assertTrue(body.contains("ps.setString(1, today)"), "第 1 个参数是 SET 里的新日期");
        assertTrue(body.contains("ps.setLong(2, playerId)"), "第 2 个参数是玩家 id");
        assertTrue(body.contains("ps.setString(3, today)"), "第 3 个参数是 WHERE 里的比较值");
        // 复位的列清单：四个计数 + PVP + 四个奖励标记，两个共振标记不在内
        for (String column : new String[]{
                "exploration_count = 0", "battle_count = 0", "secret_realm_count = 0",
                "skill_learn_count = 0", "pvp_count = 0",
                "exploration_rewarded = 0", "battle_rewarded = 0",
                "secret_realm_rewarded = 0", "skill_learn_rewarded = 0"}) {
            assertTrue(body.contains(column), column + " 要复位");
        }
        assertFalse(body.contains("resonance7_awarded"),
                "resonance7_awarded 是终身一次性标记，不在复位范围内");
        assertFalse(body.contains("resonance30_awarded"), "同上");
        assertTrue(body.contains("ps.executeUpdate();"),
                "返回值被丢掉是原实现如此：复位 0 行只意味着「今天已经复位过」，不是错误");
    }

    @Test
    @DisplayName("计数自增把跨日归 1 和 +1 压在同一条语句里")
    void countIncrementCombinesResetAndIncrement() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "private static String dailyCountIncrementSql(String countColumn)");
        assertTrue(body.contains("VALUES (?, 1, CURRENT_DATE)"),
                "建行时的计数初值是字面量 1");
        assertTrue(body.contains("= " + "\" + countColumn + \" = \" + countColumn + \" + 1,")
                || body.contains("= \" + countColumn + \" + 1,"),
                "同日则累加 1");
        assertTrue(body.contains("last_daily_reset = CURRENT_DATE"),
                "跨日复位由数据库侧取 CURRENT_DATE，不是绑定参数 —— 原实现如此，"
                        + "改了会让 SQLite 与 MySQL 的 CURRENT_DATE 语义漂移");
        assertTrue(body.contains("ON CONFLICT(player_id) DO UPDATE SET"), "SQLite 分支");
        assertTrue(body.contains("ON DUPLICATE KEY UPDATE"), "MySQL 分支");
    }

    @Test
    @DisplayName("列名是裸拼接：只接受受信任的字面量")
    void columnNameIsRawConcatenation() throws IOException {
        String code = stripComments(read(daoPath()));
        String inc = bodyOf(code, "private static String dailyCountIncrementSql(String countColumn)");
        assertTrue(inc.contains("\"INSERT INTO player_daily (player_id, \" + countColumn"),
                "标识符天生无法参数化，拼接是唯一办法");
        String mark = bodyOf(code, "public void markFortuneClaimed(Connection conn, long playerId, String column)");
        assertTrue(mark.contains("UPDATE player_daily SET \" + column + \" = 1 WHERE player_id = ?"),
                "值固定是字面量 1");
        assertTrue(mark.contains("ps.setLong(1, playerId)"), "唯一的参数是玩家 id");
    }

    @Test
    @DisplayName("奖励标记的 UPDATE 没有 WHERE 奖励条件")
    void fortuneClaimHasNoRewardCondition() throws IOException {
        String code = stripComments(read(daoPath()));
        String mark = bodyOf(code, "public void markFortuneClaimed(Connection conn, long playerId, String column)");
        assertFalse(mark.contains("rewarded = 0"),
                "防重复发放完全靠服务层那次「达标且未发过」的判断，SQL 里没有条件");
        assertTrue(mark.contains("WHERE player_id = ?"), "只按玩家过滤");
    }

    @Test
    @DisplayName("getDaily 读不到返回 null，「不存在则建行」留在服务层")
    void getDailyReturnsNullAndInsertIsSeparate() throws IOException {
        String code = stripComments(read(daoPath()));
        String get = bodyOf(code, "public PlayerDaily getDaily(Connection conn, long playerId)");
        assertTrue(get.contains("SELECT * FROM player_daily WHERE player_id = ?"),
                "列清单是 *，与 mapDaily 的逐列读取对应");
        assertTrue(get.contains("return null;"),
                "读不到返回 null：调用方据此决定建行，DAO 不自己 INSERT");
        assertFalse(get.contains("INSERT INTO"),
                "「不存在则建行」是两个语义，拆开放：读就是读");

        String insert = bodyOf(code, "public void insertDaily(Connection conn, long playerId)");
        assertTrue(insert.contains("INSERT INTO player_daily (player_id) VALUES (?)"),
                "只给 player_id 一列有值，其余走列默认值");
    }

    @Test
    @DisplayName("映射的三个 rewarded 与两个 resonance 一律 == 1 判布尔")
    void mappingBooleansAreIntComparison() throws IOException {
        String code = stripComments(read(daoPath()));
        String map = bodyOf(code, "private static PlayerDaily mapDaily(ResultSet rs)");
        for (String column : new String[]{
                "exploration_rewarded", "battle_rewarded", "secret_realm_rewarded",
                "skill_learn_rewarded", "resonance7_awarded", "resonance30_awarded"}) {
            assertTrue(map.contains("rs.getInt(\"" + column + "\") == 1"),
                    column + " 的列类型是 INT DEFAULT 0 不是 BOOLEAN，所以按 == 1 判");
        }
        // 十二列一列都不能少
        for (String column : new String[]{
                "last_morning_cultivation", "consecutive_days", "total_active_days",
                "exploration_count", "battle_count", "secret_realm_count",
                "skill_learn_count"}) {
            assertTrue(map.contains("\"" + column + "\""), column + " 要读");
        }
    }

    @Test
    @DisplayName("DAO 不自己取「现在」")
    void daoDoesNotReadClock() throws IOException {
        String code = stripComments(read(daoPath()));
        assertFalse(code.contains("LocalDate.now()"),
                "复位的日期由调用方传进来：DAO 自己取会让一次重试里的时间悄悄变化");
        assertFalse(code.contains("System.currentTimeMillis()"), "同上");
        assertFalse(code.contains("Instant.now()"), "同上");
    }

    @Test
    @DisplayName("异常文案逐字保留")
    void exceptionMessagesKept() throws IOException {
        String code = stripComments(read(daoPath()));
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"查询每日数据失败\", e)"),
                "读每日数据的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"创建每日数据失败\", e)"),
                "建行的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"每日状态复位失败\", e)"),
                "复位的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"记录每日次数失败\", e)"),
                "计数自增的文案");
        assertEquals(1, countOccurrences(code, "throw new RuntimeException(\"标记机缘奖励失败\", e)"),
                "奖励标记的文案");
    }
}
