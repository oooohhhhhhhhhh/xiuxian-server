package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.CropConfig;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.util.GameLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FarmService {

    private static final GameLogger LOG = GameLogger.getLogger(FarmService.class);
    private static final int DEFAULT_PLOTS = 4;
    private static final int MAX_PLOTS = 12;
    private static final int WATER_MAX = 100;
    private static final int FERTILIZER_MAX = 100;
    private static final int WATER_DECREASE_PER_SECOND = 1;

    private final PlayerService playerService = new PlayerService();
    private final ItemService itemService = new ItemService();
    private final Random random = new Random();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(FarmService::updateAllPlots, 1, 1, TimeUnit.SECONDS);
    }

    public List<FarmPlot> getPlots(long playerId) {
        initPlots(playerId);
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? ORDER BY plot_index";
        List<FarmPlot> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapPlot(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询农田失败", e);
        }
        return result;
    }

    private void initPlots(long playerId) {
        String countSql = "SELECT COUNT(*) as cnt FROM farm_plots WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt("cnt") == 0) {
                    String insertSql = "INSERT INTO farm_plots (player_id, plot_index, state, water_level, fertilizer_level) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement insPs = conn.prepareStatement(insertSql)) {
                        for (int i = 0; i < DEFAULT_PLOTS; i++) {
                            insPs.setLong(1, playerId);
                            insPs.setInt(2, i);
                            insPs.setString(3, FarmPlot.PlotState.EMPTY.name());
                            insPs.setInt(4, WATER_MAX);
                            insPs.setInt(5, 0);
                            insPs.executeUpdate();
                        }
                    }
                    LOG.info("玩家 " + playerId + " 初始化农田 " + DEFAULT_PLOTS + " 块");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("初始化农田失败", e);
        }
    }

    public Map<String, Object> plant(long playerId, int plotIndex, String seedKey) {
        Map<String, Object> result = new LinkedHashMap<>();

        CropConfig config = CropConfig.get(seedKey);
        if (config == null) {
            result.put("success", false);
            result.put("message", "未知种子");
            return result;
        }

        if (itemService.getItemCount(playerId, seedKey) < 1) {
            Item seed = ItemRegistry.get(seedKey);
            String seedName = seed != null ? seed.getName() : seedKey;
            result.put("success", false);
            result.put("message", "种子不足：需要 " + seedName);
            return result;
        }

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                    }
                }
            }

            if (currentState == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if (!FarmPlot.PlotState.EMPTY.name().equals(currentState)) {
                txResult.put("success", false);
                txResult.put("message", "地块已被占用");
                return txResult;
            }

            itemService.removeItem(playerId, seedKey, 1);

            long now = System.currentTimeMillis();
            long harvestTime = now + (long) config.getGrowthSeconds() * 1000;

            String updateSql = """
                UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                    planted_time = ?, harvest_time = ?, growth_stage = ?,
                    water_level = ?, fertilizer_level = ?, yield = ?
                WHERE player_id = ? AND plot_index = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, FarmPlot.PlotState.PLANTED.name());
                ps.setString(2, seedKey);
                ps.setString(3, config.getCropKey());
                ps.setLong(4, now);
                ps.setLong(5, harvestTime);
                ps.setInt(6, 1);
                ps.setInt(7, WATER_MAX);
                ps.setInt(8, 0);
                ps.setInt(9, config.getBaseYield());
                ps.setLong(10, playerId);
                ps.setInt(11, plotIndex);
                ps.executeUpdate();
            }

            Item seed = ItemRegistry.get(seedKey);
            String seedName = seed != null ? seed.getName() : config.getCropName();
            txResult.put("success", true);
            txResult.put("message", "已种植 " + seedName + "，预计 " + config.getGrowthSeconds() + " 秒后可收获");
            return txResult;
        });
    }

    public Map<String, Object> water(long playerId, int plotIndex) {
        Map<String, Object> result = new LinkedHashMap<>();

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state, water_level FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            int currentWater = 0;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                        currentWater = rs.getInt("water_level");
                    }
                }
            }

            if (currentState == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if (FarmPlot.PlotState.EMPTY.name().equals(currentState)) {
                txResult.put("success", false);
                txResult.put("message", "空地无需浇水");
                return txResult;
            }

            if (currentWater >= WATER_MAX) {
                txResult.put("success", false);
                txResult.put("message", "水分已充足");
                return txResult;
            }

            int newWater = Math.min(WATER_MAX, currentWater + 30);
            String updateSql = "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, newWater);
                ps.setLong(2, playerId);
                ps.setInt(3, plotIndex);
                ps.executeUpdate();
            }

            txResult.put("success", true);
            txResult.put("message", "浇水成功！水分: " + newWater + "%");
            return txResult;
        });
    }

    public Map<String, Object> fertilize(long playerId, int plotIndex) {
        Map<String, Object> result = new LinkedHashMap<>();

        String fertilizerKey = "mtxgdn:enhance_stone";
        if (itemService.getItemCount(playerId, fertilizerKey) < 1) {
            Item fertilizer = ItemRegistry.get(fertilizerKey);
            String fertilizerName = fertilizer != null ? fertilizer.getName() : "肥料";
            result.put("success", false);
            result.put("message", "肥料不足：需要 " + fertilizerName);
            return result;
        }

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state, fertilizer_level FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            int currentFertilizer = 0;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                        currentFertilizer = rs.getInt("fertilizer_level");
                    }
                }
            }

            if (currentState == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if (FarmPlot.PlotState.EMPTY.name().equals(currentState)) {
                txResult.put("success", false);
                txResult.put("message", "空地无需施肥");
                return txResult;
            }

            if (currentFertilizer >= FERTILIZER_MAX) {
                txResult.put("success", false);
                txResult.put("message", "肥力已充足");
                return txResult;
            }

            itemService.removeItem(playerId, fertilizerKey, 1);

            int newFertilizer = Math.min(FERTILIZER_MAX, currentFertilizer + 25);
            String updateSql = "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, newFertilizer);
                ps.setLong(2, playerId);
                ps.setInt(3, plotIndex);
                ps.executeUpdate();
            }

            txResult.put("success", true);
            txResult.put("message", "施肥成功！肥力: " + newFertilizer + "%");
            return txResult;
        });
    }

    public Map<String, Object> harvest(long playerId, int plotIndex) {
        Map<String, Object> result = new LinkedHashMap<>();

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT * FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            FarmPlot plot = null;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        plot = mapPlot(rs);
                    }
                }
            }

            if (plot == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if (plot.getState() != FarmPlot.PlotState.READY) {
                txResult.put("success", false);
                txResult.put("message", "作物还未成熟");
                return txResult;
            }

            CropConfig config = CropConfig.get(plot.getSeedKey());
            if (config == null) {
                txResult.put("success", false);
                txResult.put("message", "未知作物");
                return txResult;
            }

            int yield = calculateYield(plot, config);

            itemService.addItem(playerId, plot.getCropKey(), yield);

            String resetSql = """
                UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                    planted_time = ?, harvest_time = ?, growth_stage = ?,
                    water_level = ?, fertilizer_level = ?, yield = ?
                WHERE player_id = ? AND plot_index = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(resetSql)) {
                ps.setString(1, FarmPlot.PlotState.EMPTY.name());
                ps.setString(2, null);
                ps.setString(3, null);
                ps.setLong(4, 0);
                ps.setLong(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, WATER_MAX);
                ps.setInt(8, 0);
                ps.setInt(9, 0);
                ps.setLong(10, playerId);
                ps.setInt(11, plotIndex);
                ps.executeUpdate();
            }

            Item crop = ItemRegistry.get(plot.getCropKey());
            String cropName = crop != null ? crop.getName() : config.getCropName();
            txResult.put("success", true);
            txResult.put("message", "收获成功！获得 " + cropName + " x" + yield);
            txResult.put("yield", yield);
            return txResult;
        });
    }

    private int calculateYield(FarmPlot plot, CropConfig config) {
        int baseYield = plot.getYield();
        int waterBonus = (int) (baseYield * plot.getWaterLevel() / 100.0 * 0.5);
        int fertilizerBonus = (int) (baseYield * plot.getFertilizerLevel() / 100.0 * config.getFertilizerBonus() / 100.0);
        int randomBonus = random.nextInt(3);
        int total = baseYield + waterBonus + fertilizerBonus + randomBonus;
        return Math.min(total, config.getMaxYield());
    }

    private static void updateAllPlots() {
        String sql = "SELECT * FROM farm_plots WHERE state IN ('PLANTED', 'GROWING')";
        long now = System.currentTimeMillis();

        List<FarmPlot> plots = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                plots.add(mapPlot(rs));
            }
        } catch (SQLException e) {
            return;
        }

        for (FarmPlot plot : plots) {
            updatePlotState(plot, now);
        }
    }

    private static void updatePlotState(FarmPlot plot, long now) {
        String updateSql = "UPDATE farm_plots SET state = ?, growth_stage = ?, water_level = ? WHERE id = ?";

        CropConfig config = CropConfig.get(plot.getSeedKey());
        if (config == null) return;

        int newWater = Math.max(0, plot.getWaterLevel() - WATER_DECREASE_PER_SECOND);

        if (newWater <= 0) {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, FarmPlot.PlotState.WILTED.name());
                ps.setInt(2, plot.getGrowthStage());
                ps.setInt(3, 0);
                ps.setLong(4, plot.getId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            return;
        }

        if (now >= plot.getHarvestTime()) {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, FarmPlot.PlotState.READY.name());
                ps.setInt(2, config.getStages());
                ps.setInt(3, newWater);
                ps.setLong(4, plot.getId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            return;
        }

        long elapsed = now - plot.getPlantedTime();
        long total = plot.getHarvestTime() - plot.getPlantedTime();
        int stage = (int) ((elapsed * config.getStages()) / total) + 1;
        stage = Math.min(stage, config.getStages());

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, FarmPlot.PlotState.GROWING.name());
            ps.setInt(2, stage);
            ps.setInt(3, newWater);
            ps.setLong(4, plot.getId());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public Map<String, Object> clearPlot(long playerId, int plotIndex) {
        Map<String, Object> result = new LinkedHashMap<>();

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                    }
                }
            }

            if (currentState == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if (FarmPlot.PlotState.EMPTY.name().equals(currentState)) {
                txResult.put("success", false);
                txResult.put("message", "地块已是空地");
                return txResult;
            }

            String resetSql = """
                UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                    planted_time = ?, harvest_time = ?, growth_stage = ?,
                    water_level = ?, fertilizer_level = ?, yield = ?
                WHERE player_id = ? AND plot_index = ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(resetSql)) {
                ps.setString(1, FarmPlot.PlotState.EMPTY.name());
                ps.setString(2, null);
                ps.setString(3, null);
                ps.setLong(4, 0);
                ps.setLong(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, WATER_MAX);
                ps.setInt(8, 0);
                ps.setInt(9, 0);
                ps.setLong(10, playerId);
                ps.setInt(11, plotIndex);
                ps.executeUpdate();
            }

            txResult.put("success", true);
            txResult.put("message", "已清理地块");
            return txResult;
        });
    }

    public Map<String, Object> expandPlot(long playerId) {
        Map<String, Object> result = new LinkedHashMap<>();

        String countSql = "SELECT COUNT(*) as cnt FROM farm_plots WHERE player_id = ?";
        int currentCount = 0;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    currentCount = rs.getInt("cnt");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询地块数量失败", e);
        }

        if (currentCount >= MAX_PLOTS) {
            result.put("success", false);
            result.put("message", "已达到最大地块数");
            return result;
        }

        long cost = calculateExpandCost(currentCount);
        com.mtxgdn.entity.Player player = playerService.getPlayerById(playerId);
        if (player == null || player.getGold() < cost) {
            result.put("success", false);
            result.put("message", "金币不足，需要 " + cost + " 金币");
            return result;
        }

        final int plotIndex = currentCount;
        final long finalCost = cost;

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            playerService.addGold(playerId, -finalCost);

            String insertSql = "INSERT INTO farm_plots (player_id, plot_index, state, water_level, fertilizer_level) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                ps.setString(3, FarmPlot.PlotState.EMPTY.name());
                ps.setInt(4, WATER_MAX);
                ps.setInt(5, 0);
                ps.executeUpdate();
            }

            txResult.put("success", true);
            txResult.put("message", "扩建成功！新地块已开启，花费 " + finalCost + " 金币");
            return txResult;
        });
    }

    private long calculateExpandCost(int currentCount) {
        return 100L * (long) Math.pow(2, currentCount - DEFAULT_PLOTS);
    }

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
        plot.setGrowthStage(rs.getInt("growth_stage"));
        plot.setWaterLevel(rs.getInt("water_level"));
        plot.setFertilizerLevel(rs.getInt("fertilizer_level"));
        plot.setYield(rs.getInt("yield"));
        return plot;
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}