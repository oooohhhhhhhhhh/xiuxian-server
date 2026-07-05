package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class IceCrystal extends Item {
    public IceCrystal() {
        super("mtxgdn", "ice_crystal", ItemType.MATERIAL, ItemRarity.RARE,
            99, 180, true, 2, EmptyEffect.INSTANCE);
    }
}