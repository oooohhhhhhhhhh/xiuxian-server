package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class MoonEssence extends Item {
    public MoonEssence() {
        super("mtxgdn", "moon_essence", ItemType.MATERIAL, ItemRarity.EPIC,
            999, 350, true, 0, EmptyEffect.INSTANCE);
    }
}