package data.mtxgdn.item;

import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritStonePouch extends Item {
    public SpiritStonePouch() {
        super("mtxgdn", "spirit_stone_pouch", ItemType.CONSUMABLE, ItemRarity.COMMON,
            999, 0, false, 0, CurrencyEffect.of(0, 50, 0));
    }
}