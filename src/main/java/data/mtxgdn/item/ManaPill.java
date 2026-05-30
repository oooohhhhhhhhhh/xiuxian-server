package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ManaPill extends Item {
    public ManaPill() {
        super("mtxgdn", "mana_pill", ItemType.CONSUMABLE, ItemRarity.COMMON,
            99, 10, true, 0, HealEffect.mp(30));
    }
}
