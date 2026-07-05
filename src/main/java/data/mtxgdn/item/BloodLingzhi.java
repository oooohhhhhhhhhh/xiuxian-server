package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class BloodLingzhi extends Item {
    public BloodLingzhi() {
        super("mtxgdn", "blood_lingzhi", ItemType.MATERIAL, ItemRarity.RARE,
            999, 80, true, 0, EmptyEffect.INSTANCE);
    }
}