package data.mtxgdn.item;

import com.mtxgdn.game.item.DebuffCleanEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class PurificationPill extends Item {
    public PurificationPill() {
        super("mtxgdn", "purification_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            30, 80, true, 1, DebuffCleanEffect.all());
    }
}