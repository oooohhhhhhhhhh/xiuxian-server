package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SilverOre extends Item {
    public SilverOre() {
        super("mtxgdn", "silver_ore", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            999, 20, true, 0, EmptyEffect.INSTANCE);
    }
}