package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class TeleportEffect extends ItemEffect {

    private String destination;

    public TeleportEffect() {
    }

    public TeleportEffect(String destination) {
        this.destination = destination;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        if (destination == null || destination.isEmpty()) {
            return "使用传送符，但目的地未知";
        }
        return "使用传送符，传送到了 " + destination;
    }

    public static TeleportEffect to(String destination) {
        return new TeleportEffect(destination);
    }
}