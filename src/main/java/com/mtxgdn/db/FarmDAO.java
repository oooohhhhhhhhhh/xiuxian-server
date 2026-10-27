package com.mtxgdn.db;

import com.mtxgdn.game.entity.CropConfig;
import com.mtxgdn.game.entity.FarmPlot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code farm_plots} 表的唯一所有者。
 *
 * <p><b>为什么整张表要收在一个类里</b>：农田的状态机（EMPTY / PLANTED / GROWING /
 * WILTED / READY）跨 15 个方法读写，而"读即结算"的设计要求「读到的地块」与
 * 「随后的写入」落在同一个事务里。SQL 散在服务层时，任何一次"顺手在事务外补个查询"
 * 都会在 SQLite 下撞上 {@code SQLITE_BUSY_SNAPSHOT}（事务里先读后写，另一条连接
 * 抢先提交）。把表收进 DAO 之后，服务层只剩下编排。</p>
 *
 * <p><b>所有方法都是 Connection 首参、不自己开连接</b>：农田的每个入口
 * （播种 / 浇水 / 施肥 / 收获 / 扩建）都是「先惰性结算、再校验、再写入」，
 * 三段必须原子。DAO 自己开连接就破坏了这一点，因此这里<b>刻意没有</b>
 * 自取连接的重载 —— 调用方一定是 {@code FarmService} 的
 * {@code DatabaseManager.runTransaction} lambda。</p>
 *
 * <p><b>方言差异只在两处</b>：{@link #lockClause()}（MySQL 要 {@code FOR UPDATE}，
 * SQLite 不支持）与 {@link #addFertilizerUpTo}（{@code MIN} vs {@code LEAST}）。
 * 两条分支的语义必须一致，否则本地跑绿、线上少肥。</p>
 */
public class FarmDAO {

    /** 农田最大水分值。DAO 只管存，判定阈值仍在服务层。 */
    private static final int WATER_MAX = 100;

    /**
     * 行锁子句：MySQL 用 {@code FOR UPDATE}，SQLite 不支持则留空。
     *
     * <p>SQLite 下靠事务内的写锁保证一致性 —— {@code BEGIN DEFERRED} 之后
     * 第一条写语句就会升级为写锁，所以读的时候不加锁也不会读到别的事务的中间态。</p>
     */
    private static String lockClause() {
        return DatabaseManager.isSqlite() ? "" : " FOR UPDATE";
    }

    // ==================== 读取 ====================

    /**
     * 该玩家的全部地块，按 {@code plot_index} 排序。
     *
     * <p>排序是硬要求：{@code plot_index} 就是玩家看到的地块编号，
     * 不排序会让面板顺序随数据库返回顺序漂移。</p>
     */
    public List<FarmPlot> findByPlayerId(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? ORDER BY plot_index";
        List<FarmPlot> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapPlot(rs));
                }
            }
        }
        return result;
    }

    /**
     * 仍在生长的地块（{@code PLANTED} / {@code GROWING}）——惰性结算的输入。
     *
     * <p><b>为什么只取这两种状态</b>：{@code WILTED} 的枯萎时刻已经定死，
     * {@code READY} 的品质已经掷定，两者再"结算"不会有任何变化；
     * 把它们一并读出来只是白白放大结果集。</p>
     */
    public List<FarmPlot> findGrowingByPlayerId(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND state IN ('PLANTED', 'GROWING')";
        List<FarmPlot> plots = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plots.add(mapPlot(rs));
                }
            }
        }
        return plots;
    }

    /**
     * 可收获的地块（{@code READY}）。
     *
     * <p>加锁读：一键收获会边遍历边把地块重置为 EMPTY，
     * 不加锁时并发的两次"一键收获"可能对同一块地各发一次奖励。</p>
     */
    public List<FarmPlot> findReadyByPlayerId(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND state = 'READY'" + lockClause();
        List<FarmPlot> plots = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plots.add(mapPlot(rs));
                }
            }
        }
        return plots;
    }

    /**
     * 按玩家 + 地块索引取一块地并加行锁；不存在返回 {@code null}。
     *
     * <p><b>必须带 {@code player_id}</b>：早期实现只按 {@code plot_index} 查，
     * 会把别的玩家同编号的地块一起改掉。这里的 {@code WHERE} 是修复后的形态，
     * 不要"优化"回去。</p>
     */
    public FarmPlot findPlotForUpdate(Connection conn, long playerId, int plotIndex) throws SQLException {
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND plot_index = ?" + lockClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlot(rs);
                }
            }
        }
        return null;
    }

    /** 该玩家现有地块数（扩建时用来定新地块的下标与花费）。 */
    public int countPlots(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT COUNT(*) as cnt FROM farm_plots WHERE player_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("cnt") : 0;
            }
        }
    }

    /**
     * 一键浇水要扫的地块：非空地块的编号、状态、水分与枯萎时刻。
     *
     * <p>只取四列而不是 {@code SELECT *}：这条查询在"全部浇水"里跑，
     * 结果集会随地块数增长，而浇水只需要判断"枯萎了没 / 水满了没"。</p>
     */
    public List<WaterRow> findNonEmptyForWatering(Connection conn, long playerId) throws SQLException {
        String sql = "SELECT plot_index, state, water_level, wilted_time FROM farm_plots"
                + " WHERE player_id = ? AND state != 'EMPTY'" + lockClause();
        List<WaterRow> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new WaterRow(rs.getInt("plot_index"), rs.getString("state"),
                            rs.getInt("water_level"), rs.getLong("wilted_time")));
                }
            }
        }
        return rows;
    }

    /**
     * 一键施肥要扫的地块编号：非空且肥力未满。
     *
     * <p>肥力上限作为参数传进来而不是写死：上限是玩法数值，
     * 由服务层的 {@code FERTILIZER_MAX} 决定，DAO 不该持有它。</p>
     */
    public List<Integer> findUnderFertilizedPlotIndexes(Connection conn, long playerId, int maxFertilizer)
            throws SQLException {
        String sql = "SELECT plot_index, fertilizer_level FROM farm_plots"
                + " WHERE player_id = ? AND state != 'EMPTY' AND fertilizer_level < ?" + lockClause();
        List<Integer> plotIndexes = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, maxFertilizer);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plotIndexes.add(rs.getInt("plot_index"));
                }
            }
        }
        return plotIndexes;
    }

    // ==================== 写入 ====================

    /**
     * 插入一块新地块。
     *
     * <p>初始状态由调用方给：首次初始化是 EMPTY + 满水，扩建出来的也是同一形态，
     * 但把字面量留在服务层可以让"新地块初始水分是多少"这类玩法问题只改一处。</p>
     */
    public void insertPlot(Connection conn, long playerId, int plotIndex, String state,
                           int waterLevel, int fertilizerLevel) throws SQLException {
        String sql = "INSERT INTO farm_plots (player_id, plot_index, state, water_level, fertilizer_level)"
                + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            ps.setString(3, state);
            ps.setInt(4, waterLevel);
            ps.setInt(5, fertilizerLevel);
            ps.executeUpdate();
        }
    }

    /**
     * 播种：一次性写满种植相关的全部列，并把枯萎/虫害/品质重置。
     *
     * <p>{@code wilted_time = 0} 写死在 SQL 里而不是绑参：新种下的作物不可能处于枯萎态，
     * 做成参数只会多一个可以传错的地方。</p>
     *
     * <p>注意 {@code growth_stage} 固定写 1 —— 阶段之后由时间推导，
     * 这里只是给一个合法初值。</p>
     */
    public void updatePlotToPlanted(Connection conn, FarmPlot plot, CropConfig config, String seedKey,
                                    long now, long harvestTime, int yield,
                                    double rootBonus, double seasonModifier) throws SQLException {
        String sql = """
            UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                planted_time = ?, harvest_time = ?, growth_stage = ?,
                water_level = ?, fertilizer_level = ?, yield = ?, root_bonus = ?, season_modifier = ?,
                settled_time = ?, pest_state = ?, pest_time = ?, crop_quality = ?, wilted_time = 0
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, FarmPlot.PlotState.PLANTED.name());
            ps.setString(2, seedKey);
            ps.setString(3, config.getCropKey());
            ps.setLong(4, now);
            ps.setLong(5, harvestTime);
            ps.setInt(6, 1);
            ps.setInt(7, WATER_MAX);
            ps.setInt(8, 0);
            ps.setInt(9, Math.max(yield, 1));
            ps.setDouble(10, rootBonus);
            ps.setDouble(11, seasonModifier);
            ps.setLong(12, now);
            ps.setString(13, FarmPlot.PestState.CLEAN.name());
            ps.setLong(14, 0);
            ps.setString(15, null);
            ps.setLong(16, plot.getId());
            ps.executeUpdate();
        }
    }

    /**
     * 把惰性结算的结果一次性落库（单条 UPDATE）。
     *
     * <p><b>为什么必须是一条语句</b>：状态、阶段、水分、枯萎、虫害、品质、结算时刻
     * 是同一次结算的产物，拆成多条 UPDATE 时任何一步失败都会留下
     * "水分降了但 settled_time 没推进"这种半截状态 —— 下次结算会把同一段时间
     * 再算一遍，水分被重复扣减。</p>
     *
     * <p>{@code pest_state} 与 {@code crop_quality} 允许为 null 并落成 SQL NULL：
     * 老数据里这两列可能还没写过。</p>
     */
    public void persistSettledPlot(Connection conn, FarmPlot plot) throws SQLException {
        String sql = """
            UPDATE farm_plots SET state = ?, growth_stage = ?, water_level = ?, wilted_time = ?,
                pest_state = ?, pest_time = ?, crop_quality = ?, settled_time = ?
            WHERE id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, plot.getState().name());
            ps.setInt(2, plot.getGrowthStage());
            ps.setInt(3, plot.getWaterLevel());
            ps.setLong(4, plot.getWiltedTime());
            ps.setString(5, plot.getPestState() == null
                    ? FarmPlot.PestState.CLEAN.name() : plot.getPestState().name());
            ps.setLong(6, plot.getPestTime());
            ps.setString(7, plot.getCropQuality() == null ? null : plot.getCropQuality().name());
            ps.setLong(8, plot.getSettledTime());
            ps.setLong(9, plot.getId());
            ps.executeUpdate();
        }
    }

    /** 只改水分（按玩家 + 地块索引，不用 id：调用方手上通常只有编号）。 */
    public void updateWaterLevel(Connection conn, long playerId, int plotIndex, int waterLevel)
            throws SQLException {
        String sql = "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, waterLevel);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        }
    }

    /** 只改肥力。 */
    public void updateFertilizerLevel(Connection conn, long playerId, int plotIndex, int fertilizerLevel)
            throws SQLException {
        String sql = "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, fertilizerLevel);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        }
    }

    /** 只改状态。{@code WHERE} 带 {@code player_id} 是修复后的形态，见 {@link #findPlotForUpdate}。 */
    public void updatePlotState(Connection conn, long playerId, int plotIndex, String state)
            throws SQLException {
        String sql = "UPDATE farm_plots SET state = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        }
    }

    /** 清零枯萎时刻（复活后调用）。 */
    public void resetWiltedTime(Connection conn, long playerId, int plotIndex) throws SQLException {
        String sql = "UPDATE farm_plots SET wilted_time = 0 WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            ps.executeUpdate();
        }
    }

    /** 清除病虫害（打药后调用）：状态回到 CLEAN，虫害时刻归零。 */
    public void clearPestState(Connection conn, long playerId, int plotIndex) throws SQLException {
        String sql = "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, FarmPlot.PestState.CLEAN.name());
            ps.setLong(2, 0);
            ps.setLong(3, playerId);
            ps.setInt(4, plotIndex);
            ps.executeUpdate();
        }
    }

    /**
     * 把地块重置为空地（收获 / 清地后调用）。
     *
     * <p>{@code settled_time = 0} 与 {@code wilted_time = 0} 写死在 SQL 里：
     * 空地块没有"上次结算"的概念，留着旧值会让下次种下的作物在第一次结算时
     * 被按旧时间戳算出一大段流逝。</p>
     */
    public void resetPlot(Connection conn, long playerId, int plotIndex, String state,
                          int waterLevel, String pestState) throws SQLException {
        String sql = """
            UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                planted_time = ?, harvest_time = ?, growth_stage = ?,
                water_level = ?, fertilizer_level = ?, yield = ?,
                wilted_time = 0, settled_time = 0,
                pest_state = ?, pest_time = 0, crop_quality = ?
            WHERE player_id = ? AND plot_index = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setString(2, null);
            ps.setString(3, null);
            ps.setLong(4, 0);
            ps.setLong(5, 0);
            ps.setInt(6, 0);
            ps.setInt(7, waterLevel);
            ps.setInt(8, 0);
            ps.setInt(9, 0);
            ps.setString(10, pestState);
            ps.setString(11, null);
            ps.setLong(12, playerId);
            ps.setInt(13, plotIndex);
            ps.executeUpdate();
        }
    }

    /**
     * 复活一块枯萎地块：状态回到生长态、水分给到 {@code waterLevel}、枯萎时刻清零。
     *
     * <p>按 {@code id} 而不是 {@code player_id + plot_index}：调用方是从
     * {@link #findPlotForUpdate} 拿到的实体，手上就有主键，用主键更直接。</p>
     */
    public void reviveWiltedPlot(Connection conn, long plotId, String state, int waterLevel)
            throws SQLException {
        String sql = "UPDATE farm_plots SET state = ?, water_level = ?, wilted_time = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setInt(2, waterLevel);
            ps.setLong(3, 0);
            ps.setLong(4, plotId);
            ps.executeUpdate();
        }
    }

    /**
     * 批量加肥力：一条 {@code PreparedStatement} 复用多次。
     *
     * <p><b>封顶必须落在 SQL 里</b>：{@code fertilizer_level + ?} 的读取与回写在
     * 应用层做会丢更新（两个并发施肥各读到 90，都写成 100 而不是各自叠加）。
     * 但两种库的函数名不同 —— SQLite 是 {@code MIN}，MySQL 是 {@code LEAST}，
     * SQLite 里没有 {@code LEAST}，MySQL 里 {@code MIN} 在标量位置语义也不同。
     * 这条方言分支不能合并。</p>
     *
     * <p>不合成 {@code IN (...)} 的单条 UPDATE：{@code plot_index} 的集合大小
     * 在扩建后会变化，拼 SQL 既难读又要把占位符数目算对；而这是同一次事务内
     * 的连续小更新，复用语句的开销可以忽略。</p>
     *
     * @return 实际更新了几块地
     */
    public int addFertilizerUpToAll(Connection conn, long playerId, List<Integer> plotIndexes,
                                    int amount, int max) throws SQLException {
        String sql = DatabaseManager.isSqlite()
                ? "UPDATE farm_plots SET fertilizer_level = MIN(fertilizer_level + ?, ?)"
                + " WHERE player_id = ? AND plot_index = ?"
                : "UPDATE farm_plots SET fertilizer_level = LEAST(fertilizer_level + ?, ?)"
                + " WHERE player_id = ? AND plot_index = ?";
        int updated = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int plotIndex : plotIndexes) {
                ps.setInt(1, amount);
                ps.setInt(2, max);
                ps.setLong(3, playerId);
                ps.setInt(4, plotIndex);
                ps.executeUpdate();
                updated++;
            }
        }
        return updated;
    }

    // ==================== 行映射 ====================

    /**
     * 把一行 {@code farm_plots} 映射成实体。
     *
     * <p>{@code state} / {@code pest_state} 直接 {@code valueOf}：这两列在 DDL 里
     * 有默认值，正常不会为 null。{@code crop_quality} 单独判空是因为它是后加的列，
     * 老数据里可能确实没有值 —— 此时留 null 表示"还没掷过品质"，
     * 而不是硬塞成 COMMON（那会让老作物凭空掉一档）。</p>
     */
    private static FarmPlot mapPlot(ResultSet rs) throws SQLException {
        FarmPlot plot = new FarmPlot();
        plot.setId(rs.getLong("id"));
        plot.setPlayerId(rs.getLong("player_id"));
        plot.setPlotIndex(rs.getInt("plot_index"));
        plot.setState(FarmPlot.PlotState.valueOf(rs.getString("state")));
        plot.setSeedKey(rs.getString("seed_key"));
        plot.setCropKey(rs.getString("crop_key"));
        plot.setPlantedTime(rs.getLong("planted_time"));
        plot.setHarvestTime(rs.getLong("harvest_time"));
        plot.setWiltedTime(rs.getLong("wilted_time"));
        plot.setGrowthStage(rs.getInt("growth_stage"));
        plot.setWaterLevel(rs.getInt("water_level"));
        plot.setFertilizerLevel(rs.getInt("fertilizer_level"));
        plot.setYield(rs.getInt("yield"));
        plot.setRootBonus(rs.getDouble("root_bonus"));
        plot.setSeasonModifier(rs.getDouble("season_modifier"));
        plot.setPestState(FarmPlot.PestState.valueOf(rs.getString("pest_state")));
        plot.setPestTime(rs.getLong("pest_time"));
        String qualityStr = rs.getString("crop_quality");
        if (qualityStr != null) {
            plot.setCropQuality(FarmPlot.CropQuality.valueOf(qualityStr));
        }
        plot.setSettledTime(rs.getLong("settled_time"));
        return plot;
    }

    /** 一键浇水需要的最小行视图。 */
    public record WaterRow(int plotIndex, String state, int waterLevel, long wiltedTime) {
    }
}
