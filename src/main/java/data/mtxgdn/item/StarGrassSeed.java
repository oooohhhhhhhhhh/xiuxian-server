package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class StarGrassSeed extends Item {
    public StarGrassSeed() {
        super("mtxgdn", "star_grass_seed", ItemType.SEED, ItemRarity.EPIC,
            999, 100, true, 0, EmptyEffect.INSTANCE);
    }
}
