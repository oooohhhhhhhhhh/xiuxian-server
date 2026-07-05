package data.mtxgdn.item;

import com.mtxgdn.game.item.RebirthEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class RebirthPill extends Item {
    public RebirthPill() {
        super("mtxgdn", "rebirth_pill", ItemType.CONSUMABLE, ItemRarity.EPIC,
            5, 5000, true, 3, RebirthEffect.full());
    }
}