package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class PhoenixArmor extends Item {
    public PhoenixArmor() {
        super("mtxgdn", "phoenix_armor", ItemType.TREASURE, ItemRarity.LEGENDARY,
            1, 60000, true, 4, BuffEffect.of(60, 300, 20, 60, 0));
    }
}