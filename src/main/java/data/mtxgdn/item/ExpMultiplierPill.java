package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ExpMultiplierPill extends Item {
    public ExpMultiplierPill() {
        super("mtxgdn", "exp_multiplier_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            10, 300, true, 2, BuffEffect.of(0, 0, 0, 0, 300));
    }
}