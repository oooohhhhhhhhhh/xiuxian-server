package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class UltimatePowerPill extends Item {
    public UltimatePowerPill() {
        super("mtxgdn", "ultimate_power_pill", ItemType.CONSUMABLE, ItemRarity.EPIC,
            99, 200, true, 0, BuffEffect.of(50, 50, 20, 30, 600));
    }
}