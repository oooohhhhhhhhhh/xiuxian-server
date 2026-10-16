package com.mtxgdn.game.service;

import com.mtxgdn.game.entity.CropConfig;
import com.mtxgdn.game.entity.Season;
import com.mtxgdn.util.AppConfig;
import com.mtxgdn.util.GameLogger;

import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 全局季节服务 —— 将季节系统同步至整个修仙世界。
 *
 * <p>季节不再只是种田系统的附属设定，而是全服共享的"天道时序"，
 * 影响闭关修炼、晨修、游历、灵石获取等全局玩法。</p>
 *
 * <p>两种模式（application.yml 中 season.mode 配置）：</p>
 * <ul>
 *   <li>{@code calendar}（默认）：按真实月份划分四季（3-5 春、6-8 夏、9-11 秋、其余冬）</li>
 *   <li>{@code cycle}：按固定小时数循环更替（season.cycle_hours，默认 6 小时一季），更替时全服广播</li>
 * </ul>
 */
public class SeasonService {

    private static final GameLogger LOG = GameLogger.getLogger(SeasonService.class);

    private static final boolean CYCLE_MODE = "cycle".equalsIgnoreCase(AppConfig.get("season.mode", "calendar"));
    private static final long CYCLE_DURATION_MS =
            Math.max(1, AppConfig.getInt("season.cycle_hours", 6)) * 3600_000L;

    private static volatile Season currentSeason;
    private static volatile long seasonStartMs;
    private static volatile long seasonEndMs;

