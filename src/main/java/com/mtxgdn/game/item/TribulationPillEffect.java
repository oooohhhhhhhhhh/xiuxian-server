package com.mtxgdn.game.item;

import com.mtxgdn.game.service.ItemService;
import com.mtxgdn.game.service.PlayerService;

/**
 * 渡劫丹效果：服用时提示将在突破时自动生效（+10% 成功率）。
 * 实际加成在 RealmService 突破流程中自动消耗生效，手动服用仅作说明。
 */
public class TribulationPillEffect extends ItemEffect {

    @Override
    public String execute(long playerId, PlayerService playerService, ItemService itemService) {
        return "服下渡劫丹，药力护体！下次突破大境界时将自动生效（成功率 +10%）";
    }

    public static TribulationPillEffect of() {
        return new TribulationPillEffect();
    }
}
