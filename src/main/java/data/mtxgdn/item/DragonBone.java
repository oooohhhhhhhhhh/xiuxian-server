package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DragonBone extends Item {
    public DragonBone() {
        super("mtxgdn", "dragon_bone", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 300, true, 0, EmptyEffect.INSTANCE);
    }
}