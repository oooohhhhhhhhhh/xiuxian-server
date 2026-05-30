package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class PowerBuffPill extends Item {
    public PowerBuffPill() {
        super("mtxgdn", "power_buff_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            30, 50, true, 1, BuffEffect.of(30, 20, 0, 0, 60));
    }
}
