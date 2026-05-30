package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class HealingPill extends Item {
    public HealingPill() {
        super("mtxgdn", "healing_pill", ItemType.CONSUMABLE, ItemRarity.COMMON,
            99, 10, true, 0, HealEffect.hp(50));
    }
}
