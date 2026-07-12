package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class HeShouWuSeed extends Item {
    public HeShouWuSeed() {
        super("mtxgdn", "he_shou_wu_seed", ItemType.MATERIAL, ItemRarity.RARE, 1, 180, true, 1, EmptyEffect.INSTANCE);
    }
}