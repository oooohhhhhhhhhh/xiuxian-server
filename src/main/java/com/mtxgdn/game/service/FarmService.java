package com.mtxgdn.game.service;

import com.mtxgdn.common.service.ServiceRegistry;

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
import com.mtxgdn.i18n.I18n;

public class FarmService {

    private static final GameLogger LOG = GameLogger.getLogger(FarmService.class);
    private static final int DEFAULT_PLOTS = 4;
    private static final int MAX_PLOTS = 12;
    private static final int WATER_MAX = 100;
    private static final int FERTILIZER_MAX = 100;
    /** 水分每秒消耗 0.1%（约 17 分钟才能从满水降到枯萎线），适合 QQ 群聊的异步节奏 */
    private static final double WATER_DECREASE_PER_SECOND = 0.1;
    /** 枯萎后 6 小时死亡（原 1 小时，给予玩家恢复机会） */
    private static final long WILTED_DEAD_SECONDS = 6 * 3600;
    private static final double PEST_CHANCE_PER_HOUR = 0.02;
    /** 种植后 5 分钟内不检测病虫害（与旧每秒推进实现一致） */
    private static final long PEST_GRACE_MS = 5 * 60 * 1000L;
    /** 病虫害出现后 30 分钟未处理则升级为严重 */
    private static final long PEST_UPGRADE_MS = 30 * 60 * 1000L;

    /** 行锁子句：MySQL 使用 FOR UPDATE，SQLite 不支持则留空（事务内写锁已保证一致性） */
    private static String lockClause() {
        return DatabaseManager.isSqlite() ? "" : " FOR UPDATE";
    }

    private final PlayerService playerService = ServiceRegistry.getPlayerService();
    private final ItemService itemService = ServiceRegistry.getItemService();
    private static final Random random = new Random();

    /**
     * 农田不再有后台推进线程。
     *
     * <p>改造前：{@code scheduleAtFixedRate(updateAllPlots, 1, 1, SECONDS)} 每秒把
     * {@code SELECT * FROM farm_plots WHERE state IN ('PLANTED','GROWING')} 的全部地块
     * 推进一格——代价与"全服正在生长的地块数"成正比，而绝大多数玩家当时并不在看农田，
     * 属于纯无用功；地块越多、tick 越密，数据库压力越大。</p>
     *
     * <p>改造后：生长状态改由 {@link #settlePlayerPlots} 在玩家真正访问农田时
     * 按时间差一次性结算，读取即正确，无需任何后台线程。</p>
     *
     * <p>本方法保留为空实现，仅为兼容启动器与测试里既有的清理调用
     * （{@code ServerShutdownHook} / {@code MultiPlayerFullTest}）。</p>
     */
    public static void shutdownScheduler() {
        // 惰性结算后没有后台调度器需要关闭
    }

    private double calculateSpiritualRootBonus(long playerId, CropConfig.ElementType cropElement) {
        com.mtxgdn.entity.Player player = playerService.getPlayerById(playerId);
        if (player == null || player.getSpiritualRoot() == null) {
            return 0;
        }

        SpiritualRoot root = player.getSpiritualRoot();
        double baseBonus = getSpiritualRootBonus(root, cropElement);

        // 鸿蒙“所有灵根效果增强 50%”：经统一入口 effectEnhancementMultiplier() 放大（鸿蒙 ×1.5，其余 ×1.0）
        baseBonus *= root.effectEnhancementMultiplier();

        return baseBonus;
    }

