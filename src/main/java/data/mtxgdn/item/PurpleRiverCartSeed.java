package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class PurpleRiverCartSeed extends Item {
    public PurpleRiverCartSeed() {
        super("mtxgdn", "purple_river_cart_seed", ItemType.MATERIAL, ItemRarity.EPIC, 1, 400, true, 1, EmptyEffect.INSTANCE);
    }
}