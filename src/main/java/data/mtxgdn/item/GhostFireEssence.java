package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class GhostFireEssence extends Item {
    public GhostFireEssence() {
        super("mtxgdn", "ghost_fire_essence", ItemType.MATERIAL, ItemRarity.RARE,
            999, 100, true, 0, EmptyEffect.INSTANCE);
    }
}
