package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritGrass extends Item {
    public SpiritGrass() {
        super("mtxgdn", "spirit_grass", ItemType.MATERIAL, ItemRarity.COMMON,
            999, 5, true, 0, EmptyEffect.INSTANCE);
    }
}
