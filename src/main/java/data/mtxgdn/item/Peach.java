package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class Peach extends Item {
    public Peach() {
        super("mtxgdn", "peach", ItemType.MATERIAL, ItemRarity.UNCOMMON, 1, 80, true, 1, EmptyEffect.INSTANCE);
    }
}