package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class SupremeSpiritStone extends Item {
    public SupremeSpiritStone() {
        super("mtxgdn", "spirit_stone_supreme", ItemType.CURRENCY, ItemRarity.EPIC,
            999999, 1000000000, true, 0, EmptyEffect.INSTANCE);
    }
}