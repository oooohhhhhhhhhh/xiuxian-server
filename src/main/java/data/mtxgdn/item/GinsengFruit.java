package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class GinsengFruit extends Item {
    public GinsengFruit() {
        super("mtxgdn", "ginseng_fruit", ItemType.MATERIAL, ItemRarity.LEGENDARY, 1, 1000, true, 1, EmptyEffect.INSTANCE);
    }
}