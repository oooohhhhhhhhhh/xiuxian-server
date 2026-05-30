package data.mtxgdn.item;

import com.mtxgdn.game.item.EmptyEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DragonBloodCrystal extends Item {
    public DragonBloodCrystal() {
        super("mtxgdn", "dragon_blood_crystal", ItemType.MATERIAL, ItemRarity.EPIC,
            50, 5000, true, 3, EmptyEffect.INSTANCE);
    }
}
