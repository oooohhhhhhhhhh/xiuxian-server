package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class InventoryCapacityEffect extends ItemEffect {

    private int capacityBonus;

    public InventoryCapacityEffect() {
    }

    public InventoryCapacityEffect(int capacityBonus) {
        this.capacityBonus = capacityBonus;
    }

    public int getCapacityBonus() {
        return capacityBonus;
    }

    public void setCapacityBonus(int capacityBonus) {
        this.capacityBonus = capacityBonus;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "背包容量 +" + capacityBonus + " 格";
    }

    public static InventoryCapacityEffect of(int capacityBonus) {
        return new InventoryCapacityEffect(capacityBonus);
    }
}