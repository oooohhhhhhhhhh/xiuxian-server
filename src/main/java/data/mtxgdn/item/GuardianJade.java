package data.mtxgdn.item;

import com.mtxgdn.game.item.BuffEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class GuardianJade extends Item {
    public GuardianJade() {
        super("mtxgdn", "guardian_jade", ItemType.TREASURE, ItemRarity.EPIC,
            1, 8000, true, 3, BuffEffect.of(0, 100, 0, 50, 0));
    }
}
