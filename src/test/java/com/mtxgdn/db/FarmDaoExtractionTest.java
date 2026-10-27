package com.mtxgdn.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 农田域的 JDBC 边界。
 *
 * <p><b>缺陷形态</b>：{@code FarmService} 原来把 {@code farm_plots} 的 18 条 SQL
 * 直接写在服务层 —— 6 条 SELECT、11 条 UPDATE、1 条 INSERT，散在 15 个方法里。
 * 这张表的状态机（EMPTY / PLANTED / GROWING / WILTED / READY）跨这些方法读写，
 * 任何一次"顺手在事务外补个查询"都会在 SQLite 下撞上 {@code SQLITE_BUSY_SNAPSHOT}。</p>
 *
 * <p>本测试钉住三件事：服务层不再有 JDBC 与 SQL、DAO 只管 SQL 不管事务、
 * 以及几条容易在重构中被"顺手优化"掉的语义（方言分支、按玩家限定 WHERE、
 * 封顶落在 SQL 里）。</p>
 */
class FarmDaoExtractionTest extends DaoExtractionAssertions {

    @Override
    protected Class<?> daoClass() {
        return FarmDAO.class;
    }

    @Override
    protected String servicePath() {
        return "src/main/java/com/mtxgdn/game/service/FarmService.java";
    }

    @Override
    protected String daoPath() {
        return "src/main/java/com/mtxgdn/db/FarmDAO.java";
    }

    @Override
    protected List<String> serviceAnchors() {
        return List.of(
                // ---- 惰性结算的数值规则留在服务层 ----
                "private static final double WATER_DECREASE_PER_SECOND = 0.1;",
                "private static final long WILTED_DEAD_SECONDS = 6 * 3600;",
                "private static final double PEST_CHANCE_PER_HOUR = 0.02;",
                "private static final long PEST_GRACE_MS = 5 * 60 * 1000L;",
                "private static final long PEST_UPGRADE_MS = 30 * 60 * 1000L;",
                "PEST_CHANCE_PER_HOUR / 3600.0",
                // ---- 结算算法本身 ----
                "long secondsToDry = (long) Math.ceil(water / WATER_DECREASE_PER_SECOND);",
                "plot.setWiltedTime(since + secondsToDry * 1000L);",
                "determineQuality(plot)",
                "settlePest(plot, since, elapsedSeconds, now);",
                "computeStage(plot, config, now)",
                // 时钟回拨保护：基准点晚于 now 时按 now 处理
                "if (since > now) since = now;",
                // ---- 产量公式与病虫害惩罚 ----
                "int waterBonus = (int) (baseYield * plot.getWaterLevel() / 100.0 * 0.5);",
                "int randomBonus = random.nextInt(3);",
                "Math.max(1, Math.min(total, config.getMaxYield() * 2))",
                // ---- 扩建花费 ----
                "100L * (long) Math.pow(2, currentCount - DEFAULT_PLOTS)",
                // ---- 事务编排 ----
                "farmDAO.findByPlayerId(conn, playerId)",
                "farmDAO.findGrowingByPlayerId(conn, playerId)",
                "farmDAO.findReadyByPlayerId(conn, playerId)",
                "farmDAO.findPlotForUpdate(conn, playerId, plotIndex)",
                "farmDAO.findNonEmptyForWatering(conn, playerId)",
                "farmDAO.findUnderFertilizedPlotIndexes(conn, playerId, FERTILIZER_MAX)",
                "farmDAO.addFertilizerUpToAll(",
                "farmDAO.reviveWiltedPlot(conn, plot.getId(),",
                // ---- 模块门控 ----
                "isEnabledOrUnknown(\"farm\")");
    }

    @Override
    protected List<String> daoForbiddenTokens() {
        return List.of(
                // 农田的每个入口都是「先结算、再校验、再写入」，DAO 自己开事务就破坏原子性
                "DatabaseManager.runTransaction(",
                "setAutoCommit(",
                "rollback(");
    }

    // ==================== 1. 服务层不再有 JDBC（继承的断言已覆盖） ====================

    @Test
    @DisplayName("服务层: 18 条 farm_plots 的 SQL 一条都不剩")
    void serviceHasNoFarmSqlLeft() throws IOException {
        String code = stripComments(read(servicePath()));
        for (String table : List.of("farm_plots")) {
            assertFalse(code.contains(table), "服务层不该再出现表名 " + table);
        }
        assertFalse(code.contains("UPDATE farm_plots"), "服务层不该再出现 farm_plots 的 UPDATE");
        assertFalse(code.contains("MIN(fertilizer_level"), "封顶逻辑不该留在服务层");
        assertFalse(code.contains("LEAST("), "方言分支不该留在服务层");
        assertFalse(code.contains("FOR UPDATE"), "行锁子句不该留在服务层");
    }

