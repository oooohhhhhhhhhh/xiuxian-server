package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class MiddleSpiritStone extends Item {
    public MiddleSpiritStone() {
        super("mtxgdn", "spirit_stone_mid", ItemType.CURRENCY, ItemRarity.UNCOMMON,
            999999, 1000, true, 0, EmptyEffect.INSTANCE);
    }
}