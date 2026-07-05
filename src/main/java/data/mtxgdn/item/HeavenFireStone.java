package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class HeavenFireStone extends Item {
    public HeavenFireStone() {
        super("mtxgdn", "heaven_fire_stone", ItemType.MATERIAL, ItemRarity.RARE,
            99, 200, true, 2, EmptyEffect.INSTANCE);
    }
}