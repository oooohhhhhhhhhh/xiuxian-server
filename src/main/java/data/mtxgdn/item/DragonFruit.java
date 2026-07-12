package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class DragonFruit extends Item {
    public DragonFruit() {
        super("mtxgdn", "dragon_fruit", ItemType.MATERIAL, ItemRarity.RARE, 1, 250, true, 1, EmptyEffect.INSTANCE);
    }
}