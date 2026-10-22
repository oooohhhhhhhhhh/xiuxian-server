package com.mtxgdn.game.service;

import com.mtxgdn.db.DatabaseManager;
import com.mtxgdn.entity.Player;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import com.mtxgdn.game.entity.ExplorationResult;
import com.mtxgdn.game.entity.SpiritualRoot;
import com.mtxgdn.game.explorationevent.ExplorationEvent;
import com.mtxgdn.game.explorationevent.ExplorationEventRegistry;
import com.mtxgdn.common.service.ServiceRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ExplorationService {

    /** 游历基础冷却 5 分钟（原 60 秒导致每天可刷上千次，灵石/物品产出失控） */
    public static final long BASE_COOLDOWN_MS = 300_000;

    private final PlayerService playerService;
    private final ItemService itemService;
    private final Random random = new Random();

    public ExplorationService() {
        this.playerService = ServiceRegistry.getPlayerService();
        this.itemService = ServiceRegistry.getItemService();
    }

    public ExplorationService(PlayerService playerService) {
        this.playerService = playerService;
        this.itemService = ServiceRegistry.getItemService();
    }

    /**
     * 解析玩家所在地点：优先用已加载的角色行，仅在位置缺失（NULL/0）时才回查数据库。
     * 地点失效时按出生地处理，收益与事件偏置都不会因此错乱。
     */
    private long resolveLocationId(Player player) {
        long locId = player.getCurrentLocationId();
        if (locId > 0) {
            return locId;
        }
        return ServiceRegistry.getMapService().getPlayerLocationId(player.getId());
    }

    /**
     * 游历探索 - 使用事务锁防止并发重复触发
     * 修复 TOCTOU 竞态条件：冷却检查和时间更新必须在同一事务内
     */
    public ExplorationResult explore(long userId) {
        Player player = playerService.getPlayerByUserIdRaw(userId);
        if (player == null) {
            return ExplorationResult.failure("角色不存在，请先创建角色");
        }

        if (player.getHp() <= 0) {
            return ExplorationResult.failure("你已重伤，无法游历，请先恢复生命值");
        }

        long now = System.currentTimeMillis();
        long effectiveCd = calculateEffectiveCooldown(player);

        // 使用事务锁将冷却检查和时间更新原子化
        boolean cooldownPassed = DatabaseManager.runTransaction(conn -> {
            // 加锁读取玩家的游历冷却时间
            String lockSql = "SELECT last_exploration_time FROM players WHERE id = ? " +
                DatabaseManager.getDialect().getForUpdateClause();
            try (PreparedStatement ps = conn.prepareStatement(lockSql)) {
                ps.setLong(1, player.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long lastTime = rs.getLong("last_exploration_time");
                        if (lastTime > 0 && (now - lastTime) < effectiveCd) {
                            return false; // 冷却中
                        }
                    }
                }
            }
            // 更新游历时间
            String updateSql = "UPDATE players SET last_exploration_time = ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, now);
                ps.setLong(2, player.getId());
                ps.executeUpdate();
            }
            return true;
        });

        if (!cooldownPassed) {
            long remaining = (effectiveCd - (now - player.getLastExplorationTime())) / 1000;
            return ExplorationResult.failure("你刚游历过，还需要等待 " + remaining + " 秒");
        }

        // 所在地界：既决定事件抽取的权重偏置，也决定收益倍率
        MapService mapService = ServiceRegistry.getMapService();
        long locationId = resolveLocationId(player);
        String locationName = mapService.getLocationName(locationId);
        Map<String, Double> locationBias = mapService.getEventBias(locationId);

        // 插件事件：游历开始
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.EXPLORATION_START)
                        .data("playerId", player.getId())
                        .data("locationId", locationId)
                        .build());

        ExplorationEvent event = ExplorationEventRegistry.randomByWeight(random, locationBias);
        if (event == null) {
            return ExplorationResult.failure("暂无可用游历事件");
        }

        List<String> log = buildExplorationLog(locationName, locationBias);
        ExplorationResult result = createResult(event, log, now, effectiveCd);

        event.execute(player, playerService, itemService, random, result, log);

        // 全局季节：游历收益倍率（春/夏/秋/冬各有游历加成，按已结算收益追加）
        double explMult = SeasonService.getExplorationMultiplier();
        if (explMult > 1.0 && result.isSuccess()) {
            long bonusGold = (long)(result.getGoldGained() * (explMult - 1));
            long bonusSS = (long)(result.getSpiritStonesGained() * (explMult - 1));
            long bonusExp = (long)(result.getExpGained() * (explMult - 1));
            if (bonusGold > 0) playerService.addGold(player.getId(), bonusGold);
            if (bonusSS > 0) itemService.addSpiritStones(player.getId(), bonusSS);
            if (bonusExp > 0) playerService.addExperience(player.getId(), bonusExp);
            if (bonusGold > 0 || bonusSS > 0 || bonusExp > 0) {
                StringBuilder sb = new StringBuilder();
                if (bonusGold > 0) sb.append("金币+").append(bonusGold);
                if (bonusSS > 0) sb.append(sb.length() > 0 ? " " : "").append("灵石+").append(bonusSS);
                if (bonusExp > 0) sb.append(sb.length() > 0 ? " " : "").append("经验+").append(bonusExp);
                log.add("🍂 " + SeasonService.getCurrentSeason().getDisplayName() + "游历加成: " + sb);
            }
        }

        // 今日天象游历收益倍率（星辰耀天 ×1.2 / 万籁俱寂 ×1.05），按已结算收益追加
        CelestialPhenomenon celestial = com.mtxgdn.game.service.DailyService.getTodayPhenomenonStatic();
        if (celestial.getExplorationMultiplier() > 1.0 && result.isSuccess()) {
            long cGold = (long) (result.getGoldGained() * (celestial.getExplorationMultiplier() - 1));
            long cSS = (long) (result.getSpiritStonesGained() * (celestial.getExplorationMultiplier() - 1));
            long cExp = (long) (result.getExpGained() * (celestial.getExplorationMultiplier() - 1));
            if (cGold > 0) playerService.addGold(player.getId(), cGold);
            if (cSS > 0) itemService.addSpiritStones(player.getId(), cSS);
            if (cExp > 0) playerService.addExperience(player.getId(), cExp);
            if (cGold > 0 || cSS > 0 || cExp > 0) {
                StringBuilder csb = new StringBuilder();
                if (cGold > 0) csb.append("金币+").append(cGold);
                if (cSS > 0) csb.append(csb.length() > 0 ? " " : "").append("灵石+").append(cSS);
                if (cExp > 0) csb.append(csb.length() > 0 ? " " : "").append("经验+").append(cExp);
                log.add("✦ " + celestial.getDisplayName() + "游历加成: " + csb);
            }
        }

        // 所在地界游历收益倍率（越凶险的地界产出越高），按已结算收益追加
        double locationMult = mapService.getExplorationBonus(locationId);
        if (locationMult > 1.0 && result.isSuccess()) {
            long lGold = (long) (result.getGoldGained() * (locationMult - 1));
            long lSS = (long) (result.getSpiritStonesGained() * (locationMult - 1));
            long lExp = (long) (result.getExpGained() * (locationMult - 1));
            if (lGold > 0) playerService.addGold(player.getId(), lGold);
            if (lSS > 0) itemService.addSpiritStones(player.getId(), lSS);
            if (lExp > 0) playerService.addExperience(player.getId(), lExp);
            if (lGold > 0 || lSS > 0 || lExp > 0) {
                StringBuilder lsb = new StringBuilder();
                if (lGold > 0) lsb.append("金币+").append(lGold);
                if (lSS > 0) lsb.append(lsb.length() > 0 ? " " : "").append("灵石+").append(lSS);
                if (lExp > 0) lsb.append(lsb.length() > 0 ? " " : "").append("经验+").append(lExp);
                log.add("🏔 【" + locationName + "】地界加成 ×" + locationMult + ": " + lsb);
            }
        }

        new DailyService().onExplore(player.getId(), result.getEventType());

        // 插件事件：游历结束
        com.mtxgdn.plugin.event.PluginEventManager.fireQuietly(
                com.mtxgdn.plugin.event.PluginEvent.builder()
                        .type(com.mtxgdn.plugin.event.PluginEvent.Type.EXPLORATION_END)
                        .data("playerId", player.getId())
                        .data("eventType", result.getEventType() == null ? "" : result.getEventType())
                        .build());

        return result;
    }

    private long calculateEffectiveCooldown(Player player) {
        long effectiveCd = BASE_COOLDOWN_MS;
        if (player.getSpiritualRoot() != null && player.getSpiritualRoot().hasEffect(SpiritualRoot.SpecialEffect.EXPLORATION_CD)) {
            effectiveCd = (long)(BASE_COOLDOWN_MS * (1 - player.getSpiritualRoot().getEffectValue()));
        }
        return effectiveCd;
    }

    private List<String> buildExplorationLog(String locationName, Map<String, Double> locationBias) {
        List<String> log = new ArrayList<>();
        log.add("你从【" + locationName + "】踏上了游历之路...");
        String favored = describeFavoredEvents(locationBias);
        if (!favored.isEmpty()) {
            log.add("此地以" + favored + "闻名，遇上这类事的几率更高。");
        }
        log.add("---");
        return log;
    }

    /** 把地界偏置翻成一句人话（只取倍率 >1 的事件，按倍率降序）。 */
    private String describeFavoredEvents(Map<String, Double> locationBias) {
        if (locationBias == null || locationBias.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Double>> favored = locationBias.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 1.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Double> entry : favored) {
            ExplorationEvent event = ExplorationEventRegistry.getByKey(entry.getKey());
            if (event != null) {
                names.add("「" + event.getName() + "」");
            }
            if (names.size() >= 3) {
                break;
            }
        }
        return String.join("、", names);
    }

    private ExplorationResult createResult(ExplorationEvent event, List<String> log, long now, long effectiveCd) {
        ExplorationResult result = new ExplorationResult();
        result.setSuccess(true);
        result.setLog(log);
        result.setNextExplorationTime(now + effectiveCd);
        return result;
    }
}