package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class EmptyEffect extends ItemEffect {

    public static final EmptyEffect INSTANCE = new EmptyEffect();

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return null;
    }
}
