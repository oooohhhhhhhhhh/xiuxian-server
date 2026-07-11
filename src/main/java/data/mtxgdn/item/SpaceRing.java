package data.mtxgdn.item;

import com.mtxgdn.game.item.InventoryCapacityEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SpaceRing extends Item {
    public SpaceRing() {
        super("mtxgdn", "space_ring", ItemType.TREASURE, ItemRarity.RARE,
            1, 5000, true, 2, InventoryCapacityEffect.of(20));
    }
}