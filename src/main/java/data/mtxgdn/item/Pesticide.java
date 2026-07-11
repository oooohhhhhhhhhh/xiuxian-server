package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class Pesticide extends Item {
    public Pesticide() {
        super("mtxgdn", "pesticide", ItemType.MATERIAL, ItemRarity.COMMON,
            50, 5, true, 10, EmptyEffect.INSTANCE);
    }
}
