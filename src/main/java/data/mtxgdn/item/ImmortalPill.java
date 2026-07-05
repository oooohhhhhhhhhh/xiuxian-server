package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class ImmortalPill extends Item {
    public ImmortalPill() {
        super("mtxgdn", "immortal_pill", ItemType.CONSUMABLE, ItemRarity.LEGENDARY,
            99, 500, true, 0, HealEffect.of(500, 500));
    }
}