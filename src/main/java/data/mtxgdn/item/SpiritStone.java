package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class SpiritStone extends Item {
    public SpiritStone() {
        super("mtxgdn", "spirit_stone", ItemType.CURRENCY, ItemRarity.COMMON,
            999999, 1, true, 0, EmptyEffect.INSTANCE);
    }
}