    // ==================== 2. DAO 结构 ====================

    @Test
    @DisplayName("DAO: 只持有 farm_plots 一张表，不碰玩家表")
    void daoOwnsOnlyFarmPlots() throws IOException {
        String code = stripComments(read(daoPath()));
        assertTrue(code.contains("farm_plots"), "DAO 必须操作 farm_plots");
        // 加钱走 playerService.addGold，加物品走 itemService.addItem —— 都不是农田该管的事
        assertFalse(code.contains("UPDATE players"), "农田 DAO 不该直接改 players 表");
        assertFalse(code.contains("player_items"), "农田 DAO 不该碰背包表");
    }

    @Test
    @DisplayName("DAO: 每个方法都是 Connection 首参（农田全部入口都在事务内）")
    void daoIsConnectionFirstOnly() throws IOException {
        String code = stripComments(read(daoPath()));
        // 没有任何一处自取连接：农田的读取与写入必须共用调用方那条连接
        assertEquals(0, countOccurrences(code, "getConnection("),
                "FarmDAO 不该自己开连接：所有入口都在 FarmService 的事务里");
    }

    // ==================== 3. SQL 守恒 ====================

    /** 抽取时逐字搬过来的全部 SQL 片段（去空白后比对）。 */
    private static final List<String> GOLDEN_SQL = List.of(
            // ---- 读取 ----
            "SELECT * FROM farm_plots WHERE player_id = ? ORDER BY plot_index",
            "SELECT * FROM farm_plots WHERE player_id = ? AND state IN ('PLANTED', 'GROWING')",
            "SELECT * FROM farm_plots WHERE player_id = ? AND state = 'READY'",
            "SELECT * FROM farm_plots WHERE player_id = ? AND plot_index = ?",
            "SELECT COUNT(*) as cnt FROM farm_plots WHERE player_id = ?",
            "SELECT plot_index, state, water_level, wilted_time FROM farm_plots"
                    + " WHERE player_id = ? AND state != 'EMPTY'",
            "SELECT plot_index, fertilizer_level FROM farm_plots"
                    + " WHERE player_id = ? AND state != 'EMPTY' AND fertilizer_level < ?",
            // ---- 写入 ----
            "INSERT INTO farm_plots (player_id, plot_index, state, water_level, fertilizer_level)"
                    + " VALUES (?, ?, ?, ?, ?)",
            "UPDATE farm_plots SET state = ?, water_level = ?, wilted_time = ? WHERE id = ?",
            "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?",
            "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?",
            "UPDATE farm_plots SET state = ? WHERE player_id = ? AND plot_index = ?",
            "UPDATE farm_plots SET wilted_time = 0 WHERE player_id = ? AND plot_index = ?",
            "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE player_id = ? AND plot_index = ?",
            // ---- 方言分支 ----
            "UPDATE farm_plots SET fertilizer_level = MIN(fertilizer_level + ?, ?)",
            "UPDATE farm_plots SET fertilizer_level = LEAST(fertilizer_level + ?, ?)");

    @Test
    @DisplayName("SQL: 抽取前的每条语句都逐字出现在 FarmDAO 里（旧有新无为空）")
    void sqlIsConserved() throws IOException {
        String daoSource = stripComments(read(daoPath()));
        StringBuilder fingerprint = new StringBuilder();
        for (String literal : stringLiterals(daoSource)) {
            fingerprint.append(squash(literal));
        }
        String flat = fingerprint.toString();

        List<String> missing = new java.util.ArrayList<>();
        for (String sql : GOLDEN_SQL) {
            if (!flat.contains(squash(sql))) {
                missing.add(sql);
            }
        }
        assertTrue(missing.isEmpty(),
                "下面这些 SQL 在 FarmDAO 里找不到（搬丢了、或被「顺手优化」了）。"
                        + "本次重构要求表名/列名/条件/ORDER BY/参数顺序逐字不变：\n"
                        + String.join("\n---\n", missing));
    }

    // ==================== 4. 几条不能"顺手优化"的语义 ====================

    @Test
    @DisplayName("方言: 封顶必须留在 SQL 里，且 SQLite 用 MIN、MySQL 用 LEAST")
    void fertilizerCapStaysInSqlWithDialectBranch() throws IOException {
        String code = stripComments(read(daoPath()));
        // 封顶落在 SQL 里：应用层读-改-写会丢更新（两个并发施肥各读到 90，都写成 100）
        assertTrue(code.contains("MIN(fertilizer_level + ?, ?)"),
                "SQLite 分支必须用 MIN(fertilizer_level + ?, ?) 封顶");
        assertTrue(code.contains("LEAST(fertilizer_level + ?, ?)"),
                "MySQL 分支必须用 LEAST(fertilizer_level + ?, ?) 封顶 —— "
                        + "SQLite 没有 LEAST，MySQL 的 MIN 在标量位置语义不同，分支不能合并");
        assertEquals(2, countOccurrences(code, "DatabaseManager.isSqlite()"),
                "只有封顶与行锁两处方言分支，多出来说明有人又加了方言判断");
    }

