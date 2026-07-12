package com.mtxgdn.onebot.command.farm;

import com.mtxgdn.common.command.Command;
import com.mtxgdn.common.command.CommandContext;
import com.mtxgdn.game.entity.CropConfig;
import com.mtxgdn.game.entity.FarmPlot;
import com.mtxgdn.game.entity.PlayerInfo;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRegistry;
import com.mtxgdn.game.service.FarmService;
import com.mtxgdn.game.service.ItemService;

import java.util.List;
import java.util.Map;

public class FarmCommand extends Command {

    private final FarmService farmService = new FarmService();
    private final ItemService itemService = new ItemService();

    public FarmCommand() {
        super(new String[]{"种田", "farm"}, "种田系统", "/种田 [查看/种植/浇水/施肥/收获/清理/扩建/种子/一键浇水/一键施肥/一键收获/杀虫]", "探索");
    }

    @Override
    public void execute(CommandContext ctx) {
        Long userId = ctx.requireBinding();
        if (userId == null) return;
        PlayerInfo p = ctx.requirePlayer(userId);
        if (p == null) return;

        String arg = ctx.getArg();
        if (arg == null || arg.isBlank()) {
            ctx.reply(viewPlots(p.getId()));
            return;
        }

        String[] parts = arg.trim().split("\\s+", 3);
        String sub = parts[0];
        String result;
        if ("查看".equals(sub) || "list".equals(sub) || "v".equals(sub)) {
            result = viewPlots(p.getId());
        } else if ("种植".equals(sub) || "plant".equals(sub) || "p".equals(sub)) {
            result = plant(p.getId(), parts);
        } else if ("浇水".equals(sub) || "water".equals(sub) || "w".equals(sub)) {
            result = water(p.getId(), parts);
        } else if ("施肥".equals(sub) || "fertilize".equals(sub) || "f".equals(sub)) {
            result = fertilize(p.getId(), parts);
        } else if ("收获".equals(sub) || "harvest".equals(sub) || "h".equals(sub)) {
            result = harvest(p.getId(), parts);
        } else if ("清理".equals(sub) || "clear".equals(sub) || "c".equals(sub)) {
            result = clearPlot(p.getId(), parts);
        } else if ("扩建".equals(sub) || "expand".equals(sub) || "e".equals(sub)) {
            result = expandPlot(p.getId());
        } else if ("种子".equals(sub) || "seeds".equals(sub) || "s".equals(sub)) {
            result = listSeeds(p.getId());
        } else if ("一键浇水".equals(sub) || "waterall".equals(sub) || "wa".equals(sub)) {
            result = waterAll(p.getId());
        } else if ("一键施肥".equals(sub) || "fertilizeall".equals(sub) || "fa".equals(sub)) {
            result = fertilizeAll(p.getId(), parts);
        } else if ("一键收获".equals(sub) || "harvestall".equals(sub) || "ha".equals(sub)) {
            result = harvestAll(p.getId());
        } else if ("杀虫".equals(sub) || "pesticide".equals(sub) || "killpest".equals(sub)) {
            result = usePesticide(p.getId(), parts);
        } else {
            result = "未知子命令: " + sub;
        }
        ctx.reply(result);
    }

    private String viewPlots(long playerId) {
        List<FarmPlot> plots = farmService.getPlots(playerId);
        StringBuilder sb = new StringBuilder();
        sb.append("【我的农田】\n");
        sb.append("──────────────\n");

        Season currentSeason = Season.getCurrentSeason();
        sb.append("当前季节: ").append(currentSeason.getDisplayName())
          .append(" ").append(currentSeason.getDescription()).append("\n\n");

        for (FarmPlot plot : plots) {
            sb.append("地块 ").append(plot.getPlotIndex()).append(": ");
            sb.append(plot.getState().getDisplayName());

            if (plot.getState() != FarmPlot.PlotState.EMPTY) {
                CropConfig config = CropConfig.get(plot.getSeedKey());
                String cropName = config != null ? config.getCropName() : "未知作物";

                sb.append(" (").append(cropName).append(")");
                sb.append(" | 阶段: ").append(plot.getGrowthStage());

                if (config != null) {
                    sb.append("/").append(config.getStages());
                }

                sb.append(" | 水分: ").append(plot.getWaterLevel()).append("%");
                sb.append(" | 肥力: ").append(plot.getFertilizerLevel()).append("%");

                if (plot.getPestState() != null && plot.getPestState() != FarmPlot.PestState.CLEAN) {
                    sb.append(" | ⚠").append(plot.getPestState().getDisplayName());
                }

                if (plot.getRootBonus() > 0) {
                    sb.append(" | 灵根+").append((int)(plot.getRootBonus() * 100)).append("%");
                }

                if (plot.getSeasonModifier() != 1) {
                    if (plot.getSeasonModifier() > 1) {
                        sb.append(" | 季节+").append((int)((plot.getSeasonModifier() - 1) * 100)).append("%");
                    } else {
                        sb.append(" | 季节").append((int)((plot.getSeasonModifier() - 1) * 100)).append("%");
                    }
                }

                if (plot.getState() == FarmPlot.PlotState.GROWING) {
                    long remaining = Math.max(0, plot.getHarvestTime() - System.currentTimeMillis());
                    long mins = remaining / 60000;
                    long secs = (remaining % 60000) / 1000;
                    sb.append(" | 剩余: ").append(mins).append("分").append(secs).append("秒");
                } else if (plot.getState() == FarmPlot.PlotState.READY) {
                    sb.append(" | ✅可收获");
                    if (plot.getCropQuality() != null && plot.getCropQuality() != FarmPlot.CropQuality.COMMON) {
                        sb.append(" (").append(plot.getCropQuality().getDisplayName()).append(")");
                    }
                } else if (plot.getState() == FarmPlot.PlotState.WILTED) {
                    sb.append(" | 💧已枯萎");
                }
            }

            sb.append("\n");
        }

        sb.append("──────────────\n");
        sb.append("可用命令: /种田 种植 [地块] [种子] | /种田 浇水 [地块] | /种田 施肥 [地块] [low/mid/high] | /种田 收获 [地块] | /种田 杀虫 [地块] | /种田 扩建\n");
        sb.append("一键操作: /种田 一键浇水 | /种田 一键施肥 [low/mid/high] | /种田 一键收获");
        return sb.toString();
    }

