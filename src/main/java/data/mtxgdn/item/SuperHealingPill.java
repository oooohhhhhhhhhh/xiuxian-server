package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SuperHealingPill extends Item {
    public SuperHealingPill() {
        super("mtxgdn", "super_healing_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            99, 50, true, 0, HealEffect.hp(200));
    }
}