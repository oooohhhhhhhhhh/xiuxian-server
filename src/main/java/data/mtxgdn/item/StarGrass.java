package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class StarGrass extends Item {
    public StarGrass() {
        super("mtxgdn", "star_grass", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 60, true, 0, EmptyEffect.INSTANCE);
    }
}
