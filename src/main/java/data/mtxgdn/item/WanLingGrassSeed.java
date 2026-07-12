package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class WanLingGrassSeed extends Item {
    public WanLingGrassSeed() {
        super("mtxgdn", "wan_ling_grass_seed", ItemType.MATERIAL, ItemRarity.COMMON, 1, 30, true, 1, EmptyEffect.INSTANCE);
    }
}