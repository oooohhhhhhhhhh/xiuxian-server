package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DragonHeart extends Item {
    public DragonHeart() {
        super("mtxgdn", "dragon_heart", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            999, 1000, true, 0, EmptyEffect.INSTANCE);
    }
}