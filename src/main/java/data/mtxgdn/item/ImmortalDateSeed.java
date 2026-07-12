package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class ImmortalDateSeed extends Item {
    public ImmortalDateSeed() {
        super("mtxgdn", "immortal_date_seed", ItemType.MATERIAL, ItemRarity.EPIC, 1, 300, true, 1, EmptyEffect.INSTANCE);
    }
}