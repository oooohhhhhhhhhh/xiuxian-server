package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

public class AttributeResetEffect extends ItemEffect {

    public AttributeResetEffect() {
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "使用洗髓丹，属性已重置，可重新分配";
    }

    public static AttributeResetEffect of() {
        return new AttributeResetEffect();
    }
}