    private double getSpiritualRootBonus(SpiritualRoot root, CropConfig.ElementType cropElement) {
        return switch (root) {
            case TRUE_FIVE_ELEMENTS -> 0.20;
            case QINGDI_WOOD -> cropElement == CropConfig.ElementType.WOOD ? 0.30 : 0.10;
            case XUANMING_WATER -> cropElement == CropConfig.ElementType.WATER ? 0.30 : 0.10;
            case LIHUO_FIRE -> cropElement == CropConfig.ElementType.FIRE ? 0.30 : 0.10;
            case HOUTU_EARTH -> cropElement == CropConfig.ElementType.EARTH ? 0.30 : 0.10;
            case TAIYI_GOLDEN -> cropElement == CropConfig.ElementType.METAL ? 0.30 : 0.10;
            case XUANBING_ICE -> cropElement == CropConfig.ElementType.ICE ? 0.25 : 0.05;
            case XUNFENG_WIND -> cropElement == CropConfig.ElementType.LIGHT ? 0.20 : 0.05;
            case JINGLEI_THUNDER -> cropElement == CropConfig.ElementType.FIRE ? 0.20 : 0.05;
            case GOLDEN_FIRE -> (cropElement == CropConfig.ElementType.METAL || cropElement == CropConfig.ElementType.FIRE) ? 0.15 : 0.05;
            case WOODEN_WATER -> (cropElement == CropConfig.ElementType.WOOD || cropElement == CropConfig.ElementType.WATER) ? 0.15 : 0.05;
            case EARTHEN_GOLD -> (cropElement == CropConfig.ElementType.EARTH || cropElement == CropConfig.ElementType.METAL) ? 0.15 : 0.05;
            case FIRE_WOOD_EARTH -> (cropElement == CropConfig.ElementType.FIRE || cropElement == CropConfig.ElementType.WOOD || cropElement == CropConfig.ElementType.EARTH) ? 0.10 : 0.03;
            case GOLDEN_WATER_WOOD -> (cropElement == CropConfig.ElementType.METAL || cropElement == CropConfig.ElementType.WATER || cropElement == CropConfig.ElementType.WOOD) ? 0.10 : 0.03;
            case HONGMENG -> 0.50;
            default -> 0;
        };
    }

