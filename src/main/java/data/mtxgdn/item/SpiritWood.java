package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritWood extends Item {
    public SpiritWood() {
        super("mtxgdn", "spirit_wood", ItemType.MATERIAL, ItemRarity.COMMON,
            999, 15, true, 0, EmptyEffect.INSTANCE);
    }
}