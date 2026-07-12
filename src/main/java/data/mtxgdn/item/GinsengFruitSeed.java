package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class GinsengFruitSeed extends Item {
    public GinsengFruitSeed() {
        super("mtxgdn", "ginseng_fruit_seed", ItemType.MATERIAL, ItemRarity.LEGENDARY, 1, 500, true, 1, EmptyEffect.INSTANCE);
    }
}