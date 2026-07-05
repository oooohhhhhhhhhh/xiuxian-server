package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SuperManaPill extends Item {
    public SuperManaPill() {
        super("mtxgdn", "super_mana_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            99, 50, true, 0, HealEffect.mp(200));
    }
}