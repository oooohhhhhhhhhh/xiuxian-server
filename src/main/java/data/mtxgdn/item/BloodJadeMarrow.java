package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class BloodJadeMarrow extends Item {
    public BloodJadeMarrow() {
        super("mtxgdn", "blood_jade_marrow", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 250, true, 0, EmptyEffect.INSTANCE);
    }
}
