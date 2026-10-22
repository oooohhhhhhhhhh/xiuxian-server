package com.mtxgdn.game.item;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

/**
 * 经验翻倍丹效果：短时间内提升经验获取效率（+50%，300 秒）。
 * 通过 BuffService 经验倍率生效于 addExperience 结算。
 */
public class ExpMultiplierEffect extends ItemEffect {

    private int percent;
    private int durationSeconds;

    public ExpMultiplierEffect() {
        this.percent = 50;
        this.durationSeconds = 300;
    }

    public ExpMultiplierEffect(int percent, int durationSeconds) {
        this.percent = percent;
        this.durationSeconds = durationSeconds;
    }

    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        ServiceRegistry.getBuffService().addExpBoost(playerId, percent, durationSeconds);
        return "经验获取效率提升 " + percent + "%（持续 " + durationSeconds + " 秒），";
    }

    public static ExpMultiplierEffect of(int percent, int seconds) {
        return new ExpMultiplierEffect(percent, seconds);
    }
}
