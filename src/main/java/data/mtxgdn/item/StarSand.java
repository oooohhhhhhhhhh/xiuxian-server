package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class StarSand extends Item {
    public StarSand() {
        super("mtxgdn", "star_sand", ItemType.MATERIAL, ItemRarity.EPIC,
            50, 500, true, 3, EmptyEffect.INSTANCE);
    }
}