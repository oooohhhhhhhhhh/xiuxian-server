package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class StarRobes extends Item {
    public StarRobes() {
        super("mtxgdn", "star_robes", ItemType.TREASURE, ItemRarity.EPIC,
            1, 25000, true, 3, BuffEffect.of(40, 150, 40, 120, 0));
    }
}