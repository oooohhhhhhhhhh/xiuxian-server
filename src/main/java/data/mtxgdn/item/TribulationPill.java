package data.mtxgdn.item;

import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.TribulationPillEffect;

public class TribulationPill extends Item {
    public TribulationPill() {
        // 价格与灵石商店一致（500），突破时自动消耗生效 +10% 成功率
        super("mtxgdn", "tribulation_pill", ItemType.CONSUMABLE, ItemRarity.EPIC,
            5, 500, true, 3, TribulationPillEffect.of());
    }
}
