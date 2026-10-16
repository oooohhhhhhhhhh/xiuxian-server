package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

/**
 * 净化丹效果：清除"重伤"负面状态并恢复生命。
 * 重伤（HP=1）时使用恢复 60% 最大生命，否则恢复 20% 最大生命。
 */
public class PurificationEffect extends ItemEffect {

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        int maxHp = playerService.getPlayerById(playerId) != null
                ? playerService.getPlayerById(playerId).getMaxHp() : 100;
        int currentHp = playerService.getPlayerById(playerId) != null
                ? playerService.getPlayerById(playerId).getHp() : 0;
        int restore = currentHp <= 1 ? (int)(maxHp * 0.6) : (int)(maxHp * 0.2);
        playerService.addHp(playerId, restore);
        return "净化体内淤毒，重伤状态消除，恢复 " + restore + " 点生命，";
    }

    public static PurificationEffect of() {
        return new PurificationEffect();
    }
}
