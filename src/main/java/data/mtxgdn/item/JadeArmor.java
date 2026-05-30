package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class JadeArmor extends Item {
    public JadeArmor() {
        super("mtxgdn", "jade_armor", ItemType.TREASURE, ItemRarity.LEGENDARY,
            1, 30000, true, 4, BuffEffect.of(80, 200, 30, 100, 0));
    }
}
