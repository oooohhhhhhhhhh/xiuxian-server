package data.mtxgdn.item;

import com.mtxgdn.game.item.TeleportEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class TeleportTalisman extends Item {
    public TeleportTalisman() {
        super("mtxgdn", "teleport_talisman", ItemType.CONSUMABLE, ItemRarity.UNCOMMON,
            20, 100, true, 1, TeleportEffect.to("主城"));
    }
}