package com.mtxgdn.game.item;

import com.mtxgdn.common.service.ServiceRegistry;

import com.mtxgdn.game.service.BuffService;
import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

/**
 * 洗髓丹效果：清除全部活跃 Buff 并回退其属性加成，同时恢复满生命/法力。
 * （项目无属性点分配系统，此处实现为"重置战斗加成状态"）
 */
public class AttributeResetEffect extends ItemEffect {

    public AttributeResetEffect() {
    }

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        ServiceRegistry.getBuffService().removeAllBuffs(playerId);
        playerService.addHp(playerId, Integer.MAX_VALUE);
        playerService.addMp(playerId, Integer.MAX_VALUE);
        return "洗髓伐毛！已清除全部增益状态，生命与法力恢复至满值，";
    }

    public static AttributeResetEffect of() {
        return new AttributeResetEffect();
    }
}
