package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class NetherFlowerSeed extends Item {
    public NetherFlowerSeed() {
        super("mtxgdn", "nether_flower_seed", ItemType.SEED, ItemRarity.RARE,
            999, 50, true, 0, EmptyEffect.INSTANCE);
    }
}
