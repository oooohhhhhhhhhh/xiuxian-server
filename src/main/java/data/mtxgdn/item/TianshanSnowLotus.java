package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class TianshanSnowLotus extends Item {
    public TianshanSnowLotus() {
        super("mtxgdn", "tianshan_snow_lotus", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 200, true, 0, EmptyEffect.INSTANCE);
    }
}