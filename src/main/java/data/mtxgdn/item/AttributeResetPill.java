package data.mtxgdn.item;

import com.mtxgdn.game.item.AttributeResetEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class AttributeResetPill extends Item {
    public AttributeResetPill() {
        super("mtxgdn", "attribute_reset_pill", ItemType.CONSUMABLE, ItemRarity.RARE,
            10, 500, true, 2, AttributeResetEffect.of());
    }
}