package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class PeachSeed extends Item {
    public PeachSeed() {
        super("mtxgdn", "peach_seed", ItemType.MATERIAL, ItemRarity.UNCOMMON, 1, 50, true, 1, EmptyEffect.INSTANCE);
    }
}