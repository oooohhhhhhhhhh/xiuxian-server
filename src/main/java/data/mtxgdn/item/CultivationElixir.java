package data.mtxgdn.item;

import com.mtxgdn.game.item.ExpEffect;
import com.mtxgdn.game.item.Item;
import com.mtxgdn.game.item.ItemType;
import com.mtxgdn.game.item.ItemRarity;

public class CultivationElixir extends Item {
    public CultivationElixir() {
        super("mtxgdn", "cultivation_elixir", ItemType.CONSUMABLE, ItemRarity.RARE,
            20, 100, true, 1, ExpEffect.of(500));
    }
}
