package data.mtxgdn.item;

import com.mtxgdn.game.item.HealEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpiritRecoveryPill extends Item {
    public SpiritRecoveryPill() {
        super("mtxgdn", "spirit_recovery_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            50, 30, true, 0, HealEffect.of(100, 80));
    }
}
