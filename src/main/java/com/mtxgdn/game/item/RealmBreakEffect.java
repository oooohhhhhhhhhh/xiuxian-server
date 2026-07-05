package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class RealmBreakEffect extends ItemEffect {

    private double tribulationSuccessBonus;

    public RealmBreakEffect() {
    }

    public RealmBreakEffect(double tribulationSuccessBonus) {
        this.tribulationSuccessBonus = tribulationSuccessBonus;
    }

    public double getTribulationSuccessBonus() {
        return tribulationSuccessBonus;
    }

    public void setTribulationSuccessBonus(double tribulationSuccessBonus) {
        this.tribulationSuccessBonus = tribulationSuccessBonus;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "服用渡劫丹，渡劫成功率提升 " + String.format("%.0f", tribulationSuccessBonus * 100) + "%";
    }

    public static RealmBreakEffect of(double bonus) {
        return new RealmBreakEffect(bonus);
    }
}