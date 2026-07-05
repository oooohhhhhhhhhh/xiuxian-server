package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class FireVineSeed extends Item {
    public FireVineSeed() {
        super("mtxgdn", "fire_vine_seed", ItemType.SEED, ItemRarity.UNCOMMON,
            999, 20, true, 0, EmptyEffect.INSTANCE);
    }
}