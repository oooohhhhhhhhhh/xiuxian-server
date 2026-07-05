package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class TigerFang extends Item {
    public TigerFang() {
        super("mtxgdn", "tiger_fang", ItemType.MATERIAL, ItemRarity.UNCOMMON,
            999, 40, true, 0, EmptyEffect.INSTANCE);
    }
}