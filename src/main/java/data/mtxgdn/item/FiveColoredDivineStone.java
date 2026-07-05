package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class FiveColoredDivineStone extends Item {
    public FiveColoredDivineStone() {
        super("mtxgdn", "five_colored_divine_stone", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            999, 500, true, 0, EmptyEffect.INSTANCE);
    }
}
