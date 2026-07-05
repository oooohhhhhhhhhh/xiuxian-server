package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class FireVine extends Item {
    public FireVine() {
        super("mtxgdn", "fire_vine", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            999, 35, true, 0, EmptyEffect.INSTANCE);
    }
}