package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class DragonSword extends Item {
    public DragonSword() {
        super("mtxgdn", "dragon_sword", ItemType.TREASURE, ItemRarity.LEGENDARY,
            1, 50000, true, 4, BuffEffect.of(200, 50, 50, 80, 0));
    }
}