package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class IronOre extends Item {
    public IronOre() {
        super("mtxgdn", "iron_ore", ItemType.MATERIAL, ItemRarity.COMMON,
            999, 8, true, 0, EmptyEffect.INSTANCE);
    }
}
