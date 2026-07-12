package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class DragonFruitSeed extends Item {
    public DragonFruitSeed() {
        super("mtxgdn", "dragon_fruit_seed", ItemType.MATERIAL, ItemRarity.RARE, 1, 150, true, 1, EmptyEffect.INSTANCE);
    }
}