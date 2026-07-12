package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemRarity;
import com.mtxgdn.game.item.ItemType;

public class NineTurnGrass extends Item {
    public NineTurnGrass() {
        super("mtxgdn", "nine_turn_grass", ItemType.MATERIAL, ItemRarity.MYTHIC, 1, 2000, true, 1, EmptyEffect.INSTANCE);
    }
}