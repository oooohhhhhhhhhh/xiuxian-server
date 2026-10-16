package com.mtxgdn.game.item;

import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

/**
 * 伤害反弹丹效果：受到伤害时按比例反弹给攻击者（+30% 反弹，120 秒）。
 * 战斗结算中通过 BuffService.getReflectionPercent 生效。
 */
public class ReflectionBuffEffect extends ItemEffect {

    private int percent;
    private int durationSeconds;

    public ReflectionBuffEffect() {
        this.percent = 30;
        this.durationSeconds = 120;
    }

    public ReflectionBuffEffect(int percent, int durationSeconds) {
        this.percent = percent;
        this.durationSeconds = durationSeconds;
    }

    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public int getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        new BuffService().addReflectionBuff(playerId, percent, durationSeconds);
        return "获得伤害反弹 " + percent + "%（持续 " + durationSeconds + " 秒），";
    }

    public static ReflectionBuffEffect of(int percent, int seconds) {
        return new ReflectionBuffEffect(percent, seconds);
    }
}
