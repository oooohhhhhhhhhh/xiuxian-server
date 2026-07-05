package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DeepSeaCoral extends Item {
    public DeepSeaCoral() {
        super("mtxgdn", "deep_sea_coral", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 150, true, 0, EmptyEffect.INSTANCE);
    }
}
