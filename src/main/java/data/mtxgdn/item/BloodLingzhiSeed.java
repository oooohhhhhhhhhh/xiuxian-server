package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class BloodLingzhiSeed extends Item {
    public BloodLingzhiSeed() {
        super("mtxgdn", "blood_lingzhi_seed", ItemType.SEED, ItemRarity.RARE,
            999, 80, true, 0, EmptyEffect.INSTANCE);
    }
}
