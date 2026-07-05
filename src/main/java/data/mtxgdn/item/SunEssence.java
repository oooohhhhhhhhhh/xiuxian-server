package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class SunEssence extends Item {
    public SunEssence() {
        super("mtxgdn", "sun_essence", ItemType.MATERIAL, ItemRarity.LEGENDARY,
            999, 800, true, 0, EmptyEffect.INSTANCE);
    }
}