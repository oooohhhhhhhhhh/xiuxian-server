package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DemonCore extends Item {
    public DemonCore() {
        super("mtxgdn", "demon_core", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            999, 80, true, 1, EmptyEffect.INSTANCE);
    }
}