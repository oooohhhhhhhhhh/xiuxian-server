package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class NetherFlower extends Item {
    public NetherFlower() {
        super("mtxgdn", "nether_flower", ItemType.MATERIAL, ItemRarity.RARE,
            999, 30, true, 0, EmptyEffect.INSTANCE);
    }
}