    public List<FarmPlot> getPlots(long playerId) {
        initPlots(playerId);
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? ORDER BY plot_index";
        List<FarmPlot> result = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // 读取即结算：把上次访问到现在的生长/枯萎/虫害一次性推进（取代原来的每秒全表扫描）
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
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
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        CropConfig config = CropConfig.get(seedKey);
        if (config == null) {
            return createResult(false, I18n.t("farm.hb16af8bc"));
        }

        return DatabaseManager.runTransaction(conn -> {
            // 先结算：地块可能在这段时间里已经枯萎，必须先落到最新状态再校验
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            if (itemService.getItemCount(conn, playerId, seedKey) < 1) {
                return createResult(false, I18n.t("farm.h41355438", getItemName(seedKey)));
            }

            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }
            if (plot.getState() != FarmPlot.PlotState.EMPTY) {
                return createResult(false, I18n.t("farm.h920b6224"));
            }

            itemService.removeItem(conn, playerId, seedKey, 1);

            double rootBonus = calculateSpiritualRootBonus(playerId, config.getElementType());
            Season currentSeason = SeasonService.getCurrentSeason();
            double seasonModifier = SeasonService.getGrowthModifier(config.getElementType());

            double totalGrowthMultiplier = (1 + rootBonus) * seasonModifier;
            int adjustedGrowthSeconds = (int) (config.getGrowthSeconds() / totalGrowthMultiplier);
            int adjustedBaseYield = (int) (config.getBaseYield() * (1 + rootBonus));

            long now = System.currentTimeMillis();
            long harvestTime = now + (long) adjustedGrowthSeconds * 1000;

            updatePlotToPlanted(conn, plot, config, seedKey, now, harvestTime, adjustedBaseYield, rootBonus, seasonModifier);

            StringBuilder msg = new StringBuilder("已种植 " + getItemName(seedKey) + "，预计 " + adjustedGrowthSeconds + " 秒后可收获");
            appendBonusInfo(msg, rootBonus, seasonModifier, currentSeason);
            if (seasonModifier < 1) {
                msg.append("（当前" + currentSeason.getDisplayName() + "不利于此作物生长，可考虑更换种子）");
            }

            Map<String, Object> result = createResult(true, msg.toString());
            result.put("harvestTime", harvestTime);
            return result;
        });
    }

    public Map<String, Object> water(long playerId, int plotIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return DatabaseManager.runTransaction(conn -> {
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }

            if (plot.getState() == FarmPlot.PlotState.EMPTY) {
                return createResult(false, I18n.t("farm.h915aaf14"));
            }

            if (plot.getState() == FarmPlot.PlotState.WILTED) {
                return handleWiltedPlotWatering(conn, plot, plotIndex);
            }

            if (plot.getWaterLevel() >= WATER_MAX) {
                return createResult(false, I18n.t("farm.hbd6a293c"));
            }

            int newWater = Math.min(WATER_MAX, plot.getWaterLevel() + 30);
            updateWaterLevel(conn, playerId, plotIndex, newWater);

            return createResult(true, I18n.t("farm.haf577243", newWater));
        });
    }

    private Map<String, Object> handleWiltedPlotWatering(Connection conn, FarmPlot plot, int plotIndex) {
        long now = System.currentTimeMillis();
        if (plot.getWiltedTime() > 0 && (now - plot.getWiltedTime()) / 1000 > WILTED_DEAD_SECONDS) {
            return createResult(false, I18n.t("farm.h5433c430"));
        }
        String updateSql = "UPDATE farm_plots SET state = ?, water_level = ?, wilted_time = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, FarmPlot.PlotState.GROWING.name());
            ps.setInt(2, 50);
            ps.setLong(3, 0);
            ps.setLong(4, plot.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            return createResult(false, I18n.t("farm.h0c3d0718"));
        }
        return createResult(true, I18n.t("farm.h88221924"));
    }

    public Map<String, Object> fertilize(long playerId, int plotIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return fertilize(playerId, plotIndex, "mtxgdn:low_grade_fertilizer");
    }

    public Map<String, Object> fertilize(long playerId, int plotIndex, String fertilizerKey) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        int bonusAmount = getFertilizerBonus(fertilizerKey);
        if (bonusAmount == 0) {
            return createResult(false, I18n.t("farm.h634a5aa8"));
        }

        if (itemService.getItemCount(playerId, fertilizerKey) < 1) {
            return createResult(false, I18n.t("farm.hbf7381d4", getItemName(fertilizerKey)));
        }

        return DatabaseManager.runTransaction(conn -> {
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }

            if (plot.getState() == FarmPlot.PlotState.EMPTY) {
                return createResult(false, I18n.t("farm.he9f89b8c"));
            }

            if (plot.getFertilizerLevel() >= FERTILIZER_MAX) {
                return createResult(false, I18n.t("farm.hf20b7301"));
            }

            itemService.removeItem(conn, playerId, fertilizerKey, 1);

            int newFertilizer = Math.min(FERTILIZER_MAX, plot.getFertilizerLevel() + bonusAmount);
            updateFertilizerLevel(conn, playerId, plotIndex, newFertilizer);

            return createResult(true, I18n.t("farm.h86b7984d", getItemName(fertilizerKey), newFertilizer));
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
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return DatabaseManager.runTransaction(conn -> {
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }

            if (plot.getState() != FarmPlot.PlotState.READY) {
                return createResult(false, I18n.t("farm.h10de2898"));
            }

            CropConfig config = CropConfig.get(plot.getSeedKey());
            if (config == null) {
                return createResult(false, I18n.t("farm.h4b6fa38c"));
            }

            int yield = calculateYield(plot, config);
            itemService.addItem(conn, playerId, plot.getCropKey(), yield);
            resetPlot(conn, playerId, plotIndex);

            StringBuilder msg = new StringBuilder("收获成功！获得 ");
            appendQualityInfo(msg, plot.getCropQuality());
            msg.append(getItemName(plot.getCropKey())).append(" x").append(yield);
            appendQualityBonus(msg, plot.getCropQuality());

            Map<String, Object> result = createResult(true, msg.toString());
            result.put("yield", yield);
            return result;
        });
    }

    private int calculateYield(FarmPlot plot, CropConfig config) {
        int baseYield = plot.getYield();
        int waterBonus = (int) (baseYield * plot.getWaterLevel() / 100.0 * 0.5);
        int fertilizerBonus = (int) (baseYield * plot.getFertilizerLevel() / 100.0 * config.getFertilizerBonus() / 100.0);

        int pestPenalty = calculatePestPenalty(plot, baseYield);

        int randomBonus = random.nextInt(3);
        int total = baseYield + waterBonus + fertilizerBonus + randomBonus - pestPenalty;

        if (plot.getCropQuality() != null) {
            total = (int) (total * plot.getCropQuality().getMultiplier());
        }

        return Math.max(1, Math.min(total, config.getMaxYield() * 2));
    }

    private int calculatePestPenalty(FarmPlot plot, int baseYield) {
        return switch (plot.getPestState()) {
            case MILD -> (int) (baseYield * 0.2);
            case SEVERE -> (int) (baseYield * 0.4);
            case DISEASE -> (int) (baseYield * 0.3);
            default -> 0;
        };
    }

    public Map<String, Object> clearPlot(long playerId, int plotIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return DatabaseManager.runTransaction(conn -> {
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }

            if (plot.getState() == FarmPlot.PlotState.EMPTY) {
                return createResult(false, I18n.t("farm.h1cca0008"));
            }

            resetPlot(conn, playerId, plotIndex);
            return createResult(true, I18n.t("farm.h21e7e68c"));
        });
    }

    public Map<String, Object> usePesticide(long playerId, int plotIndex) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        if (itemService.getItemCount(playerId, "mtxgdn:pesticide") < 1) {
            return createResult(false, I18n.t("farm.h36b84c89"));
        }

        return DatabaseManager.runTransaction(conn -> {
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            FarmPlot plot = getPlotForUpdate(conn, playerId, plotIndex);
            if (plot == null) {
                return createResult(false, I18n.t("farm.hd8a8fe7c"));
            }

            if (plot.getPestState() == FarmPlot.PestState.CLEAN) {
                return createResult(false, I18n.t("farm.h49d89808"));
            }

            itemService.removeItem(conn, playerId, "mtxgdn:pesticide", 1);
            clearPestState(conn, playerId, plotIndex);

            return createResult(true, I18n.t("farm.h95625008"));
        });
    }

    public Map<String, Object> expandPlot(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        int currentCount = countPlayerPlots(playerId);

        if (currentCount >= MAX_PLOTS) {
            return createResult(false, I18n.t("farm.hf5b0e6f0"));
        }

        long cost = calculateExpandCost(currentCount);
        com.mtxgdn.entity.Player player = playerService.getPlayerById(playerId);
        if (player == null || player.getGold() < cost) {
            return createResult(false, I18n.t("farm.ha28ab727", cost));
        }

        final int plotIndex = currentCount;
        final long finalCost = cost;

        return DatabaseManager.runTransaction(conn -> {
            playerService.addGold(conn, playerId, -finalCost);
            createNewPlot(conn, playerId, plotIndex);
            return createResult(true, I18n.t("farm.hac3f3c60", finalCost));
        });
    }

    public Map<String, Object> waterAll(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return DatabaseManager.runTransaction(conn -> {
            // 先结算：枯萎地块要先判定死亡时限，成熟地块要先变成 READY
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            String sql = "SELECT plot_index, state, water_level, wilted_time FROM farm_plots WHERE player_id = ? AND state != 'EMPTY'" + lockClause();
            int wateredCount = 0;
            int wiltedCount = 0;
            long now = System.currentTimeMillis();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String state = rs.getString("state");
                        int waterLevel = rs.getInt("water_level");
                        int plotIndex = rs.getInt("plot_index");

                        if ("WILTED".equals(state)) {
                            long wiltedTime = rs.getLong("wilted_time");
                            if (wiltedTime > 0 && (now - wiltedTime) / 1000 <= WILTED_DEAD_SECONDS) {
                                updatePlotState(conn, playerId, plotIndex, FarmPlot.PlotState.GROWING.name());
                                updateWaterLevel(conn, playerId, plotIndex, 50);
                                resetWiltedTime(conn, playerId, plotIndex);
                                wiltedCount++;
                            }
                        } else if (waterLevel < WATER_MAX) {
                            int newWater = Math.min(WATER_MAX, waterLevel + 30);
                            updateWaterLevel(conn, playerId, plotIndex, newWater);
                            wateredCount++;
                        }
                    }
                }
            } catch (SQLException e) {
                return createResult(false, I18n.t("farm.he10fe06e"));
            }

            StringBuilder msg = new StringBuilder();
            if (wateredCount > 0) msg.append("已为 ").append(wateredCount).append(" 块地块浇水");
            if (wiltedCount > 0) {
                if (msg.length() > 0) msg.append("，");
                msg.append("已复活 ").append(wiltedCount).append(" 块枯萎作物");
            }

            if (msg.length() > 0) {
                return createResult(true, msg.toString());
            } else {
                return createResult(false, I18n.t("farm.h9778e920"));
            }
        });
    }

    public Map<String, Object> fertilizeAll(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return fertilizeAll(playerId, "mtxgdn:low_grade_fertilizer");
    }

    public Map<String, Object> fertilizeAll(long playerId, String fertilizerKey) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        int bonusAmount = getFertilizerBonus(fertilizerKey);
        if (bonusAmount == 0) {
            return createResult(false, I18n.t("farm.h634a5aa8"));
        }

        return DatabaseManager.runTransaction(conn -> {
            // 先结算：确保待施肥地块的状态是最新的
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            String sql = "SELECT plot_index, fertilizer_level FROM farm_plots WHERE player_id = ? AND state != 'EMPTY' AND fertilizer_level < ?" + lockClause();
            List<Integer> plotsToFertilize = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                ps.setInt(2, FERTILIZER_MAX);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plotsToFertilize.add(rs.getInt("plot_index"));
                    }
                }
            } catch (SQLException e) {
                return createResult(false, I18n.t("farm.h47064598"));
            }

            if (plotsToFertilize.isEmpty()) {
                return createResult(false, I18n.t("farm.h231f62ed"));
            }

            long fertilizerCount = itemService.getItemCount(playerId, fertilizerKey);
            if (fertilizerCount < plotsToFertilize.size()) {
                return createResult(false, I18n.t("farm.fertilizer_not_enough",
                        getItemName(fertilizerKey), plotsToFertilize.size()));
            }

            itemService.removeItem(conn, playerId, fertilizerKey, plotsToFertilize.size());

            String updateSql = DatabaseManager.isSqlite()
                    ? "UPDATE farm_plots SET fertilizer_level = MIN(fertilizer_level + ?, ?) WHERE player_id = ? AND plot_index = ?"
                    : "UPDATE farm_plots SET fertilizer_level = LEAST(fertilizer_level + ?, ?) WHERE player_id = ? AND plot_index = ?";

            int fertilizedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                for (int plotIndex : plotsToFertilize) {
                    ps.setInt(1, bonusAmount);
                    ps.setInt(2, FERTILIZER_MAX);
                    ps.setLong(3, playerId);
                    ps.setInt(4, plotIndex);
                    ps.executeUpdate();
                    fertilizedCount++;
                }
            } catch (SQLException e) {
                return createResult(false, I18n.t("farm.h121b7308"));
            }

            return createResult(true, I18n.t("farm.h0ff61159", fertilizedCount, getItemName(fertilizerKey), fertilizedCount));
        });
    }

    public Map<String, Object> harvestAll(long playerId) {
        com.mtxgdn.playerdata.PlayerDataService.markDirty(playerId);
        return DatabaseManager.runTransaction(conn -> {
            // 先结算：本来就要靠定时器把成熟地块刷成 READY，现在改在收获前一次性结算
            settlePlayerPlots(conn, playerId, System.currentTimeMillis());
            String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND state = 'READY'" + lockClause();
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
                            resetPlot(conn, playerId, plot.getPlotIndex());
                            harvestedCount++;
                        }
                    }
                }
            } catch (SQLException e) {
                return createResult(false, I18n.t("farm.h24d616f8"));
            }

            if (harvestedCount == 0) {
                return createResult(false, I18n.t("farm.h88a44f1f"));
            }

            StringBuilder msg = new StringBuilder("收获成功！获得：");
            for (Map.Entry<String, Integer> entry : harvestResults.entrySet()) {
                msg.append(getItemName(entry.getKey())).append(" x").append(entry.getValue()).append("，");
            }
            msg.setLength(msg.length() - 1);

            Map<String, Object> result = createResult(true, msg.toString());
            result.put("harvestedCount", harvestedCount);
            result.put("details", harvestResults);
            return result;
        });
    }

    /**
     * 惰性结算：把该玩家所有「未完结」地块的状态一次性推进到 {@code now}。
     *
     * <p>农田状态本就可以由时间戳推导（种植时间、成熟时间、上次结算时间），因此不需要
     * 任何后台线程持续推动——只在玩家真正访问农田时，按 {@code now - settled_time}
     * 折算这段时间内应发生的变化即可。</p>
     *
     * <p>与旧「每秒推进一格」的等价性：</p>
     * <ul>
     *   <li><b>水分</b>：同为线性衰减，跨过 0 即枯萎；{@code wilted_time} 取精确穿零时刻
     *       （旧实现只能取到那一秒的 tick 时间，本实现更精确但语义相同）。</li>
     *   <li><b>病虫害</b>：旧实现每秒掷一次骰子，{@code n} 秒区间的累计概率为
     *       {@code 1-(1-p)^n}——本实现直接计算该值，统计期望一致；附带修正了旧实现
     *       "服务器停机期间不生病"的隐性偏差。</li>
     *   <li><b>成熟</b>：{@code now} 越过 {@code harvest_time} 即可收获，品质在这一刻
     *       掷定并立即落库，此后不再重算。</li>
     *   <li><b>生长阶段</b>：由 {@code planted_time}/{@code harvest_time} 直接推导，本身无状态。</li>
     * </ul>
     *
     * <p>幂等性：结算后立刻把 {@code settled_time} 推进到 {@code now}，同一区间不会被重复计算；
     * 同一秒内重复访问因 {@code elapsedSeconds <= 0} 直接返回，不产生任何写入。</p>
     *
     * @param conn     调用方的事务连接，结算与业务写入处于同一事务
     * @param playerId 玩家 ID
     * @param now      结算基准时刻（毫秒）
     */
    private void settlePlayerPlots(Connection conn, long playerId, long now) {
        // 内置模块门控：农场模块停用后不推进生长（与旧后台任务的门控语义一致）
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("farm")) return;

        String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND state IN ('PLANTED', 'GROWING')";
        List<FarmPlot> plots = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plots.add(mapPlot(rs));
                }
            }
        } catch (SQLException e) {
            LOG.error("农田惰性结算读取失败 player=" + playerId, e);
            return;
        }

        for (FarmPlot plot : plots) {
            try {
                settlePlot(conn, plot, now);
            } catch (SQLException e) {
                // 单块地块失败不应影响该玩家其余地块
                LOG.error("农田惰性结算失败 plot=" + plot.getId(), e);
            }
        }
    }

    /** 结算单块地块（仅在 PLANTED/GROWING 状态下调用）。 */
    private void settlePlot(Connection conn, FarmPlot plot, long now) throws SQLException {
        CropConfig config = CropConfig.get(plot.getSeedKey());
        if (config == null) return;

        long since = plot.getPlantedTime() > 0 ? plot.getPlantedTime() : now;
        if (plot.getSettledTime() > since) since = plot.getSettledTime();
        // 时钟回拨保护：基准点晚于 now 时按 now 处理，绝不产生"负流逝"导致水分回升
        if (since > now) since = now;

        long elapsedSeconds = (now - since) / 1000;
        if (elapsedSeconds <= 0) return;

        // ---- 1. 水分衰减：跨过 0 即枯萎，枯萎时刻取精确穿零点 ----
        double water = plot.getWaterLevel();
        if (water <= 0) {
            plot.setState(FarmPlot.PlotState.WILTED);
            plot.setWaterLevel(0);
            if (plot.getWiltedTime() <= 0) plot.setWiltedTime(since);
            plot.setSettledTime(now);
            persistSettledPlot(conn, plot);
            return;
        }

        long secondsToDry = (long) Math.ceil(water / WATER_DECREASE_PER_SECOND);
        if (elapsedSeconds >= secondsToDry) {
            plot.setState(FarmPlot.PlotState.WILTED);
            plot.setWaterLevel(0);
            plot.setWiltedTime(since + secondsToDry * 1000L);
            plot.setSettledTime(now);
            persistSettledPlot(conn, plot);
            return;
        }
        int newWater = (int) Math.max(1, Math.round(water - WATER_DECREASE_PER_SECOND * elapsedSeconds));

        // ---- 2. 成熟判定：先于病虫害；成熟后水分与品质冻结（与旧实现一致）----
        if (now >= plot.getHarvestTime()) {
            // 注意顺序：品质必须在改写水分之前掷定。determineQuality 会按水分高低计分
            // （>=80 加 15 分），旧实现也是"读旧水分 -> 掷品质 -> 再写入新水分"，
            // 先降水分再掷会让卡在阈值上的地块凭空掉一档品质。
            FarmPlot.CropQuality quality = determineQuality(plot);
            plot.setState(FarmPlot.PlotState.READY);
            plot.setGrowthStage(config.getStages());
            plot.setWaterLevel(newWater);
            plot.setWiltedTime(0);
            plot.setCropQuality(quality); // 只在此刻掷一次，随本次结算落库
            plot.setSettledTime(now);
            persistSettledPlot(conn, plot);
            return;
        }

        // ---- 3. 病虫害 ----
        settlePest(plot, since, elapsedSeconds, now);

        // ---- 4. 生长阶段 ----
        plot.setState(FarmPlot.PlotState.GROWING);
        plot.setGrowthStage(computeStage(plot, config, now));
        plot.setWaterLevel(newWater);
        plot.setWiltedTime(0);
        plot.setSettledTime(now);
        persistSettledPlot(conn, plot);
    }

    /** 病虫害结算：把「每秒掷一次骰子」折算为区间累计概率，一次算清。 */
    private static void settlePest(FarmPlot plot, long since, long elapsedSeconds, long now) {
        boolean hasPest = plot.getPestState() != null && plot.getPestState() != FarmPlot.PestState.CLEAN;
        if (hasPest) {
            // 已有病虫害：超过升级窗口则加重（MILD / DISEASE -> SEVERE）
            if (now - plot.getPestTime() > PEST_UPGRADE_MS) {
                plot.setPestState(FarmPlot.PestState.SEVERE);
                plot.setPestTime(now);
            }
            return;
        }

        // 种植满宽限期后才开始检测
        long eligibleFrom = Math.max(since, plot.getPlantedTime() + PEST_GRACE_MS);
        if (now <= eligibleFrom) return;
        long exposedSeconds = (now - eligibleFrom) / 1000;
        if (exposedSeconds <= 0) return;

        // 每小时 2% 换算成"每秒概率"，再按已暴露的秒数复利折算。
        // 注意分母必须是 3600 而不是 60：exposedSeconds 的单位是秒，
        // 写成 /60.0 等于把小时率当成了分钟率，病虫害实际发生率被放大 60 倍
        // （2%/小时 → 名义 2%/分钟），作物几乎一种下就生虫。
        double chance = pestChanceForExposedSeconds(exposedSeconds);
        if (random.nextDouble() < chance) {
            plot.setPestState(random.nextDouble() < 0.7
                    ? FarmPlot.PestState.MILD
                    : FarmPlot.PestState.DISEASE);
            plot.setPestTime(now);
        }
    }

    /**
     * 已暴露 {@code exposedSeconds} 秒后"至少生一次虫"的概率。
     *
     * <p>把小时率折算成秒率再复利累计：{@code 1 - (1 - p_小时/3600)^秒数}。
     * 抽成包内可见的静态方法是为了能直接断言量纲 —— 这段逻辑曾经把 {@code /3600.0}
     * 写成了 {@code /60.0}，导致病虫害发生率被放大 60 倍，而这种错误
     * 靠随机数测试很难稳定复现。</p>
     *
     * @param exposedSeconds 超过宽限期后的暴露秒数（&le;0 时返回 0）
     * @return [0,1] 区间的概率
     */
    static double pestChanceForExposedSeconds(long exposedSeconds) {
        if (exposedSeconds <= 0) {
            return 0.0;
        }
        double perSecond = PEST_CHANCE_PER_HOUR / 3600.0;
        return 1.0 - Math.pow(1.0 - perSecond, exposedSeconds);
    }

    /** 生长阶段：无状态，可由时间直接推导。 */
    private static int computeStage(FarmPlot plot, CropConfig config, long now) {
        long planted = plot.getPlantedTime();
        long total = plot.getHarvestTime() - planted;
        if (total <= 0) return config.getStages();
        int stage = (int) (((now - planted) * config.getStages()) / total) + 1;
        return Math.min(Math.max(stage, 1), config.getStages());
    }

    /** 把结算结果一次性落库（单条 UPDATE：状态/阶段/水分/枯萎/虫害/品质/结算时刻）。 */
    private void persistSettledPlot(Connection conn, FarmPlot plot) throws SQLException {
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

    private int countPlayerPlots(long playerId) {
        String countSql = "SELECT COUNT(*) as cnt FROM farm_plots WHERE player_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询地块数量失败", e);
        }
        return 0;
    }

    private long calculateExpandCost(int currentCount) {
        return 100L * (long) Math.pow(2, currentCount - DEFAULT_PLOTS);
    }

    private FarmPlot getPlotForUpdate(Connection conn, long playerId, int plotIndex) {
        String sql = "SELECT * FROM farm_plots WHERE player_id = ? AND plot_index = ?" + lockClause();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapPlot(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询地块失败", e);
        }
        return null;
    }

    private void updatePlotToPlanted(Connection conn, FarmPlot plot, CropConfig config, String seedKey,
                                      long now, long harvestTime, int yield, double rootBonus, double seasonModifier) {
        String updateSql = """
            UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                planted_time = ?, harvest_time = ?, growth_stage = ?,
                water_level = ?, fertilizer_level = ?, yield = ?, root_bonus = ?, season_modifier = ?,
                settled_time = ?, pest_state = ?, pest_time = ?, crop_quality = ?, wilted_time = 0
            WHERE id = ?
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
            ps.setInt(9, Math.max(yield, 1));
            ps.setDouble(10, rootBonus);
            ps.setDouble(11, seasonModifier);
            ps.setLong(12, now);
            ps.setString(13, FarmPlot.PestState.CLEAN.name());
            ps.setLong(14, 0);
            ps.setString(15, null);
            ps.setLong(16, plot.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("种植失败", e);
        }
    }

    private void updateWaterLevel(Connection conn, long playerId, int plotIndex, int waterLevel) {
        String sql = "UPDATE farm_plots SET water_level = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, waterLevel);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新水分失败", e);
        }
    }

    private void updateFertilizerLevel(Connection conn, long playerId, int plotIndex, int fertilizerLevel) {
        String sql = "UPDATE farm_plots SET fertilizer_level = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, fertilizerLevel);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新肥力失败", e);
        }
    }

    /** 修复：按玩家 + 地块索引更新状态（原实现只按地块索引，会误改其他玩家同索引的地块） */
    private void updatePlotState(Connection conn, long playerId, int plotIndex, String state) {
        String sql = "UPDATE farm_plots SET state = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setLong(2, playerId);
            ps.setInt(3, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新地块状态失败", e);
        }
    }

    private void resetWiltedTime(Connection conn, long playerId, int plotIndex) {
        String sql = "UPDATE farm_plots SET wilted_time = 0 WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("重置枯萎时间失败", e);
        }
    }

    private void clearPestState(Connection conn, long playerId, int plotIndex) {
        String sql = "UPDATE farm_plots SET pest_state = ?, pest_time = ? WHERE player_id = ? AND plot_index = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, FarmPlot.PestState.CLEAN.name());
            ps.setLong(2, 0);
            ps.setLong(3, playerId);
            ps.setInt(4, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("清除病虫害失败", e);
        }
    }

    private void resetPlot(Connection conn, long playerId, int plotIndex) {
        String sql = """
            UPDATE farm_plots SET state = ?, seed_key = ?, crop_key = ?,
                planted_time = ?, harvest_time = ?, growth_stage = ?,
                water_level = ?, fertilizer_level = ?, yield = ?,
                wilted_time = 0, settled_time = 0,
                pest_state = ?, pest_time = 0, crop_quality = ?
            WHERE player_id = ? AND plot_index = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, FarmPlot.PlotState.EMPTY.name());
            ps.setString(2, null);
            ps.setString(3, null);
            ps.setLong(4, 0);
            ps.setLong(5, 0);
            ps.setInt(6, 0);
            ps.setInt(7, WATER_MAX);
            ps.setInt(8, 0);
            ps.setInt(9, 0);
            ps.setString(10, FarmPlot.PestState.CLEAN.name());
            ps.setString(11, null);
            ps.setLong(12, playerId);
            ps.setInt(13, plotIndex);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("重置地块失败", e);
        }
    }

    private void createNewPlot(Connection conn, long playerId, int plotIndex) {
        String sql = "INSERT INTO farm_plots (player_id, plot_index, state, water_level, fertilizer_level) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            ps.setInt(2, plotIndex);
            ps.setString(3, FarmPlot.PlotState.EMPTY.name());
            ps.setInt(4, WATER_MAX);
            ps.setInt(5, 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("创建地块失败", e);
        }
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
        plot.setSettledTime(rs.getLong("settled_time"));
        return plot;
    }

    private Map<String, Object> createResult(boolean success, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", message);
        return result;
    }

    private String getItemName(String itemKey) {
        Item item = ItemRegistry.get(itemKey);
        return item != null ? item.getName() : itemKey;
    }

    private void appendBonusInfo(StringBuilder msg, double rootBonus, double seasonModifier, Season season) {
        if (rootBonus > 0) {
            msg.append("（灵根加成 +").append((int) (rootBonus * 100)).append("%）");
        }
        if (seasonModifier > 1) {
            msg.append("（").append(season.getDisplayName()).append("加成 +").append((int) ((seasonModifier - 1) * 100)).append("%）");
        } else if (seasonModifier < 1) {
            msg.append("（").append(season.getDisplayName()).append("减成 ").append((int) ((seasonModifier - 1) * 100)).append("%）");
        }
    }

    private void appendQualityInfo(StringBuilder msg, FarmPlot.CropQuality quality) {
        if (quality != null && quality != FarmPlot.CropQuality.COMMON) {
            msg.append("【").append(quality.getDisplayName()).append("】");
        }
    }

    private void appendQualityBonus(StringBuilder msg, FarmPlot.CropQuality quality) {
        if (quality != null && quality != FarmPlot.CropQuality.COMMON) {
            msg.append("（品质加成 +").append((int) ((quality.getMultiplier() - 1) * 100)).append("%）");
        }
    }
}