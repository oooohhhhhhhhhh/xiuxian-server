package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DragonScale extends Item {
    public DragonScale() {
        super("mtxgdn", "dragon_scale", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            20, 2000, true, 4, EmptyEffect.INSTANCE);
    }
}