    private String plant(long playerId, String[] args) {
        if (args.length < 3) {
            return "用法: /种田 种植 [地块索引] [种子key]\n可用种子: " + listAvailableSeeds(playerId);
        }

        try {
            int plotIndex = Integer.parseInt(args[1]);
            String seedKey = args[2];
            Map<String, Object> result = farmService.plant(playerId, plotIndex, seedKey);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String water(long playerId, String[] args) {
        if (args.length < 2) {
            return "用法: /种田 浇水 [地块索引]";
        }

        try {
            int plotIndex = Integer.parseInt(args[1]);
            Map<String, Object> result = farmService.water(playerId, plotIndex);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String fertilize(long playerId, String[] args) {
        if (args.length < 2) {
            return "用法: /种田 施肥 [地块索引] [肥料类型(可选)]\n肥料类型: low/mid/high (默认低级肥料)";
        }

        try {
            int plotIndex = Integer.parseInt(args[1]);
            String fertilizerKey = "mtxgdn:low_grade_fertilizer";
            if (args.length >= 3) {
                fertilizerKey = switch (args[2].toLowerCase()) {
                    case "mid", "中级" -> "mtxgdn:mid_grade_fertilizer";
                    case "high", "高级" -> "mtxgdn:high_grade_fertilizer";
                    default -> "mtxgdn:low_grade_fertilizer";
                };
            }
            Map<String, Object> result = farmService.fertilize(playerId, plotIndex, fertilizerKey);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String harvest(long playerId, String[] args) {
        if (args.length < 2) {
            return "用法: /种田 收获 [地块索引]";
        }

        try {
            int plotIndex = Integer.parseInt(args[1]);
            Map<String, Object> result = farmService.harvest(playerId, plotIndex);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String clearPlot(long playerId, String[] args) {
        if (args.length < 2) {
            return "用法: /种田 清理 [地块索引]";
        }

        try {
            int plotIndex = Integer.parseInt(args[1]);
            Map<String, Object> result = farmService.clearPlot(playerId, plotIndex);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String expandPlot(long playerId) {
        Map<String, Object> result = farmService.expandPlot(playerId);
        return (String) result.get("message");
    }

    private String waterAll(long playerId) {
        Map<String, Object> result = farmService.waterAll(playerId);
        return (String) result.get("message");
    }

    private String fertilizeAll(long playerId, String[] args) {
        String fertilizerKey = "mtxgdn:low_grade_fertilizer";
        if (args.length >= 2) {
            fertilizerKey = switch (args[1].toLowerCase()) {
                case "mid", "中级" -> "mtxgdn:mid_grade_fertilizer";
                case "high", "高级" -> "mtxgdn:high_grade_fertilizer";
                default -> "mtxgdn:low_grade_fertilizer";
            };
        }
        Map<String, Object> result = farmService.fertilizeAll(playerId, fertilizerKey);
        return (String) result.get("message");
    }

    private String harvestAll(long playerId) {
        Map<String, Object> result = farmService.harvestAll(playerId);
        return (String) result.get("message");
    }

    private String usePesticide(long playerId, String[] args) {
        if (args.length < 2) {
            return "用法: /种田 杀虫 [地块索引]";
        }
        try {
            int plotIndex = Integer.parseInt(args[1]);
            Map<String, Object> result = farmService.usePesticide(playerId, plotIndex);
            return (String) result.get("message");
        } catch (NumberFormatException e) {
            return "地块索引必须是数字";
        }
    }

    private String listSeeds(long playerId) {
        return "【可用种子】\n" + listAvailableSeeds(playerId);
    }

    private String listAvailableSeeds(long playerId) {
        StringBuilder sb = new StringBuilder();
        String[] seedKeys = {
            "mtxgdn:spirit_grass_seed",
            "mtxgdn:thousand_year_ginseng_seed",
            "mtxgdn:dark_ice_grass_seed",
            "mtxgdn:fire_vine_seed",
            "mtxgdn:nether_flower_seed",
            "mtxgdn:star_grass_seed",
            "mtxgdn:blood_lingzhi_seed",
            "mtxgdn:tianshan_snow_lotus_seed"
        };

        for (String key : seedKeys) {
            Item item = ItemRegistry.get(key);
            if (item != null) {
                long count = itemService.getItemCount(playerId, key);
                CropConfig config = CropConfig.get(key);
                String cropName = config != null ? "→ " + config.getCropName() : "";
                sb.append("- ").append(item.getName()).append(" (").append(key).append(") ")
                  .append(cropName).append(" | 数量: ").append(count).append("\n");
            }
        }

        return sb.toString();
    }
}