    private static volatile Consumer<Season> changeListener;

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "global-season-tick");
                t.setDaemon(true);
                return t;
            });

    static {
        recompute(System.currentTimeMillis(), true);
        scheduler.scheduleAtFixedRate(SeasonService::tick, 10, 10, TimeUnit.SECONDS);
        LOG.info("全局季节系统已启动（模式: " + (CYCLE_MODE ? "cycle/" + (CYCLE_DURATION_MS / 3600_000L) + "小时" : "calendar") + "），当前季节: " + currentSeason.getDisplayName());
    }

    private static void tick() {
        // 内置模块门控：修炼模块停用后暂停天象刷新
        if (!com.mtxgdn.plugin.BuiltInPluginRegistry.getInstance().isEnabledOrUnknown("cultivation")) return;
        recompute(System.currentTimeMillis(), false);
    }

    private static synchronized void recompute(long now, boolean force) {
        Season next = computeSeason(now);
        long start = computeSeasonStart(now);
        long end = computeSeasonEnd(now);
        boolean changed = force || currentSeason == null || next != currentSeason;
        if (changed) {
            Season old = currentSeason;
            currentSeason = next;
            seasonStartMs = start;
            seasonEndMs = end;
            if (old != null && old != next) {
                LOG.info("【季节更替】" + old.getDisplayName() + " → " + next.getDisplayName());
                Consumer<Season> listener = changeListener;
                if (listener != null) {
                    try {
                        listener.accept(next);
                    } catch (Exception e) {
                        LOG.error("季节更替广播失败", e);
                    }
                }
            }
        }
    }

    // ==================== 季节计算 ====================

    private static Season computeSeason(long now) {
        if (CYCLE_MODE) {
            long idx = Math.floorMod(now / CYCLE_DURATION_MS, 4);
            return switch ((int) idx) {
                case 0 -> Season.SPRING;
                case 1 -> Season.SUMMER;
                case 2 -> Season.AUTUMN;
                default -> Season.WINTER;
            };
        }
        return Season.getCurrentSeason();
    }

    private static long computeSeasonStart(long now) {
        if (CYCLE_MODE) {
            return now - Math.floorMod(now, CYCLE_DURATION_MS);
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int month = c.get(Calendar.MONTH) + 1;
        int year = c.get(Calendar.YEAR);
        int startMonth;
        if (month >= 3 && month <= 5) {
            startMonth = 3;
        } else if (month >= 6 && month <= 8) {
            startMonth = 6;
        } else if (month >= 9 && month <= 11) {
            startMonth = 9;
        } else {
            startMonth = 12;
            if (month == 1 || month == 2) {
                year = year - 1;
            }
        }
        Calendar start = Calendar.getInstance();
        start.clear();
        start.set(year, startMonth - 1, 1, 0, 0, 0);
        return start.getTimeInMillis();
    }

    private static long computeSeasonEnd(long now) {
        if (CYCLE_MODE) {
            return computeSeasonStart(now) + CYCLE_DURATION_MS;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int month = c.get(Calendar.MONTH) + 1;
        int year = c.get(Calendar.YEAR);
        int endMonth;
        int endYear = year;
        if (month >= 3 && month <= 5) {
            endMonth = 6;
        } else if (month >= 6 && month <= 8) {
            endMonth = 9;
        } else if (month >= 9 && month <= 11) {
            endMonth = 12;
        } else {
            endMonth = 3;
            endYear = year + 1;
        }
        Calendar end = Calendar.getInstance();
        end.clear();
        end.set(endYear, endMonth - 1, 1, 0, 0, 0);
        return end.getTimeInMillis();
    }

    // ==================== 对外接口 ====================

    /** 当前全局季节 */
    public static Season getCurrentSeason() {
        return currentSeason;
    }

    /** 当前季节开始时间（毫秒时间戳） */
    public static long getSeasonStartMs() {
        return seasonStartMs;
    }

    /** 当前季节结束（下次更替）时间（毫秒时间戳） */
    public static long getSeasonEndMs() {
        return seasonEndMs;
    }

    /** 距离下次季节更替的剩余毫秒数 */
    public static long getSeasonRemainingMs() {
        return Math.max(0, seasonEndMs - System.currentTimeMillis());
    }

    /** 季节循环单季时长（毫秒） */
    public static long getSeasonDurationMs() {
        return CYCLE_MODE ? CYCLE_DURATION_MS : seasonEndMs - seasonStartMs;
    }

    /** 注册季节更替监听（用于全服广播） */
    public static void setChangeListener(Consumer<Season> listener) {
        changeListener = listener;
    }

    // ==================== 全局效果 ====================

    /** 修炼速度倍率（闭关系统） */
    public static double getCultivationMultiplier() {
        return switch (currentSeason) {
            case SPRING -> 1.10;   // 春回大地，灵气升腾
            case SUMMER -> 1.05;
            case AUTUMN -> 1.15;   // 金秋肃杀，感悟更深
            case WINTER -> 0.95;   // 天寒地冻，灵气凝滞
        };
    }

    /** 晨修/机缘经验倍率 */
    public static double getMorningCultivationMultiplier() {
        return getCultivationMultiplier();
    }

    /** 游历探索收益倍率 */
    public static double getExplorationMultiplier() {
        return switch (currentSeason) {
            case SPRING -> 1.10;
            case SUMMER -> 1.00;
            case AUTUMN -> 1.05;
            case WINTER -> 0.90;
        };
    }

    /** 灵石获取倍率 */
    public static double getSpiritStoneMultiplier() {
        return switch (currentSeason) {
            case SPRING -> 1.00;
            case SUMMER -> 0.95;
            case AUTUMN -> 1.20;   // 秋收冬藏，收获丰盛
            case WINTER -> 0.90;
        };
    }

    /** 物品掉落倍率 */
    public static double getDropMultiplier() {
        return switch (currentSeason) {
            case SPRING -> 1.05;
            case SUMMER -> 1.00;
            case AUTUMN -> 1.15;
            case WINTER -> 0.90;
        };
    }

    /** 作物生长倍率（种田系统） */
    public static double getGrowthModifier(CropConfig.ElementType elementType) {
        return currentSeason.getGrowthModifier(elementType);
    }

    /** 季节对指定灵根属性的契合描述 */
    public static String getSeasonEffectDescription() {
        return switch (currentSeason) {
            case SPRING -> "修炼+10%，游历+10%，木/灵系作物生长加速";
            case SUMMER -> "修炼+5%，火系作物生长加速，灵石-5%";
            case AUTUMN -> "修炼+15%，灵石+20%，掉落+15%，金/土系作物生长加速";
            case WINTER -> "修炼-5%，水/冰系作物生长加速，游历-10%";
        };
    }
}
