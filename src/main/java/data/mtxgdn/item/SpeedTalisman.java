package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpeedTalisman extends Item {
    public SpeedTalisman() {
        super("mtxgdn", "speed_talisman", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            20, 40, true, 1, BuffEffect.of(0, 0, 25, 10, 120));
    }
}
