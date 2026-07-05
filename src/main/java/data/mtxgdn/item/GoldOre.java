package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class GoldOre extends Item {
    public GoldOre() {
        super("mtxgdn", "gold_ore", ItemType.MATERIAL, ItemRarity.RARE,
            999, 50, true, 0, EmptyEffect.INSTANCE);
    }
}