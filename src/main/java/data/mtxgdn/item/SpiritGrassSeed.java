package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritGrassSeed extends Item {
    public SpiritGrassSeed() {
        super("mtxgdn", "spirit_grass_seed", ItemType.SEED, ItemRarity.COMMON,
            999, 10, true, 0, EmptyEffect.INSTANCE);
    }
}