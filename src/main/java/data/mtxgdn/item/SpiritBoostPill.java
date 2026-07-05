package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritBoostPill extends Item {
    public SpiritBoostPill() {
        super("mtxgdn", "spirit_boost_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            99, 80, true, 0, BuffEffect.of(0, 0, 0, 50, 300));
    }
}