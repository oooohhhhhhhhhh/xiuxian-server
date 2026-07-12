package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class PurpleRiverCart extends Item {
    public PurpleRiverCart() {
        super("mtxgdn", "purple_river_cart", ItemType.MATERIAL, ItemRarity.EPIC, 1, 700, true, 1, EmptyEffect.INSTANCE);
    }
}