    @Test
    @DisplayName("安全: 按地块索引的写操作必须同时限定 player_id")
    void playerScopedWritesKeepPlayerId() throws IOException {
        String code = stripComments(read(daoPath()));
        // 早期实现只按 plot_index 查/改，会把别的玩家同编号的地块一起改掉
        String[] scopedWrites = {
                "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?",
                "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?",
                "UPDATE farm_plots SET state = ? WHERE player_id = ? AND plot_index = ?",
                "UPDATE farm_plots SET wilted_time = 0 WHERE player_id = ? AND plot_index = ?",
                "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE player_id = ? AND plot_index = ?",
        };
        for (String sql : scopedWrites) {
            assertTrue(code.contains(sql), "按地块索引的写操作必须带 player_id: " + sql);
        }
        // 不允许出现只按 plot_index 定位的写语句
        assertFalse(code.contains("WHERE plot_index = ?\"") || code.contains("WHERE plot_index = ? "),
                "不许出现只按 plot_index 定位的写语句（会误改其他玩家同编号的地块）");
    }

    @Test
    @DisplayName("结算: 落库必须是单条 UPDATE（拆开会让水分被重复扣减）")
    void settlementPersistsInOneStatement() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void persistSettledPlot(Connection conn, FarmPlot plot)");
        assertEquals(1, countOccurrences(body, "executeUpdate()"),
                "结算落库必须是一条 UPDATE：拆成多条时中间失败会留下"
                        + "「水分降了但 settled_time 没推进」，下次结算重复扣减同一段时间");
        // 七个字段一次写完
        for (String col : List.of("state = ?", "growth_stage = ?", "water_level = ?",
                "wilted_time = ?", "pest_state = ?", "pest_time = ?",
                "crop_quality = ?", "settled_time = ?")) {
            assertTrue(body.contains(col), "结算落库必须覆盖 " + col);
        }
    }

    @Test
    @DisplayName("重置: 空地必须把 settled_time 与 wilted_time 清零")
    void resetPlotClearsTimestamps() throws IOException {
        String raw = read(daoPath());
        String code = stripComments(raw);
        String body = bodyOf(code, "public void resetPlot(Connection conn, long playerId, int plotIndex,");
        assertTrue(body.contains("wilted_time = 0"), "重置地块必须清零枯萎时刻");
        assertTrue(body.contains("settled_time = 0"),
                "重置地块必须清零结算时刻：留着旧值会让下次种下的作物"
                        + "在第一次结算时被按旧时间戳算出一大段流逝");
        assertTrue(body.contains("pest_time = 0"), "重置地块必须清零虫害时刻");
    }

    @Test
    @DisplayName("播种: 新种下的作物不可能处于枯萎态，wilted_time 写死在 SQL 里")
    void plantResetsWiltedTimeInSql() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "public void updatePlotToPlanted(Connection conn, FarmPlot plot,");
        assertTrue(body.contains("wilted_time = 0"),
                "播种 SQL 里必须写死 wilted_time = 0（做成绑参只会多一个能传错的地方）");
        assertTrue(body.contains("Math.max(yield, 1)"),
                "产量下限 1 必须保留：0 会让玩家收完发现物品栏没变化");
    }

    @Test
    @DisplayName("映射: crop_quality 单独判空（老数据可能没有该列值）")
    void mapPlotHandlesMissingQuality() throws IOException {
        String code = stripComments(read(daoPath()));
        String body = bodyOf(code, "private static FarmPlot mapPlot(ResultSet rs)");
        assertTrue(body.contains("if (qualityStr != null)"),
                "crop_quality 必须判空：硬塞成 COMMON 会让老作物凭空掉一档品质");
        // 状态两列直接 valueOf：DDL 里有默认值，null 属于数据损坏，应当炸出来
        assertTrue(body.contains("FarmPlot.PlotState.valueOf(rs.getString(\"state\"))"),
                "state 直接 valueOf，null 应炸出来而不是静默兜底");
    }

    // ==================== 5. 夹具 ====================

    private static List<String> stringLiterals(String code) {
        List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < code.length() && code.charAt(i) != '"') {
                    if (code.charAt(i) == '\\') {
                        i++;
                        if (i < code.length()) {
                            sb.append(code.charAt(i));
                            i++;
                        }
                        continue;
                    }
                    sb.append(code.charAt(i));
                    i++;
                }
                i++;
                out.add(sb.toString());
            } else if (c == '\'') {
                i++;
                while (i < code.length() && code.charAt(i) != '\'') {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
