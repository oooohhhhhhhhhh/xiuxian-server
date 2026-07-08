package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class HighSpiritStone extends Item {
    public HighSpiritStone() {
        super("mtxgdn", "spirit_stone_high", ItemType.CURRENCY, ItemRarity.RARE,
            999999, 1000000, true, 0, EmptyEffect.INSTANCE);
    }
}