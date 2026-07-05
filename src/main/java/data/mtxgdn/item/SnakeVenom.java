package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SnakeVenom extends Item {
    public SnakeVenom() {
        super("mtxgdn", "snake_venom", ItemType.MATERIAL, ItemRarity.RARE,
            999, 60, true, 0, EmptyEffect.INSTANCE);
    }
}