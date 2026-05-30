package com.mtxgdn.game.item;

import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.ItemService;

public abstract class ItemEffect {

    public abstract String execute(long playerId, PlayerService playerService, ItemService itemService);

    public boolean isInstantEffect() {
        return true;
    }
}
