package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.game.entity.CropConfig;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.entity.SpiritualRoot;
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
    private static final Random random = new Random();

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    static {
        scheduler.scheduleAtFixedRate(FarmService::updateAllPlots, 1, 1, TimeUnit.SECONDS);
    }

    private double calculateSpiritualRootBonus(long playerId, CropConfig.ElementType cropElement) {
        com.mtxgdn.entity.Player player = playerService.getPlayerById(playerId);
        if (player == null || player.getSpiritualRoot() == null) {
            return 0;
        }
        
        SpiritualRoot root = player.getSpiritualRoot();
        double baseBonus = 0;
        
        switch (root) {
            case TRUE_FIVE_ELEMENTS -> baseBonus = 0.20;
            case QINGDI_WOOD -> baseBonus = cropElement == CropConfig.ElementType.WOOD ? 0.30 : 0.10;
            case XUANMING_WATER -> baseBonus = cropElement == CropConfig.ElementType.WATER ? 0.30 : 0.10;
            case LIHUO_FIRE -> baseBonus = cropElement == CropConfig.ElementType.FIRE ? 0.30 : 0.10;
            case HOUTU_EARTH -> baseBonus = cropElement == CropConfig.ElementType.EARTH ? 0.30 : 0.10;
            case TAIYI_GOLDEN -> baseBonus = cropElement == CropConfig.ElementType.METAL ? 0.30 : 0.10;
            case XUANBING_ICE -> baseBonus = cropElement == CropConfig.ElementType.ICE ? 0.25 : 0.05;
            case XUNFENG_WIND -> baseBonus = cropElement == CropConfig.ElementType.LIGHT ? 0.20 : 0.05;
            case JINGLEI_THUNDER -> baseBonus = cropElement == CropConfig.ElementType.FIRE ? 0.20 : 0.05;
            case GOLDEN_FIRE -> baseBonus = (cropElement == CropConfig.ElementType.METAL || 
                                               cropElement == CropConfig.ElementType.FIRE) ? 0.15 : 0.05;
            case WOODEN_WATER -> baseBonus = (cropElement == CropConfig.ElementType.WOOD || 
                                               cropElement == CropConfig.ElementType.WATER) ? 0.15 : 0.05;
            case EARTHEN_GOLD -> baseBonus = (cropElement == CropConfig.ElementType.EARTH || 
                                               cropElement == CropConfig.ElementType.METAL) ? 0.15 : 0.05;
            case FIRE_WOOD_EARTH -> baseBonus = (cropElement == CropConfig.ElementType.FIRE || 
                                                  cropElement == CropConfig.ElementType.WOOD || 
                                                  cropElement == CropConfig.ElementType.EARTH) ? 0.10 : 0.03;
            case GOLDEN_WATER_WOOD -> baseBonus = (cropElement == CropConfig.ElementType.METAL || 
                                                    cropElement == CropConfig.ElementType.WATER || 
                                                    cropElement == CropConfig.ElementType.WOOD) ? 0.10 : 0.03;
            case HONGMENG -> baseBonus = 0.50;
            default -> baseBonus = 0;
        }
        
        if (root.hasEffect(SpiritualRoot.SpecialEffect.ALL_EFFECTS_ENHANCE)) {
            baseBonus *= (1 + root.getEffectValue());
        }
        
        return baseBonus;
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

            double rootBonus = calculateSpiritualRootBonus(playerId, config.getElementType());
            Season currentSeason = Season.getCurrentSeason();
            double seasonModifier = currentSeason.getGrowthModifier(config.getElementType());

            double totalGrowthMultiplier = (1 + rootBonus) * seasonModifier;
            int adjustedGrowthSeconds = (int) (config.getGrowthSeconds() / totalGrowthMultiplier);
            int adjustedBaseYield = (int) (config.getBaseYield() * (1 + rootBonus));

            long now = System.currentTimeMillis();
            long harvestTime = now + (long) adjustedGrowthSeconds * 1000;

            String updateSql = """
                UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                    planted_time = ?, harvest_time = ?, growth_stage = ?,
                    water_level = ?, fertilizer_level = ?, yield = ?, root_bonus = ?, season_modifier = ?
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
                ps.setInt(9, Math.max(adjustedBaseYield, 1));
                ps.setDouble(10, rootBonus);
                ps.setDouble(11, seasonModifier);
                ps.setLong(12, playerId);
                ps.setInt(13, plotIndex);
                ps.executeUpdate();
            }

            Item seed = ItemRegistry.get(seedKey);
            String seedName = seed != null ? seed.getName() : config.getCropName();
            StringBuilder msg = new StringBuilder("已种植 " + seedName + "，预计 " + adjustedGrowthSeconds + " 秒后可收获");
            if (rootBonus > 0) {
                msg.append("（灵根加成 +").append((int)(rootBonus * 100)).append("%）");
            }
            if (seasonModifier > 1) {
                msg.append("（").append(currentSeason.getDisplayName()).append("加成 +").append((int)((seasonModifier - 1) * 100)).append("%）");
            } else if (seasonModifier < 1) {
                msg.append("（").append(currentSeason.getDisplayName()).append("减成 ").append((int)((seasonModifier - 1) * 100)).append("%）");
            }
            txResult.put("success", true);
            txResult.put("message", msg.toString());
            return txResult;
        });
    }

    private static final long WILTED_DEAD_SECONDS = 3600;
    private static final double PEST_CHANCE_PER_HOUR = 0.02;
    private static final long PEST_CHECK_INTERVAL = 60000;

    public Map<String, Object> water(long playerId, int plotIndex) {
        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state, water_level, wilted_time FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            int currentWater = 0;
            long wiltedTime = 0;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                        currentWater = rs.getInt("water_level");
                        wiltedTime = rs.getLong("wilted_time");
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

            if (FarmPlot.PlotState.WILTED.name().equals(currentState)) {
                long now = System.currentTimeMillis();
                if (wiltedTime > 0 && (now - wiltedTime) / 1000 > WILTED_DEAD_SECONDS) {
                    txResult.put("success", false);
                    txResult.put("message", "作物已枯萎过久，无法恢复，请清理地块重新种植");
                    return txResult;
                }
                String updateSql = "UPDATE farm_plots SET state = ?, water_level = ?, wilted_time = ? WHERE player_id = ? AND plot_index = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, FarmPlot.PlotState.GROWING.name());
                    ps.setInt(2, 50);
                    ps.setLong(3, 0);
                    ps.setLong(4, playerId);
                    ps.setInt(5, plotIndex);
                    ps.executeUpdate();
                }
                txResult.put("success", true);
                txResult.put("message", "浇水成功！枯萎作物已恢复，水分: 50%");
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
        return fertilize(playerId, plotIndex, "mtxgdn:low_grade_fertilizer");
    }

    public Map<String, Object> fertilize(long playerId, int plotIndex, String fertilizerKey) {
        Map<String, Object> result = new LinkedHashMap<>();

        int bonusAmount = getFertilizerBonus(fertilizerKey);
        if (bonusAmount == 0) {
            result.put("success", false);
            result.put("message", "无效的肥料类型");
            return result;
        }

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

            itemService.removeItem(conn, playerId, fertilizerKey, 1);

            int newFertilizer = Math.min(FERTILIZER_MAX, currentFertilizer + bonusAmount);
            String updateSql = "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, newFertilizer);
                ps.setLong(2, playerId);
                ps.setInt(3, plotIndex);
                ps.executeUpdate();
            }

            Item fertilizer = ItemRegistry.get(fertilizerKey);
            String fertilizerName = fertilizer != null ? fertilizer.getName() : "肥料";
            txResult.put("success", true);
            txResult.put("message", "施肥成功！使用 " + fertilizerName + "，肥力: " + newFertilizer + "%");
            return txResult;
        });
    }

    private int getFertilizerBonus(String fertilizerKey) {
        return switch (fertilizerKey) {
            case "mtxgdn:low_grade_fertilizer" -> 25;
            case "mtxgdn:mid_grade_fertilizer" -> 40;
            case "mtxgdn:high_grade_fertilizer" -> 60;
            default -> 0;
        };
    }

    public Map<String, Object> harvest(long playerId, int plotIndex) {
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
            
            StringBuilder msg = new StringBuilder("收获成功！获得 ");
            if (plot.getCropQuality() != null && plot.getCropQuality() != FarmPlot.CropQuality.COMMON) {
                msg.append("【").append(plot.getCropQuality().getDisplayName()).append("】");
            }
            msg.append(cropName).append(" x").append(yield);
            if (plot.getCropQuality() != null && plot.getCropQuality() != FarmPlot.CropQuality.COMMON) {
                msg.append("（品质加成 +").append((int)((plot.getCropQuality().getMultiplier() - 1) * 100)).append("%）");
            }
            
            txResult.put("success", true);
            txResult.put("message", msg.toString());
            txResult.put("yield", yield);
            return txResult;
        });
    }

    private int calculateYield(FarmPlot plot, CropConfig config) {
        int baseYield = plot.getYield();
        int waterBonus = (int) (baseYield * plot.getWaterLevel() / 100.0 * 0.5);
        int fertilizerBonus = (int) (baseYield * plot.getFertilizerLevel() / 100.0 * config.getFertilizerBonus() / 100.0);
        
        int pestPenalty = 0;
        switch (plot.getPestState()) {
            case MILD -> pestPenalty = (int) (baseYield * 0.2);
            case SEVERE -> pestPenalty = (int) (baseYield * 0.4);
            case DISEASE -> pestPenalty = (int) (baseYield * 0.3);
            default -> {}
        }
        
        int randomBonus = random.nextInt(3);
        int total = baseYield + waterBonus + fertilizerBonus + randomBonus - pestPenalty;
        
        if (plot.getCropQuality() != null) {
            total = (int) (total * plot.getCropQuality().getMultiplier());
        }
        
        return Math.max(1, Math.min(total, config.getMaxYield() * 2));
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
        String updateSql = "UPDATE farm_plots SET state = ?, growth_stage = ?, water_level = ?, wilted_time = ?, pest_state = ?, pest_time = ? WHERE id = ?";

        CropConfig config = CropConfig.get(plot.getSeedKey());
        if (config == null) return;

        int newWater = Math.max(0, plot.getWaterLevel() - WATER_DECREASE_PER_SECOND);
        FarmPlot.PestState newPestState = plot.getPestState();
        long newPestTime = plot.getPestTime();

        if (newWater <= 0) {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, FarmPlot.PlotState.WILTED.name());
                ps.setInt(2, plot.getGrowthStage());
                ps.setInt(3, 0);
                ps.setLong(4, plot.getWiltedTime() > 0 ? plot.getWiltedTime() : now);
                ps.setString(5, newPestState.name());
                ps.setLong(6, newPestTime);
                ps.setLong(7, plot.getId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            return;
        }

        if (now >= plot.getHarvestTime()) {
            FarmPlot.CropQuality quality = determineQuality(plot);
            String qualityUpdateSql = "UPDATE farm_plots SET state = ?, growth_stage = ?, water_level = ?, wilted_time = ?, pest_state = ?, pest_time = ?, crop_quality = ? WHERE id = ?";
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(qualityUpdateSql)) {
                ps.setString(1, FarmPlot.PlotState.READY.name());
                ps.setInt(2, config.getStages());
                ps.setInt(3, newWater);
                ps.setLong(4, 0);
                ps.setString(5, newPestState.name());
                ps.setLong(6, newPestTime);
                ps.setString(7, quality.name());
                ps.setLong(8, plot.getId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            return;
        }

        checkPestAttack(plot, now);

        long elapsed = now - plot.getPlantedTime();
        long total = plot.getHarvestTime() - plot.getPlantedTime();
        int stage = (int) ((elapsed * config.getStages()) / total) + 1;
        stage = Math.min(stage, config.getStages());

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, FarmPlot.PlotState.GROWING.name());
            ps.setInt(2, stage);
            ps.setInt(3, newWater);
            ps.setLong(4, 0);
            ps.setString(5, newPestState.name());
            ps.setLong(6, newPestTime);
            ps.setLong(7, plot.getId());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private static FarmPlot.CropQuality determineQuality(FarmPlot plot) {
        double qualityScore = 0;
        
        if (plot.getWaterLevel() >= 80) qualityScore += 15;
        else if (plot.getWaterLevel() >= 50) qualityScore += 5;
        
        if (plot.getFertilizerLevel() >= 80) qualityScore += 20;
        else if (plot.getFertilizerLevel() >= 50) qualityScore += 10;
        
        if (plot.getPestState() == FarmPlot.PestState.CLEAN) qualityScore += 25;
        else if (plot.getPestState() == FarmPlot.PestState.MILD) qualityScore += 5;
        
        if (plot.getRootBonus() >= 0.2) qualityScore += 10;
        if (plot.getSeasonModifier() > 1.1) qualityScore += 10;
        
        double rand = random.nextDouble() * 100;
        
        if (rand <= qualityScore * 0.3) {
            return FarmPlot.CropQuality.EXCELLENT;
        } else if (rand <= qualityScore * 0.7) {
            return FarmPlot.CropQuality.GOOD;
        } else {
            return FarmPlot.CropQuality.COMMON;
        }
    }

    private static void checkPestAttack(FarmPlot plot, long now) {
        if (plot.getPestState() != FarmPlot.PestState.CLEAN) {
            if (now - plot.getPestTime() > 1800000) {
                upgradePestState(plot);
            }
            return;
        }

        if (now - plot.getPlantedTime() > 300000) {
            if (random.nextDouble() < PEST_CHANCE_PER_HOUR / 60) {
                triggerPestAttack(plot, now);
            }
        }
    }

    private static void triggerPestAttack(FarmPlot plot, long now) {
        FarmPlot.PestState newState = random.nextDouble() < 0.7 ? 
            FarmPlot.PestState.MILD : FarmPlot.PestState.DISEASE;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE id = ?")) {
            ps.setString(1, newState.name());
            ps.setLong(2, now);
            ps.setLong(3, plot.getId());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private static void upgradePestState(FarmPlot plot) {
        FarmPlot.PestState newState = switch (plot.getPestState()) {
            case MILD -> FarmPlot.PestState.SEVERE;
            case DISEASE -> FarmPlot.PestState.SEVERE;
            default -> plot.getPestState();
        };
        if (newState != plot.getPestState()) {
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE id = ?")) {
                ps.setString(1, newState.name());
                ps.setLong(2, System.currentTimeMillis());
                ps.setLong(3, plot.getId());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    public Map<String, Object> clearPlot(long playerId, int plotIndex) {
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

    public Map<String, Object> usePesticide(long playerId, int plotIndex) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (itemService.getItemCount(playerId, "mtxgdn:pesticide") < 1) {
            result.put("success", false);
            result.put("message", "杀虫剂不足");
            return result;
        }

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();

            String checkSql = "SELECT state, pest_state FROM farm_plots WHERE player_id = ? AND plot_index = ? FOR UPDATE";
            String currentState = null;
            String pestState = null;
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, plotIndex);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        currentState = rs.getString("state");
                        pestState = rs.getString("pest_state");
                    }
                }
            } catch (SQLException e) {
                txResult.put("success", false);
                txResult.put("message", "查询地块失败");
                return txResult;
            }

            if (currentState == null) {
                txResult.put("success", false);
                txResult.put("message", "地块不存在");
                return txResult;
            }

            if ("CLEAN".equals(pestState)) {
                txResult.put("success", false);
                txResult.put("message", "地块没有病虫害");
                return txResult;
            }

            itemService.removeItem(playerId, "mtxgdn:pesticide", 1);

            String updateSql = "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE player_id = ? AND plot_index = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, FarmPlot.PestState.CLEAN.name());
                ps.setLong(2, 0);
                ps.setLong(3, playerId);
                ps.setInt(4, plotIndex);
                ps.executeUpdate();
            } catch (SQLException e) {
                txResult.put("success", false);
                txResult.put("message", "使用杀虫剂失败");
                return txResult;
            }

            txResult.put("success", true);
            txResult.put("message", "已使用杀虫剂，病虫害已清除");
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

    public Map<String, Object> waterAll(long playerId) {
        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();
            String sql = "SELECT plot_index, state, water_level FROM farm_plots WHERE player_id = ? AND state != 'EMPTY' FOR UPDATE";
            int wateredCount = 0;
            int wiltedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String state = rs.getString("state");
                        int waterLevel = rs.getInt("water_level");
                        int plotIndex = rs.getInt("plot_index");

                        if ("WILTED".equals(state)) {
                            long now = System.currentTimeMillis();
                            long wiltedTime = getWiltedTime(conn, playerId, plotIndex);
                            if (wiltedTime > 0 && (now - wiltedTime) / 1000 <= WILTED_DEAD_SECONDS) {
                                String updateSql = "UPDATE farm_plots SET state = ?, water_level = ?, wilted_time = ? WHERE player_id = ? AND plot_index = ?";
                                try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                                    ups.setString(1, FarmPlot.PlotState.GROWING.name());
                                    ups.setInt(2, 50);
                                    ups.setLong(3, 0);
                                    ups.setLong(4, playerId);
                                    ups.setInt(5, plotIndex);
                                    ups.executeUpdate();
                                    wiltedCount++;
                                }
                            }
                        } else if (waterLevel < WATER_MAX) {
                            int newWater = Math.min(WATER_MAX, waterLevel + 30);
                            String updateSql = "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?";
                            try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                                ups.setInt(1, newWater);
                                ups.setLong(2, playerId);
                                ups.setInt(3, plotIndex);
                                ups.executeUpdate();
                                wateredCount++;
                            }
                        }
                    }
                }
            }

            StringBuilder msg = new StringBuilder();
            if (wateredCount > 0) msg.append("已为 ").append(wateredCount).append(" 块地块浇水，");
            if (wiltedCount > 0) msg.append("已复活 ").append(wiltedCount).append(" 块枯萎作物，");
            if (msg.length() > 0) {
                msg.setLength(msg.length() - 1);
                txResult.put("success", true);
                txResult.put("message", msg.toString());
            } else {
                txResult.put("success", false);
                txResult.put("message", "没有需要浇水的地块");
            }
            return txResult;
        });
    }

    public Map<String, Object> fertilizeAll(long playerId) {
        return fertilizeAll(playerId, "mtxgdn:low_grade_fertilizer");
    }

    public Map<String, Object> fertilizeAll(long playerId, String fertilizerKey) {
        int bonusAmount = getFertilizerBonus(fertilizerKey);
        if (bonusAmount == 0) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "无效的肥料类型");
            return result;
        }

        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();
            String sql = "SELECT plot_index, fertilizer_level FROM farm_plots WHERE player_id = ? AND state != 'EMPTY' AND fertilizer_level < ? FOR UPDATE";
            int fertilizedCount = 0;
            List<Integer> plotsToFertilize = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, FERTILIZER_MAX);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plotsToFertilize.add(rs.getInt("plot_index"));
                    }
                }
            }

            if (plotsToFertilize.isEmpty()) {
                txResult.put("success", false);
                txResult.put("message", "没有需要施肥的地块");
                return txResult;
            }

            long fertilizerCount = itemService.getItemCount(playerId, fertilizerKey);
            if (fertilizerCount < plotsToFertilize.size()) {
                Item fertilizer = ItemRegistry.get(fertilizerKey);
                String fertilizerName = fertilizer != null ? fertilizer.getName() : "肥料";
                txResult.put("success", false);
                txResult.put("message", fertilizerName + "不足，需要 " + plotsToFertilize.size() + " 个");
                return txResult;
            }

            itemService.removeItem(conn, playerId, fertilizerKey, plotsToFertilize.size());

            for (int plotIndex : plotsToFertilize) {
                String updateSql = "UPDATE farm_plots SET fertilizer_level = LEAST(fertilizer_level + ?, ?) WHERE player_id = ? AND plot_index = ?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setInt(1, bonusAmount);
                    ps.setInt(2, FERTILIZER_MAX);
                    ps.setLong(3, playerId);
                    ps.setInt(4, plotIndex);
                    ps.executeUpdate();
                    fertilizedCount++;
                }
            }

            Item fertilizer = ItemRegistry.get(fertilizerKey);
            String fertilizerName = fertilizer != null ? fertilizer.getName() : "肥料";
            txResult.put("success", true);
            txResult.put("message", "已为 " + fertilizedCount + " 块地块施肥，消耗 " + fertilizerName + " x" + fertilizedCount);
            return txResult;
        });
    }

    public Map<String, Object> harvestAll(long playerId) {
        return DatabaseManager.runTransaction(conn -> {
            Map<String, Object> txResult = new LinkedHashMap<>();
            String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND state = 'READY' FOR UPDATE";
            Map<String, Integer> harvestResults = new LinkedHashMap<>();
            int harvestedCount = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        FarmPlot plot = mapPlot(rs);
                        CropConfig config = CropConfig.get(plot.getSeedKey());
                        if (config != null) {
                            int yield = calculateYield(plot, config);
                            itemService.addItem(conn, playerId, plot.getCropKey(), yield);
                            harvestResults.merge(plot.getCropKey(), yield, Integer::sum);

                            String resetSql = "UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?, planted_time = ?, harvest_time = ?, growth_stage = ?, water_level = ?, fertilizer_level = ?, yield = ? WHERE player_id = ? AND plot_index = ?";
                            try (PreparedStatement ups = conn.prepareStatement(resetSql)) {
                                ups.setString(1, FarmPlot.PlotState.EMPTY.name());
                                ups.setString(2, null);
                                ups.setString(3, null);
                                ups.setLong(4, 0);
                                ups.setLong(5, 0);
                                ups.setInt(6, 0);
                                ups.setInt(7, WATER_MAX);
                                ups.setInt(8, 0);
                                ups.setInt(9, 0);
                                ups.setLong(10, playerId);
                                ups.setInt(11, plot.getPlotIndex());
                                ups.executeUpdate();
                            }
                            harvestedCount++;
                        }
                    }
                }
            }

            if (harvestedCount == 0) {
                txResult.put("success", false);
                txResult.put("message", "没有可收获的作物");
                return txResult;
            }

            StringBuilder msg = new StringBuilder("收获成功！获得：");
            for (Map.Entry<String, Integer> entry : harvestResults.entrySet()) {
                Item crop = ItemRegistry.get(entry.getKey());
                String cropName = crop != null ? crop.getName() : entry.getKey();
                msg.append(cropName).append(" x").append(entry.getValue()).append("，");
            }
            msg.setLength(msg.length() - 1);

            txResult.put("success", true);
            txResult.put("message", msg.toString());
            txResult.put("harvestedCount", harvestedCount);
            txResult.put("details", harvestResults);
            return txResult;
        });
    }

    private long getWiltedTime(Connection conn, long playerId, int plotIndex) throws SQLException {
        String sql = "SELECT wilted_time FROM farm_plots WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("wilted_time");
                }
            }
        }
        return 0;
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
        return plot;
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}