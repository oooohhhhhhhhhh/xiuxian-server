package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class ImmortalDate extends Item {
    public ImmortalDate() {
        super("mtxgdn", "immortal_date", ItemType.MATERIAL, ItemRarity.EPIC, 1, 500, true, 1, EmptyEffect.INSTANCE);
    }
}