package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class NineTurnGrassSeed extends Item {
    public NineTurnGrassSeed() {
        super("mtxgdn", "nine_turn_grass_seed", ItemType.MATERIAL, ItemRarity.MYTHIC, 1, 1000, true, 1, EmptyEffect.INSTANCE);
    }
}