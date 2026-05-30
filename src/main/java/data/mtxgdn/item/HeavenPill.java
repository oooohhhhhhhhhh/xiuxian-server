package data.mtxgdn.item;

import com.mtxgdn.game.item.ExpEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class HeavenPill extends Item {
    public HeavenPill() {
        super("mtxgdn", "heaven_pill", ItemType.CONSUMABLE, ItemRarity.LEGENDARY,
            5, 10000, true, 4, ExpEffect.of(10000));
    }
}
