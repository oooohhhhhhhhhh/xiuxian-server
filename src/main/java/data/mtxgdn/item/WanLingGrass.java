package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class WanLingGrass extends Item {
    public WanLingGrass() {
        super("mtxgdn", "wan_ling_grass", ItemType.MATERIAL, ItemRarity.COMMON, 1, 50, true, 1, EmptyEffect.INSTANCE);
    }
}