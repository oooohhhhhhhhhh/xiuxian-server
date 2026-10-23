package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;
import com.mtxgdn.game.service.RealmService;

/**
 * 渡劫丹效果：服用时提示将在突破时自动生效。
 *
 * <p>实际加成在 {@link RealmService} 的突破流程中自动消耗生效，手动服用仅作说明。</p>
 *
 * <p>提示语里的百分比直接取自 {@link RealmService#TRIBULATION_PILL_BONUS}，
 * 不再手写数字 —— 原来实现写 10%、物品描述写 20%，玩家看到的和实际拿到的对不上。</p>
 */
public class TribulationPillEffect extends ItemEffect {

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "服下渡劫丹，药力护体！下次突破大境界时将自动生效（成功率 +"
                + String.format("%.0f", RealmService.TRIBULATION_PILL_BONUS) + "%）";
    }

    public static TribulationPillEffect of() {
        return new TribulationPillEffect();
    }
}
