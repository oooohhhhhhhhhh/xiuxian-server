package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritPearl extends Item {
    public SpiritPearl() {
        super("mtxgdn", "spirit_pearl", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 400, true, 0, EmptyEffect.INSTANCE);
    }
}