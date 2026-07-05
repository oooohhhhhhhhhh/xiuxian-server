package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DamageReflectionPill extends Item {
    public DamageReflectionPill() {
        super("mtxgdn", "damage_reflection_pill", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            20, 120, true, 1, BuffEffect.of(0, 30, 0, 0, 120));
    }
}