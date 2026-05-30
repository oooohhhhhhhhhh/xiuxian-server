package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritSword extends Item {
    public SpiritSword() {
        super("mtxgdn", "spirit_sword", ItemType.TREASURE, ItemRarity.RARE,
            1, 3000, true, 2, BuffEffect.of(50, 0, 10, 20, 0));
    }
}
