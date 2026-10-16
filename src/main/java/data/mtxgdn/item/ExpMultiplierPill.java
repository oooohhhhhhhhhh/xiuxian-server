package data.mtxgdn.item;

import com.mtxgdn.game.item.ExpMultiplierEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ExpMultiplierPill extends Item {
    public ExpMultiplierPill() {
        // 经验获取效率 +50%（300 秒），真实作用于经验结算
        super("mtxgdn", "exp_multiplier_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            10, 300, true, 2, ExpMultiplierEffect.of(50, 300));
    }
}