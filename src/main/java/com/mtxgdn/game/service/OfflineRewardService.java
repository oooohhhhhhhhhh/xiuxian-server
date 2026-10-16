package com.mtxgdn.game.service;

import com.mtxgdn.entity.Player;
import com.mtxgdn.game.config.GameConfigLoader;
import com.mtxgdn.game.entity.CelestialPhenomenon;
import com.mtxgdn.game.entity.SpiritualRoot;

public class OfflineRewardService {

    private static final int MAX_OFFLINE_SECONDS = 8 * 3600;
    private static final double OFFLINE_CULTIVATION_RATE = 0.50;
    private static final double OFFLINE_HEART_DEMON_RATE = 0.50;

    private final PlayerService playerService;
    private final DailyService dailyService;
    private final HeartDemonService heartDemonService;

    public OfflineRewardService() {
        this.playerService = new PlayerService();
        this.dailyService = new DailyService();
        this.heartDemonService = new HeartDemonService();
    }

    public OfflineRewardResult processOfflineRewards(long userId) {
        Player player = playerService.getPlayerRaw(userId);
        if (player == null) {
            return OfflineRewardResult.noReward();
        }

        long now = System.currentTimeMillis();
        long lastOffline = player.getLastOfflineTime();

        if (lastOffline <= 0) {
            return OfflineRewardResult.noReward();
        }

        long elapsedMs = now - lastOffline;
        int elapsedSeconds = (int)(elapsedMs / 1000);
        if (elapsedSeconds < 30) {
            playerService.updateLastOfflineTime(player.getId(), 0);
            return OfflineRewardResult.noReward();
        }

        // 原子认领：只有认领成功的请求才结算，防止并发重复发放（WebSocket/REST/QQ 多端同时上线）
        if (!playerService.claimOfflineTime(player.getId(), lastOffline)) {
            return OfflineRewardResult.noReward();
        }

        elapsedSeconds = Math.min(elapsedSeconds, MAX_OFFLINE_SECONDS);

        OfflineRewardResult result = new OfflineRewardResult();
        result.hasReward = true;
        result.offlineSeconds = elapsedSeconds;
        result.offlineMinutes = elapsedSeconds / 60;
        result.offlineHours = result.offlineMinutes / 60;

        int hpRecovered = recoverHpMp(player, elapsedSeconds);
        result.hpRecovered = hpRecovered;
        result.mpRecovered = hpRecovered > 0 ? (int)(hpRecovered * 0.6) : 0;

        if (player.isCultivating()) {
            processOfflineCultivation(player, elapsedSeconds, result);
        }

        return result;
    }

    private void processOfflineCultivation(Player player, int elapsedSeconds, OfflineRewardResult result) {
        // 与在线闭关一致的结算（境界/季节/地点 double 累乘），再乘离线效率
        double rawExp = playerService.calculateCultivationExp(player, elapsedSeconds) * OFFLINE_CULTIVATION_RATE;

        CelestialPhenomenon phenomenon = dailyService.getTodayPhenomenon();
        if (phenomenon != null) {
            rawExp = rawExp * phenomenon.getCultivationMultiplier();
        }

        SpiritualRoot root = player.getSpiritualRoot();
        if (root != null && root.hasEffect(SpiritualRoot.SpecialEffect.CULTIVATION_EFFICIENCY)) {
            rawExp = rawExp * (1 + root.getEffectValue());
        }

        long rawExpGained = Math.max(0, Math.round(rawExp));
        if (rawExpGained <= 0) {
            rawExpGained = elapsedSeconds;
        }

        HeartDemonService.HeartDemonResult hdResult =
                heartDemonService.processCultivation(player.getId(), rawExpGained,
                        (int)(elapsedSeconds * OFFLINE_HEART_DEMON_RATE));

        long netExp = hdResult.netExpChange;
        if (netExp != 0) {
            playerService.addExperience(player.getId(), netExp);
        }

        result.wasCultivating = true;
        result.expGained = netExp;
        result.rawExpGained = rawExpGained;

        if (hdResult.triggered) {
            result.heartDemonTriggered = true;
            result.heartDemonSeverity = hdResult.severity;
            result.heartDemonNarrative = hdResult.narrative;
            result.heartDemonExpLost = hdResult.expLost;
        }

        playerService.setCultivating(player.getId(), true);
    }

    private int recoverHpMp(Player player, int elapsedSeconds) {
        if (player.getHp() >= player.getMaxHp() && player.getMp() >= player.getMaxMp()) {
            return 0;
        }

        double recoverPercent = elapsedSeconds / 60.0 * 0.02;
        recoverPercent = Math.min(recoverPercent, 1.0);

        int hpRecovered = 0;
        if (player.getHp() < player.getMaxHp()) {
            hpRecovered = (int)(player.getMaxHp() * recoverPercent);
            playerService.addHp(player.getId(), hpRecovered);
        }

        if (player.getMp() < player.getMaxMp()) {
            int mpRecovered = (int)(player.getMaxMp() * recoverPercent);
            playerService.addMp(player.getId(), mpRecovered);
        }

        return hpRecovered;
    }

    public static class OfflineRewardResult {
        public boolean hasReward;
        public boolean wasCultivating;
        public int offlineSeconds;
        public int offlineMinutes;
        public int offlineHours;
        public long expGained;
        public long rawExpGained;
        public int hpRecovered;
        public int mpRecovered;
        public boolean heartDemonTriggered;
        public String heartDemonSeverity;
        public String heartDemonNarrative;
        public long heartDemonExpLost;

        public static OfflineRewardResult noReward() {
            OfflineRewardResult r = new OfflineRewardResult();
            r.hasReward = false;
            return r;
        }
    }
}
