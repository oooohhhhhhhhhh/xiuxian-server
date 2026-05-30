package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class ExpEffect extends ItemEffect {

    private long expGain;

    public ExpEffect() {
    }

    public ExpEffect(long expGain) {
        this.expGain = expGain;
    }

    public long getExpGain() {
        return expGain;
    }

    public void setExpGain(long expGain) {
        this.expGain = expGain;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        playerService.addExperience(playerId, expGain);
        return "获得了 " + expGain + " 点经验，";
    }

    public static ExpEffect of(long exp) {
        return new ExpEffect(exp);
    }
}
