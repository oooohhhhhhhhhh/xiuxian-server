package data.mtxgdn.item;

import com.mtxgdn.game.item.CurrencyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class GoldBag extends Item {
    public GoldBag() {
        super("mtxgdn", "gold_bag", ItemType.CONSUMABLE, ItemRarity.COMMON,
            999, 0, false, 0, CurrencyEffect.of(100, 0));
    }
}
